(ns cnrepl.sanity-test
  (:require
   [clojure.test :refer :all]
   [cnrepl.test-helper :refer [platform-newlines]]          ;DM: Added
   [cnrepl.core :as nrepl]
   [cnrepl.middleware.interruptible-eval :as eval]
   [cnrepl.middleware.print :as print]
   [cnrepl.middleware.session :as session]
   [cnrepl.misc :as misc]
   [cnrepl.transport :as transport :refer [piped-transports]])
  #_(:import
   (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(println (format "Testing with Clojure v%s on %s" (clojure-version) (.ToString Environment/Version)))   ;DM: (System/getProperty "java.version")

(defn- internal-eval
  ([expr] (internal-eval nil expr))
  ([ns expr]
   (let [[local remote] (piped-transports)
         expr (if (string? expr)
                expr
                (binding [*print-meta* true]
                  (pr-str expr)))
         msg (cond-> {:code expr :transport remote :session (atom {})}
               ns (assoc :ns ns))]
     (eval/evaluate msg)
     (-> (nrepl/response-seq local 0)
         (nrepl/combine-responses)
         (select-keys [:ns :value :out :err])))))

(deftest eval-sanity
  (try
    (are [result expr] (= result (internal-eval expr))
      {:ns "user" :value [3]}
      '(+ 1 2)

      {:ns "user" :value [nil]}
      '*1

      {:ns "user" :value [nil]}
      '(do (def ^{:dynamic true} ++ +) nil)

      {:ns "user" :value [5]}
      '(binding [++ -] (++ 8 3))

      {:ns "user" :value [42]}
      '(set! *print-length* 42)

      {:ns "user" :value [nil]}
      '*print-length*)
    (finally (ns-unmap *ns* '++))))

(deftest specified-namespace
  (try
    (are [ns result expr] (= result (internal-eval ns expr))
      (ns-name *ns*)
      {:ns "user" :value [3]}
      '(+ 1 2)

      'user
      {:ns "user" :value '[("user" "++")]}
      '(do
         (def ^{:dynamic true} ++ +)
         (map #(-> #'++ meta % str) [:ns :name]))

      (ns-name *ns*)
      {:ns "user" :value [5]}
      '(binding [user/++ -]
         (user/++ 8 3)))
    (finally (ns-unmap 'user '++))))

(deftest multiple-expressions
  (are [result expr] (= result (internal-eval expr))
    {:ns "user" :value [4 65536.0]}
    "(+ 1 3) (Math/Pow 2 16)"                              ;DM: Math/pow

    {:ns "user" :value [4 20 1 0]}
    "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"

    {:ns "user" :value [nil]}
    '*1))

(deftest repl-out-writer
  (let [[local remote] (piped-transports)
        w (print/replying-PrintWriter :out {:transport remote} {})]
    (doto w
      .Flush                                          ;DM: .flush
      (.WriteLine "println")                          ;DM: .println
      (.Write "abcd")                                 ;DM: .write
      (.Write (.ToCharArray "ef") 0 2)                ;DM: .write .toCharArray
      (.Write "gh" 0 2)                               ;DM: .write
      (.Write (.ToCharArray "ij"))                    ;DM: .write .toCharArray
      (.Write (.ToCharArray "   klm") 5 1)            ;DM: (.write "   klm" 5 1)  - no such overload on TextWriter
      (.Write (char 32))                              ;DM: (.write 32)  -- this prints a char in JVM.  No such overload on TextWriter
      .Flush) 
    (with-open [out (identity w)]                     ;DM: java.io.PrintWriter.
      (binding [*out* out]
        (newline)
        (prn #{})
        (flush)))

    (is (= [(str "println" (Environment/NewLine))     ;;; (System/getProperty "line.separator")
            "abcdefghijm "
            "\n#{}\n"]
           (->> (nrepl/response-seq local 0)
                (map :out))))))