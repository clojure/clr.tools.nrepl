(ns cnrepl.middleware.completion-test
  {:author "Bozhidar Batsov"}
  (:require
   [clojure.test :refer :all]
   [cnrepl.core :as cnrepl]
   [cnrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir clean-response]])
  (:import
   ))                                                                                                  ;;; not used?   (java.io File)

(use-fixtures :each repl-server-fixture)

(defn dummy-completion [prefix _ns _options]
  [{:candidate prefix}])

(def-repl-test completions-op
  (let [result (-> (cnrepl/message session {:op "completions" :prefix "map" :ns "clojure.core"})
                   cnrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done} (:status result)))
    (is (not-empty (:completions result)))))

(def-repl-test completions-op-error
  (let [result (-> (cnrepl/message session {:op "completions"})
                   cnrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done :completion-error :namespace-not-found} (:status result)))))

(def-repl-test completions-op-custom-fn
  (let [result (-> (cnrepl/message session {:op "completions" :prefix "map" :ns "clojure.core" :complete-fn "cnrepl.middleware.completion-test/dummy-completion"})
                   cnrepl/combine-responses
                   clean-response
                   (select-keys [:completions :status]))]
    (is (= #{:done} (:status result)))
    (is (= [{:candidate "map"}] (:completions result)))))