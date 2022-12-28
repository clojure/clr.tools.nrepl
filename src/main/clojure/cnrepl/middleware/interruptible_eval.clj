(ns cnrepl.middleware.interruptible-eval
  "Supports the ability to evaluation code. The name of the middleware is
  slightly misleading, as interrupt is currently supported at a session level
  but the name is retained for backwards compatibility."
  {:author "Chas Emerick"}
  (:require
   clojure.main
   clojure.test
   [cnrepl.middleware :refer [set-descriptor!]]  [cnrepl.debug :as debug]
   [cnrepl.middleware.caught :as caught]
   [cnrepl.middleware.print :as print]
   [cnrepl.misc :as misc :refer [response-for ]]                       ;;; with-session-classloader -- removed
   [cnrepl.transport :as t])
  (:import
   (clojure.lang Compiler+CompilerException LineNumberingTextReader)   ;;;Compiler$CompilerException LineNumberingPushbackReader
   (System.IO StringReader TextWriter)                                 ;;;(java.io FilterReader LineNumberReader StringReader Writer)
   (System.Threading ThreadInterruptedException)))                     ;;; (java.lang.reflect Field)

(def ^:dynamic *msg*
  "The message currently being evaluated."
  nil)

(defn- capture-thread-bindings
  "Capture thread bindings, excluding nrepl implementation vars."
  []
  (dissoc (get-thread-bindings) #'*msg*))

(defn- set-line!
  [^LineNumberingTextReader reader line]                                         ;;; ^LineNumberingPushbackReader
  (-> reader (.set_LineNumber line)))                                            ;;; .setLineNumber

(defn- set-column!                                                               ;;; It would be easier to make the column number settable.  Why not, if rown
  [^LineNumberingTextReader reader column]                                       ;;; ^LineNumberingPushbackReader
  (when-let [field (.GetField LineNumberingTextReader "_columnNumber"            ;;; (->> LineNumberingPushbackReader
                        (enum-or System.Reflection.BindingFlags/NonPublic        ;;;      (.getDeclaredFields)
					             System.Reflection.BindingFlags/Instance))]      ;;; (filter #(= "_columnNumber" (.getName ^Field %)))
                                                                                 ;;; first)
    (.SetValue field reader column)                                              ;;; (-> ^Field field
                                                                                 ;;;     (doto (.setAccessible true))
    ))                                                                           ;;;           (.set reader column))

(defn- source-logging-pushback-reader
  [code line column]
  (let [reader (LineNumberingTextReader. (StringReader. code))]                  ;;; LineNumberingPushbackReader.
    (when line (set-line! reader (int line)))
    (when column (set-column! reader (int column)))
    reader))

(defn- interrupted?
  "Returns true if the given throwable was ultimately caused by an interrupt."
  [^Exception e]                                                                 ;;; ^Throwable    SHOULD THESE BE ThreadAbortException?  Only in 461?
  (or (instance? ThreadInterruptedException (clojure.main/root-cause e))         ;;; ThreadDeath
      (and (instance? Compiler+CompilerException e)                              ;;; Compiler$CompilerException
           (instance? ThreadInterruptedException (.InnerException e)))))         ;;; ThreadDeath   .getCause

(defn evaluate
  "Evaluates a msg's code within the dynamic context of its session.

   Uses `clojure.main/repl` to drive the evaluation of :code (either a string
   or a seq of forms to be evaluated), which may also optionally specify a :ns
   (resolved via `find-ns`).  The map MUST contain a Transport implementation
   in :transport; expression results and errors will be sent via that Transport.

   Note: we are doubling up on restoring of ctxcl in a `catch` block both here
   and within `misc/with-session-classloader`. Not too sure why this is needed,
   but it does seem to be a fix for https://github.com/nrepl/nrepl/issues/206"
  [{:keys [transport session eval ns code file line column out-limit]
    :as msg}]
  (let [explicit-ns (and ns (-> ns symbol find-ns))
        original-ns (@session #'*ns*)
        maybe-restore-original-ns (if explicit-ns
                                    #(assoc % #'*ns* original-ns)
                                    identity)]
    (if (and ns (not explicit-ns))
      (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}
                                           :ns ns}))
      (let [                                                                            ;;; no such thing for CLR:   ctxcl (.getContextClassLoader (Thread/currentThread))
            ;; TODO: out-limit -> out-buffer-size | err-buffer-size
            ;; TODO: new options: out-quota | err-quota
            opts {::print/buffer-size (or out-limit (get (meta session) :out-limit))}
            out (print/replying-PrintWriter :out msg opts)
            err (print/replying-PrintWriter :err msg opts)]
        (try
          (clojure.main/repl
           :eval (let [eval-fn (if eval (find-var (symbol eval)) clojure.core/eval)]
                   (fn [form]
                     (eval-fn form)))                                                   ;;; (with-session-classloader session (eval-fn form))
           :init #(let [bindings
                        (-> (get-thread-bindings)
                            (into caught/default-bindings)
                            (into print/default-bindings)
                            (into @session)
                            (into {#'*out* out
                                   #'*err* err
                                   ;; clojure.test captures *out* at load-time, so we need to make sure
                                   ;; runtime output of test status/results is redirected properly
                                   ;; TODO: is this something we need to consider in general, or is this
                                   ;; specific hack reasonable?
                                   #'clojure.test/*test-out* out})
                            (cond-> explicit-ns (assoc #'*ns* explicit-ns)
                                    file (assoc #'*file* file)))]
                    (pop-thread-bindings)
                    (push-thread-bindings bindings))
           :read (if (string? code)
                   (let [reader (source-logging-pushback-reader code line column)
                         read-cond (or (-> msg :read-cond keyword)
                                       :allow)]
                     #(try (read {:read-cond read-cond :eof %2} reader)
                           (catch Exception e                                                   ;;; RuntimeException
                             ;; If error happens during reading the string, we
                             ;; don't want eval to start reading and executing the
                             ;; rest of it. So we skip over the remaining text.
                             (.ReadToEnd ^LineNumberingTextReader reader)                      ;;; (.skip ^LineNumberingPushbackReader reader Long/MAX_VALUE)
                             (throw e))))
                   (let [code (.GetEnumerator ^System.Collections.IEnumerable code)]           ;;;  .iterator ^Iterable
                     #(or (and (.MoveNext code) (.Current code) ) %2)))                        ;;; (.hasNext code) (.next code)
           :prompt #(reset! session (maybe-restore-original-ns (capture-thread-bindings)))
           :need-prompt (constantly true)
           :print (fn [value]
                    ;; *out* has :tag metadata; *err* does not
                    (.Flush ^TextWriter *err*)                                                 ;;; .flush ^Writer
                    (.Flush ^TextWriter *out*)                                                 ;;; .flush  -- added type hint  TODO -- not clear this is always true, based on a comment I made elsewhere
                    (t/send transport (response-for msg {:ns (str (ns-name *ns*))
                                                         :value value
                                                         ::print/keys #{:value}})))
           :caught (fn [^Exception e]                                                          ;;; Throwable
                     (when-not (interrupted? e)
                       (let [resp {::caught/throwable e
                                   :status :eval-error
                                   :ex (str (class e))
                                   :root-ex (str (class (clojure.main/root-cause e)))}]
                         (t/send transport (response-for msg resp))))))
          (finally
                                                                                               ;;;  (when (misc/java-8?)
                                                                                               ;;;    (.setContextClassLoader (Thread/currentThread) ctxcl))
            (.Flush ^TextWriter err)                                                 ;;; .flush  -- added type hint
            (.Flush ^TextWriter out)))))))                                                 ;;; .flush  -- added type hint

(defn interruptible-eval
  "Evaluation middleware that supports interrupts.  Returns a handler that supports
   \"eval\" and \"interrupt\" :op-erations that delegates to the given handler
   otherwise."
  [h & configuration]
  (fn [{:keys [op session id transport] :as msg}]
    (let [{:keys [exec] session-id :id} (meta session)]
      (case op
        "eval"
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code :done}))
          (exec id
                #(binding [*msg* msg]
                   (evaluate msg))
                #(t/send transport (response-for msg :status :done))))
        (h msg)))))

(set-descriptor! #'interruptible-eval
                 {:requires #{"clone" "close" #'caught/wrap-caught  #'print/wrap-print}
                  :expects #{}
                  :handles {"eval"
                            {:doc "Evaluates code. Note that unlike regular stream-based Clojure REPLs, nREPL's `\"eval\"` short-circuits on first read error and will not try to read and execute the remaining code in the message."
                             :requires {"code" "The code to be evaluated."
                                        "session" "The ID of the session within which to evaluate the code."}
                             :optional (merge caught/wrap-caught-optional-arguments
                                              print/wrap-print-optional-arguments
                                              {"id" "An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later \"interrupt\" operation."
                                               "eval" "A fully-qualified symbol naming a var whose function value will be used to evaluate [code], instead of `clojure.core/eval` (the default)."
                                               "ns" "The namespace in which to perform the evaluation. The supplied namespace must exist already (e.g. be loaded). If no namespace is specified the evaluation falls back to `*ns*` for the session in question."
                                               "file" "The path to the file containing [code]. `clojure.core/*file*` will be bound to this."
                                               "line" "The line number in [file] at which [code] starts."
                                               "column" "The column number in [file] at which [code] starts."
                                               "read-cond" "The options passed to the reader before the evaluation. Useful when middleware in a higher layer wants to process reader conditionals."})
                             :returns {"ns" "*ns*, after successful evaluation of `code`."
                                       "value" "The result of evaluating `code`, often `read`able. This printing is provided by the `print` middleware. Superseded by `ex` and `root-ex` if an exception occurs during evaluation."
                                       "ex" "The type of exception thrown, if any. If present, then `:value` will be absent."
                                       "root-ex" "The type of the root exception thrown, if any. If present, then `:value` will be absent."}}}})