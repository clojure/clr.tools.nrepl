(ns cnrepl.core-test
  (:require
   [clojure.clr.io :as io]                                   ;;; clojure.java.io
   [clojure.main]
   [clojure.set :as set]
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [cnrepl.core :as cnrepl :refer [client
                                 client-session
                                 code
                                 combine-responses
                                 connect
                                 message
                                 new-session
                                 read-response-value
                                 response-seq
                                 response-values
                                 url-connect]]
   [cnrepl.ack :as ack] [cnrepl.debug :as debug]
   [cnrepl.middleware.caught :as middleware.caught]
   [cnrepl.middleware.print :as middleware.print]
   [cnrepl.middleware.session :as session]
   [cnrepl.middleware.sideloader :as sideloader]
   [cnrepl.misc :refer [uuid]]
   [cnrepl.server :as server]
   [cnrepl.transport :as transport])
  (:import
    (System.IO FileInfo)                ;;; (java.io File Writer)
                                        ;;; java.net.SocketException
   (cnrepl.server Server)))
   
(defmacro when-require [n & body]
  (let [nn (eval n)]
    (try (require nn)
         (catch Exception e nil))        ;;; Throwable
    (when (find-ns nn)
      `(do ~@body))))

(def transport-fn->protocol
  "Add your transport-fn var here so it can be tested"
  {#'transport/bencode "nrepl"
   #_#'transport/edn #_"nrepl+edn"})  
   
;; There is a profile that adds the fastlane dependency and test
;; its transports.
(when-require 'fastlane.core
              (def transport-fn->protocol
                (merge transport-fn->protocol
                       {(find-var 'fastlane.core/transit+msgpack) "transit+msgpack"
                        (find-var 'fastlane.core/transit+json) "transit+json"
                        (find-var 'fastlane.core/transit+json-verbose) "transit+json-verbose"})))
						
(def ^FileInfo project-base-dir (FileInfo. "."))                    ;;; ^File(System/getProperty "nrepl.basedir" ".")

(def ^:dynamic ^cnrepl.server.Server  *server* nil)
(def ^{:dynamic true} *transport-fn* nil)

(defn start-server-for-transport-fn
  [transport-fn f]
  (with-open [^Server server (server/start-server :transport-fn transport-fn)]
    (binding [*server* server
              *transport-fn* transport-fn]
      (testing (str (-> transport-fn meta :name) " transport")
        (f))
      (set! *print-length* nil)
      (set! *print-level* nil))))
	 
(def transport-fns
  (keys transport-fn->protocol))
  
(defn repl-server-fixture
  "This iterates through each transport being tested, starts a server,
   runs the test against that server, then cleans up all sessions."
  [f]
  (doseq [transport-fn transport-fns]
    (debug/prn-thread "Starting server-for-transport: " transport-fn ", " transport-fns)
    (start-server-for-transport-fn transport-fn f)
    (session/close-all-sessions!)))

(use-fixtures :each repl-server-fixture)

(defmacro def-repl-test
  [name & body]
  `(deftest ~name
     (with-open [^cnrepl.transport.FnTransport
                 transport# (connect :port (:port *server*)
                                     :transport-fn *transport-fn*)]
       (let [~'transport transport#
	         ~'_ (debug/prn-thread "Creating client")
             ~'client (client transport# Int32/MaxValue)                          ;;; Long/MAX_VALUE
 	         ~'_ (debug/prn-thread "Creating session")
			 ~'session (client-session ~'client)
	         ~'_ (debug/prn-thread "Creating timeout client")
             ~'timeout-client (client transport# 1000)
	         ~'_ (debug/prn-thread "Creating time-client session")
             ~'timeout-session (client-session ~'timeout-client)
	         ~'_ (debug/prn-thread "Creating funcs")
             ~'repl-eval #(message % {:op "eval" :code %2})
             ~'repl-values (comp response-values ~'repl-eval)]
         ~@body))))
		 
(defn- strict-transport? []
  ;; TODO: add transit here.
  (or (= *transport-fn* #'transport/edn)
      (when-require 'fastlane.core
                    (or (= *transport-fn* #'fastlane.core/transit+msgpack)
                        (= *transport-fn* #'fastlane.core/transit+json)
                        (= *transport-fn* #'fastlane.core/transit+json-verbose)))))
						
(defn- check-response-format
  "checks response against spec, if available it to do a spec check later"
  [resp]
  (when-require 'cnrepl.spec
                (when-not (#'clojure.spec.alpha/valid? :cnrepl.spec/message resp)
                  (throw (Exception. ^String (#'clojure.spec.alpha/explain-str :cnrepl.spec/message resp)))))
  resp)
  
(defn clean-response
  "Cleans a response to help testing.
  
  This manually coerces bencode responses to (close) to what the raw EDN
  response is, so we can standardise testing around the richer format. It
  retains strictness on EDN transports.
  
  - de-identifies the response
  - ensures the status to a set of keywords
  - turn the content of truncated-keys to keywords"
  [resp]
  (let [de-identify
        (fn [resp]
          (dissoc resp :id :session))
        normalize-status
        (fn [resp]
          (if-let [status (:status resp)]
            (assoc resp :status (set (map keyword status)))
            resp))
        ;; This is a good example of a middleware details that's showing through
        keywordize-truncated-keys
        (fn [resp]
          (if (contains? resp ::middleware.print/truncated-keys)
            (update resp ::middleware.print/truncated-keys #(mapv keyword %))
            resp))]
    (cond-> resp
      true                      de-identify
      (not (strict-transport?)) normalize-status
      (not (strict-transport?)) keywordize-truncated-keys
      (strict-transport?)       check-response-format)))

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

  (is (= (->> "#\"regex\"" read-string eval list (map str))
         (->> "#\"regex\"" (repl-values client) (map str)))))
		 
(def-repl-test simple-expressions
  (are [expr] (= [(eval expr)] (repl-values client (pr-str expr)))
    '(range 40)
    '(apply + (range 100))))		 