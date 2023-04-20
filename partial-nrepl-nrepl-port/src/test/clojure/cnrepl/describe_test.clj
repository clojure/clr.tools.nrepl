(ns cnrepl.describe-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer [is testing use-fixtures]]
   [cnrepl.core :as cnrepl]
   [cnrepl.core-test :refer [def-repl-test repl-server-fixture]]
   [cnrepl.middleware :as middleware]
   [cnrepl.server :as server]
   [cnrepl.version :as version]))

(use-fixtures :once repl-server-fixture)

(def-repl-test simple-describe
  (let [{{:keys [nrepl clojure java]} :versions
         ops :ops} (cnrepl/combine-responses
                    (cnrepl/message timeout-client {:op "describe"}))]
    (testing "versions"
      (when-not (every? #(contains? java %) [:major :minor :incremental ])                      ;;; removed :update
        (println "Got less information out of `java.version` than we'd like:"
                 (.ToString Environment/Version) "=>" java))                                    ;;; (System/getProperty "java.version")
      (is (= (#'middleware/safe-version version/version) nrepl))
      (is (= (#'middleware/safe-version *clojure-version*) (dissoc clojure :version-string)))
      (is (= (clojure-version) (:version-string clojure)))
      (is (= (.ToString Environment/Version) (:version-string java))))                          ;;; (System/getProperty "java.version")

    (is (= server/built-in-ops (set (map name (keys ops)))))
    (is (every? empty? (map val ops)))))

(def-repl-test verbose-describe
  (let [{:keys [ops aux]} (cnrepl/combine-responses
                           (cnrepl/message timeout-client
                                          {:op "describe" :verbose? "true"}))]							  
    (is (= server/built-in-ops (set (map name (keys ops)))))
    (is (every? seq (map (comp :doc val) ops)))
    (is (= {:current-ns "user"} aux))))