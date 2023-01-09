(ns cnrepl.middleware.lookup-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [cnrepl.core :as cnrepl]
   [cnrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]])
  (:import
   ))                                                                                                ;;; (java.io File)  - not used?

(use-fixtures :each repl-server-fixture)

(defn dummy-lookup [ns sym]
  {:foo 1
   :bar 2})

(defprotocol MyProtocol
  (protocol-method [_]))

(defn fn-with-coll-in-arglist
  [{{bar :bar} :baz}]
  bar)

(def-repl-test lookup-op
  (doseq [op [{:op "lookup" :sym "map" :ns "clojure.core"}
              {:op "lookup" :sym "let" :ns "clojure.core"}
              {:op "lookup" :sym "*assert*" :ns "clojure.core"}
              {:op "lookup" :sym "map" :ns "cnrepl.core"}
              {:op "lookup" :sym "future" :ns "cnrepl.core"}
              {:op "lookup" :sym "protocol-method" :ns "cnrepl.middleware.lookup-test"}
              {:op "lookup" :sym "fn-with-coll-in-arglist" :ns "cnrepl.middleware.lookup-test"}]]
    (let [result (-> (cnrepl/message session op)
                     cnrepl/combine-responses
                     clean-response)]
      (is (= #{:done} (:status result)))
      (is (not-empty (:info result))))))

(def-repl-test lookup-op-error
  (let [result (-> (cnrepl/message session {:op "lookup"})
                   cnrepl/combine-responses
                   clean-response)]
    (is (= #{:done :lookup-error :namespace-not-found} (:status result)))))

(def-repl-test lookup-op-custom-fn
  (let [result (-> (cnrepl/message session {:op "lookup" :sym "map" :ns "clojure.core" :lookup-fn "cnrepl.middleware.lookup-test/dummy-lookup"})
                   cnrepl/combine-responses
                   clean-response)]
    (is (= #{:done} (:status result)))
    (is (= {:foo 1 :bar 2} (:info result)))))