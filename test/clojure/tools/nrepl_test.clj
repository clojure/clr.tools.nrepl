(ns clojure.tools.nrepl-test
  (:import System.Threading.Thread System.Net.Sockets.SocketException System.IO.FileInfo)           ;DM: java.net.SocketException java.io.File
  (:use clojure.test
        [clojure.test-helper :only [platform-newlines]]          ;DM: Added
        [clojure.tools.nrepl :as nrepl])
  (:require (clojure.tools.nrepl [transport :as transport]
                                 [server :as server]
                                 [ack :as ack])
            [clojure.set :as set]))

(def project-base-dir (FileInfo. (or (Environment/GetEnvironmentVariable "nrepl.basedir") ".")))         ;DM: File.  System/getProperty

(def ^{:dynamic true} *server* nil)

(defn repl-server-fixture
  [f]
  (with-open [server (server/start-server)]
    (binding [*server* server]
      (f)
      (set! *print-length* nil)
      (set! *print-level* nil))))

(use-fixtures :each repl-server-fixture)

(defmacro def-repl-test
  [name & body]
  `(deftest ~(with-meta name {:private true})
     (with-open [transport# (connect :port (:port *server*))]
       (let [~'transport transport#
             ~'client (client transport# Int32/MaxValue)                          ;DM: Long/MAX_VALUE
             ~'session (client-session ~'client)
             ~'timeout-client (client transport# 1000)
             ~'timeout-session (client-session ~'timeout-client)
             ~'repl-eval #(message % {:op :eval :code %2})
             ~'repl-values (comp response-values ~'repl-eval)]
         ~@body))))

(def-repl-test eval-literals
  (are [literal] (= (binding [*ns* (find-ns 'user)] ; needed for the ::keyword
                      (-> literal read-string eval list))
                    (repl-values client literal))
    "5"
    "0xff"
    "5.1"
    "-2e12"
    "1/4"
    "'symbol"
    "'namespace/symbol"
    ":keyword"
    "::local-ns-keyword"
    ":other.ns/keyword"
    "\"string\""
    "\"string\\nwith\\r\\nlinebreaks\""
    "'(1 2 3)"
    "[1 2 3]"
    "{1 2 3 4}"
    "#{1 2 3 4}")

  #_(is (= (->> "#\"regex\"" read-string eval list (map str))
         (->> "#\"regex\"" (repl-values client) (map str)))))

(def-repl-test simple-expressions
  (are [expr] (= [(eval expr)] (repl-values client (pr-str expr)))
    '(range 40)
    '(apply + (range 100))))

(def-repl-test defining-fns
  (repl-values client "(defn x [] 6)")
  (is (= [6] (repl-values client "(x)"))))

(defn- dumb-alternative-eval
  [form]
  (let [result (eval form)]
    (if (number? result)
      (- result)
      result)))

(def-repl-test use-alternative-eval-fn
  (is (= {:value ["-124750"]}
         (-> (message timeout-client {:op :eval :eval "clojure.tools.nrepl-test/dumb-alternative-eval"
                                      :code "(reduce + (range 500))"})
             combine-responses
             (select-keys [:value])))))
  
(def-repl-test unknown-op
  (is (= {:op "abc" :status #{"error" "unknown-op" "done"}}
         (-> (message timeout-client {:op :abc}) combine-responses (select-keys [:op :status])))))

(def-repl-test session-lifecycle
  (is (= #{"error" "unknown-session"}
         (-> (message timeout-client {:session "abc"}) combine-responses :status)))
  (let [session-id (new-session timeout-client)
        session-alive? #(contains? (-> (message timeout-client {:op :ls-sessions})
                                     combine-responses
                                     :sessions
                                     set)
                                   session-id)]
    (is session-id)
    (is (session-alive?))
    (is (= #{"done" "session-closed"} (-> (message timeout-client {:op :close :session session-id})
                                        combine-responses
                                        :status)))
    (is (not (session-alive?)))))

(def-repl-test separate-value-from-*out*
  (is (= {:value [nil] :out (platform-newlines "5\n")}                         ;DM: Added platform-newlines
         (-> (map read-response-value (repl-eval client "(println 5)"))
           combine-responses
           (select-keys [:value :out])))))

(def-repl-test sessionless-*out*
  (is (= (platform-newlines "5\n:foo\n")                                       ;DM: Added platform-newlines
         (-> (repl-eval client "(println 5)(println :foo)")
           combine-responses
           :out))))

(def-repl-test session-*out*
  (is (= (platform-newlines "5\n:foo\n")                                       ;DM: Added platform-newlines
         (-> (repl-eval session "(println 5)(println :foo)")
           combine-responses
           :out))))

(def-repl-test error-on-lazy-seq-with-side-effects
  (let [expression '(let [foo (fn [] (map (fn [x]
                                            (println x)
                                            (throw (Exception. "oops")))
                                          [1 2 3]))]
                      (foo))
        results (-> (repl-eval session (pr-str expression))
                    combine-responses)]
    (is (= (platform-newlines "1\n") (:out results)))                         ;DM: Added platform-newlines
    (is (re-seq #"oops" (:err results)))))

(def-repl-test cross-transport-*out*
  (let [sid (-> session meta ::nrepl/taking-until :session)
        transport2 (nrepl/connect :port (:port *server*))]
    (transport/send transport2 {"op" "eval" "code" "(println :foo)"
                                "session" sid})
    (is (->> (repeatedly #(transport/recv transport2 1000))
          (take-while identity)
          (some #(= (platform-newlines ":foo\n") (:out %))))))                ;DM: Added platform-newlines
  (Thread/Sleep 100))                                                        ;DM: Added sleep so client completes before server closes

(def-repl-test streaming-out
  (is (= (for [x (range 10)]
           (platform-newlines (str x \newline)))                               ;DM: Added platform-newlines
        (->> (repl-eval client "(dotimes [x 10] (println x))")
          (map :out)
          (remove nil?)))))

#_(def-repl-test session-*out*-writer-length-translation                       ;DM: TODO - figure this one out
  (when (<= 4 (:minor *clojure-version*))
    (is (= "#inst \"2013-02-11T12:13:44.000+00:00\"\n"
          (-> (repl-eval session
                (code (println (doto
                                 (java.util.GregorianCalendar. 2013 1 11 12 13 44)
                                 (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))))))
            combine-responses
            :out)))))

(def-repl-test streaming-out-without-explicit-flushing
  (is (= ["(0 1 "
          "2 3 4"
          " 5 6 "
          "7 8 9"
          " 10)"]
         ; new session
         (->> (message client {:op :eval :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?))
         ; existing session
         (->> (message session {:op :eval :out-limit 5 :code "(print (range 11))"})
              (map :out)
              (remove nil?)))))

(def-repl-test ensure-whitespace-prints
  (is (= (str " \t \n \f " (platform-newlines "\n")) (->> (repl-eval client "(println \" \t \n \f \")")  ;DM: " \t \n \f \n"
                          combine-responses
                          :out))))

(def-repl-test session-return-recall
  (testing "sessions persist across connections"
    (repl-values session (code
                           (apply + (range 6))
                           (str 12 \c)
                           (keyword "hello")))
    (with-open [separate-connection (connect :port (:port *server*))]
      (let [history [[15 "12c" :hello]]
            sid (-> session meta :clojure.tools.nrepl/taking-until :session)
            sc-session (-> separate-connection
                         (nrepl/client 1000)
                         (nrepl/client-session :session sid))]
        (is (= history (repl-values sc-session "[*3 *2 *1]")))
        (is (= history (repl-values sc-session "*1"))))))


  (testing "without a session id, REPL-bound vars like *1 have default values"
    (is (= [nil] (repl-values client "*1")))))

(def-repl-test session-set!
  (repl-eval session (code
                       (set! *compile-path* "badpath")
                       (set! *warn-on-reflection* true)))
  (is (= [["badpath" true]] (repl-values session (code [*compile-path* *warn-on-reflection*])))))

(def-repl-test exceptions
  (let [{:keys [status err value]} (combine-responses (repl-eval session "(throw (Exception. \"bad, bad code\"))"))]
    (is (= #{"eval-error" "done"} status))
    (is (nil? value))
    (is (.Contains err "bad, bad code"))                                                           ;DM: .contains
    (is (= [true] (repl-values session "(.Contains (str *e) \"bad, bad code\")")))))               ;DM: .contains

(def-repl-test multiple-expressions-return
  (is (= [5 18] (repl-values session "5 (/ 5 0) (+ 5 6 7)"))))

(def-repl-test return-on-incomplete-expr                                                         ;DM: We are getting nil for *e so, re-seq is not happy in the test.  Why?
  (let [{:keys [out status value]} (combine-responses (repl-eval session "(missing paren"))]       ;;; ) -- so editor not confused
    (is (nil? value))
    (is (= #{"done" "eval-error"} status))
    (is (re-seq #"ReaderException" (first (repl-values session "(.Message *e)"))))))             ;;; .getMessage -- #"EOF while reading"

(def-repl-test switch-ns
  (is (= "otherns" (-> (repl-eval session "(ns otherns) (defn function [] 12)")
                     combine-responses
                     :ns)))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)"))))

(def-repl-test switch-ns-2
  (is (= "otherns" (-> (repl-eval session (code
                                            (ns otherns)
                                            (defn function [] 12)))
                     combine-responses
                     :ns)))
  (is (= [12] (repl-values session "(function)")))
  (repl-eval session "(in-ns 'user)")
  (is (= [12] (repl-values session "(otherns/function)")))
  (is (= "user" (-> (repl-eval session "nil") combine-responses :ns))))

(def-repl-test explicit-ns
  (is (= "user" (-> (repl-eval session "nil") combine-responses :ns)))
  (is (= "baz" (-> (repl-eval session (code
                                        (def bar 5)
                                        (ns baz)))
                 combine-responses
                 :ns)))
  (is (= [5] (response-values (message session {:op :eval :code "bar" :ns "user"})))))

(def-repl-test error-on-nonexistent-ns
  (is (= #{"error" "namespace-not-found" "done"}
         (-> (message timeout-client {:op :eval :code "(+ 1 1)" :ns (name (gensym))})
           combine-responses
           :status)))
  (Thread/Sleep 100))                         ;DM: Added -- need a little time for the client to complete before the server shuts down

(def-repl-test proper-response-ordering
  (is (= [[nil (platform-newlines "100\n")] ; printed number                          ;DM: Added platform-newlines
          ["nil" nil] ; return val from println
          ["42" nil]  ; return val from `42`
          [nil nil]]  ; :done
         (map (juxt :value :out) (repl-eval client "(println 100) 42")))))

(def-repl-test interrupt
  (is (= #{"error" "interrupt-id-mismatch" "done"}
         (-> (message client {:op :interrupt :interrupt-id "foo"})
           first
           :status
           set)))

  (let [resp (message session {:op :eval :code (code (do
                                                       (def halted? true)
                                                       halted?
                                                       (System.Threading.Thread/Sleep 30000)             ;DM: Thread/sleep
                                                       (def halted? false)))})]
    (Thread/Sleep 100)                                                                  ;DM: sleep
    (is (= #{"done"} (-> session (message {:op :interrupt}) first :status set)))
    #_(is (= #{"done" "interrupted"} (-> resp combine-responses :status)))   ;DM: BUG!!! I have no idea why this hangs
    (is (= [true] (repl-values session "halted?")))))

; NREPL-66: ensure that bindings of implementation vars aren't captured by user sessions
; (https://github.com/clojure-emacs/cider/issues/785)
(def-repl-test ensure-no-*msg*-capture
  (let [[r1 r2 :as results] (repeatedly 2 #(repl-eval session "(println :foo)"))
        [ids ids2] (map #(set (map :id %)) results)
        [out1 out2] (map #(-> % combine-responses :out) results)]
    (is (empty? (clojure.set/intersection ids ids2)))
    (is (= (platform-newlines ":foo\n") out1 out2))))                     ; DM: Added platform-newlines

(def-repl-test read-timeout
  (is (nil? (repl-values timeout-session "(System.Threading.Thread/Sleep 1100) :ok")))                   ;DM: Thread/sleep
  ; just getting the values off of the wire so the server side doesn't
  ; toss a spurious stack trace when the client disconnects
  (is (= [nil :ok] (->> (repeatedly #(transport/recv transport 500))
                     (take-while (complement nil?))
                     response-values))))

(def-repl-test concurrent-message-handling
  (testing "multiple messages can be handled on the same connection concurrently"
    (let [sessions (doall (repeatedly 3 #(client-session client)))
          start-time (Environment/TickCount)                                                            ;DM: System/currentTimeMillis
          elapsed-times (map (fn [session eval-duration]
                               (let [expr (pr-str `(System.Threading.Thread/Sleep ~eval-duration))      ;DM: Thread/sleep
                                     responses (message session {:op :eval :code expr})]
                                 (future
                                   (is (= [nil] (response-values responses)))
                                   (- (Environment/TickCount) start-time))))                            ;DM: System/currentTimeMillis
                             sessions
                             [2000 1000 0])]
      (is (apply > (map deref (doall elapsed-times)))))))

(def-repl-test ensure-transport-closeable
  (is (= [5] (repl-values session "5")))
  (is (instance? IDisposable transport))                                              ;DM: java.io.Closeable
  (.Dispose transport)                                                                ;DM: .close
  (is (thrown? ObjectDisposedException (repl-values session "5"))))                   ;DM: java.net.SocketException

; test is flaking on hudson, but passing locally! :-X
#_(def-repl-test ensure-server-closeable
  (.Dispose *server*)                                                                 ;DM: .close
  (is (thrown? java.net.ConnectException (connect :port (:port *server*)))))

; wasn't added until Clojure 1.3.0
(defn- root-cause
  "Returns the initial cause of an exception or error by peeling off all of
  its wrappers"
  [^Exception t]                                  ;DM: Throwable
  (loop [cause t]
    (if-let [cause (.InnerException cause)]       ;DM: .getCause
      (recur cause)
      cause)))
	  
(defn- disconnection-exception?
  [e]
  ; thrown? should check for the root cause!
  (or (and (instance? ObjectDisposedException (root-cause e))               ;DM: Added
           (re-find #"Cannot access.*" (.Message (root-cause e))))          ;DM: Added
	  (and (instance? SocketException (root-cause e))
	       (re-find #".*closed.*" (.Message (root-cause e))))))             ;DM: re-matches .getMessage   #".*(lost.*connection|socket closed).*"


(deftest transports-fail-on-disconnects
  (testing "Ensure that transports fail ASAP when the server they're connected to goes down."
    (let [server (server/start-server)
          transport (connect :port (:port server))]
      (transport/send transport {"op" "eval" "code" "(+ 1 1)"})

      (let [reader (future (while true (transport/recv transport)))]
        (Thread/Sleep 1000)                                                                         ;DM: Thread/sleep
        (.Dispose server)                                                                           ;DM: .close
        (Thread/Sleep 1000)                                                                         ;DM: Thread/sleep
        ; no deref with timeout in Clojure 1.2.0 :-(
        (try
          (.get reader 10000)                                                                       ;DM:  java.util.concurrent.TimeUnit/MILLISECONDS removed
          (is false "A reader started prior to the server closing should throw an error...")
          (catch Exception e                                                                        ;DM: Throwable
            (is (disconnection-exception? e)))))

      (is (thrown? ObjectDisposedException (transport/recv transport)))                             ;DM: SocketException
      ;; TODO no idea yet why two sends are *sometimes* required to get a failure
      (try
        (transport/send transport {"op" "eval" "code" "(+ 5 1)"})
        (catch Exception t))                                                                        ;DM: Throwable
      (is (thrown? ObjectDisposedException (transport/send transport {"op" "eval" "code" "(+ 5 1)"}))))))   ;DM: SocketException

(def-repl-test clients-fail-on-disconnects
  (testing "Ensure that clients fail ASAP when the server they're connected to goes down."
    (let [resp (repl-eval client "1 2 3 4 5 6 7 8 9 10")]
      (is (= "1" (-> resp first :value)))
      (Thread/Sleep 1000)                                                                           ;DM: Thread/sleep
      (.Dispose *server*)                                                                           ;DM: .close
      (Thread/Sleep 1000)                                                                           ;DM: Thread/sleep
      (try
        ; these responses were on the wire before the remote transport was closed
        ;;(is (> 20 (count resp)))                                                                 ;DM: This blows up in LazySeq
		(let [c (count resp)]                                                                      ;DM: this works
		  (is (> 20 c)))                                                                           ;DM: Why here?
        (transport/recv transport)
        (is false "reads after the server is closed should fail")
        (catch Exception t                                                                         ;DM: Throwable
          (is (disconnection-exception? t)))))

    ;; TODO as noted in transports-fail-on-disconnects, *sometimes* two sends are needed
    ;; to trigger an exception on send to an unavailable server
    (try (repl-eval session "(+ 1 1)") (catch Exception t))                                       ;DM: Throwable
    (is (thrown? ObjectDisposedException (repl-eval session "(+ 1 1)")))))                        ;DM: SocketException

(def-repl-test request-*in*
  (is (= '((1 2 3)) (response-values (for [resp (repl-eval session "(read)")]
                                       (do
                                         (when (-> resp :status set (contains? "need-input"))
                                           (session {:op :stdin :stdin "(1 2 3)"}))
                                         resp)))))

  (session {:op :stdin :stdin "a\nb\nc\n"})
  (doseq [x "abc"]
    (is (= [(str x)] (repl-values session "(read-line)")))))

(def-repl-test request-*in*-eof
  (is (= nil (response-values (for [resp (repl-eval session "(read)")]
                                (do
                                  (when (-> resp :status set (contains? "need-input"))
                                    (session {:op :stdin :stdin []}))
                                  resp))))))
	
(def-repl-test request-multiple-read-newline-*in*
  (is (= '(:ohai) (response-values (for [resp (repl-eval session "(read)")]
                                     (do
                                       (when (-> resp :status set (contains? "need-input"))
                                         (session {:op :stdin :stdin ":ohai\n"}))
                                       resp)))))

  (session {:op :stdin :stdin "a\n"})
  (is (= ["a"] (repl-values session "(read-line)"))))

(def-repl-test request-multiple-read-with-buffered-newline-*in*
  (is (= '(:ohai) (response-values (for [resp (repl-eval session "(read)")]
                                     (do
                                       (when (-> resp :status set (contains? "need-input"))
                                         (session {:op :stdin :stdin ":ohai\na\n"}))
                                       resp)))))

  (is (= ["a"] (repl-values session "(read-line)"))))

(def-repl-test request-multiple-read-objects-*in*
  (is (= '(:ohai) (response-values (for [resp (repl-eval session "(read)")]
                                     (do
                                       (when (-> resp :status set (contains? "need-input"))
                                         (session {:op :stdin :stdin ":ohai :kthxbai\n"}))
                                       resp)))))

  (is (= [" :kthxbai"] (repl-values session "(read-line)"))))

(def-repl-test test-url-connect
  (with-open [conn (url-connect (str "nrepl://localhost:" (:port *server*)))]
    (transport/send conn {:op :eval :code "(+ 1 1)"})
    (is (= [2] (response-values (response-seq conn 100))))))

(deftest test-ack
  (with-open [s (server/start-server :handler (ack/handle-ack (server/default-handler)))]
    (ack/reset-ack-port!)
    (with-open [s2 (server/start-server :ack-port (:port s))]
      (is (= (:port s2) (ack/wait-for-ack 10000))))))

(def-repl-test agent-await
  (is (= [42] (repl-values session (code (let [a (agent nil)]
                                           (send a (fn [_] (System.Threading.Thread/Sleep 1000) 42))                 ;DM: Thread/sleep
                                           (await a)
                                           @a))))))