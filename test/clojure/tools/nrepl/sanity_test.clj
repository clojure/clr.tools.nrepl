

;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.nrepl.sanity-test
  (:use clojure.test
        [clojure.test-helper :only [platform-newlines]]                                 ;DM: Added
        [clojure.tools.nrepl.transport :only (piped-transports)])
  (:require (clojure.tools.nrepl.middleware [interruptible-eval :as eval]
                                            [session :as session])
            [clojure.tools.nrepl :as repl]
            [clojure.set :as set])
   )                                                     ;DM: (:import (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit))

(println (format "Testing with Clojure v%s on %s" (clojure-version) (.ToString Environment/Version)))   ;DM: (System/getProperty "java.version")

(defn- internal-eval
  ([expr] (internal-eval nil expr))
  ([ns expr]
    (let [[local remote] (piped-transports)
          out (System.IO.StringWriter.)                            ;DM: java.io.StringWriter.
          err (System.IO.StringWriter.)                            ;DM: java.io.StringWriter.
          expr (if (string? expr)
                 expr
                 (binding [*print-meta* true]
                   (pr-str expr)))
          msg (merge {:code expr :transport remote}
                     (when ns {:ns ns}))
          resp-fn (if ns
                    (juxt :ns :value)
                    :value)]
      (eval/evaluate {#'*out* (identity out)                       ;DM: java.io.PrintWriter.
                      #'*err* (identity err)}                      ;DM: java.io.StringWriter.
                     msg)
      (->> (repl/response-seq local 0)
        (map resp-fn)
        (cons (str out))
        (#(if (seq (str err))
            (cons (str err) %)
            %))))))

(deftest eval-sanity
  (try
    (are [result expr] (= result (internal-eval expr))
         ["" 3] '(+ 1 2)
         
         ["" nil] '*1
         ["" nil] '(do (def ^{:dynamic true} ++ +) nil)
         ["" 5] '(binding [++ -] (++ 8 3))
         
         ["" 42] '(set! *print-length* 42)
         ["" nil] '*print-length*)
    (finally (ns-unmap *ns* '++))))

(deftest specified-namespace
  (try
    (are [ns result expr] (= result (internal-eval ns expr))
         (ns-name *ns*) ["" [(str (ns-name *ns*)) 3]]
         '(+ 1 2)
         
         'user ["" ["user" '("user" "++")]]
         '(do
            (def ^{:dynamic true} ++ +)
            (map #(-> #'++ meta % str) [:ns :name]))
         
         (ns-name *ns*) ["" [(str (ns-name *ns*)) 5]]
         '(binding [user/++ -]
            (user/++ 8 3)))
    (finally (ns-unmap 'user '++))))

(deftest multiple-expressions
  (are [result expr] (= result (internal-eval expr))
       ["" 4 65536.0] "(+ 1 3) (Math/Pow 2 16)"                                ;DM: Math/pow
       ["" 4 20 1 0] "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"
       ["" nil] '*1))

(deftest stdout-stderr
  (are [result expr] (= result (internal-eval expr))
       [(str "5 6 7 \n 8 9 10" (platform-newlines "\n")) nil] '(println 5 6 7 \newline 8 9 10)      ;DM: Added platform-newlines  "5 6 7 \n 8 9 10\n"
       [(platform-newlines "user/foo\n") "" nil] '(binding [*out* *err*]                   ;DM: Added platform-newlines
                                (prn 'user/foo))
       ["problem" "" :value] '(do (.Write *err* "problem")                                 ;DM: .write
                                  :value))
  (is (re-seq #"Exception: No such var: user/foo" (-> '(prn user/foo)
                                                    internal-eval
                                                    first))))

(deftest repl-out-writer
  (let [[local remote] (piped-transports)
        w (#'session/session-out :out :dummy-session-id remote)]
    (doto w
      .Flush                                          ;DM: .flush
      (.WriteLine "println")                          ;DM: .println
      (.Write "abcd")                                 ;DM: .write
      (.Write (.ToCharArray "ef") 0 2)                ;DM: .write .toCharArray
      (.Write "gh" 0 2)                               ;DM: .write
      (.Write (.ToCharArray "ij"))                    ;DM: .write .toCharArray
      (.Write (.ToCharArray "   klm") 5 1)            ;DM: (.write "   klm" 5 1)  - no such overload on TextWriter
      (.Write (char 32))                              ;DM: (.write 32)  -- this prints a char in JVM.  No such overload on TextWriter
      .Flush)                                         ;DM: .flush
    (with-open [out (identity w)]                     ;DM: java.io.PrintWriter.
      (binding [*out* out]
        (newline)
        (prn #{})
        (flush)))
    
;DM:     (is (= ["println\n" "abcdefghijm " "\n#{}\n"]
;DM:           (->> (repl/response-seq local 0)
;DM:             (map :out))))
;DM: ; I'm not sure why the (newline) call above does not generate a separate response in the JVM version.
;DM: ; When I see a newline, I spit out what we've got so far.
		
    (is (= [(platform-newlines "println\n") 
	        (platform-newlines "abcdefghijm ") 
			(platform-newlines "\n")
			(platform-newlines "#{}\n")]
          (->> (repl/response-seq local 0)
            (map :out))))))

; TODO
(comment
  (def-repl-test auto-print-stack-trace
  (is (= true (repl-value "(set! clojure.tools.nrepl/*print-detail-on-error* true)")))
  (is (.contains (-> (repl "(throw (Exception. \"foo\" (Exception. \"nested exception\")))")
                   full-response
                   :err)
        "nested exception")))

(def-repl-test install-custom-error-detail-fn
  (->> (repl/send-with connection
         (set! clojure.tools.nrepl/*print-error-detail*
           (fn [ex] (print "custom printing!")))
         (set! clojure.tools.nrepl/*print-detail-on-error* true))
    repl/response-seq
    doall)
  (is (= "custom printing!"
        (->> (repl/send-with connection
               (throw (Exception. "foo")))
          full-response
          :err))))
)