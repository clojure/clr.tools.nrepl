(ns cnrepl.middleware.load-file-test
  {:author "Chas Emerick"}
  (:require
   [clojure.test :refer :all]
   [cnrepl.core :as cnrepl]
   [cnrepl.core-test :refer [def-repl-test repl-server-fixture project-base-dir]])
  (:import
   (System.IO FileInfo Path)))                                                                                ;;; (java.io File)

(use-fixtures :each repl-server-fixture)

(def-repl-test load-code-with-debug-info
  (dorun (cnrepl/message timeout-session
                        {:op "load-file" :file "\n\n\n(defn function [])"}))
  (is (contains?
       ;; different versions of Clojure use different default :file metadata
       #{[{:file "NO_SOURCE_PATH" :line 4}]
         [{:file "NO_SOURCE_FILE" :line 4}]}
       (repl-values timeout-session
                    (cnrepl/code
                     (-> #'function
                         meta
                         (select-keys [:file :line]))))))
  (dorun (cnrepl/message timeout-session {:op "load-file"
                                         :file "\n\n\n\n\n\n\n\n\n(defn afunction [])"
                                         :file-path "path/from/source/root.clj"
                                         :file-name "root.clj"}))
  (is (= [{:file "path/from/source/root.clj" :line 10}]
         (repl-values timeout-session
                      (cnrepl/code
                       (-> #'afunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-debug-info
  (dorun
   (cnrepl/message timeout-session
                  {:op "load-file"
                   :file (slurp (FileInfo. (Path/Combine (.FullName project-base-dir) "load-file-test/nrepl/load_file_sample.clj")))             ;;; File.  added Path.Combine + .FullName
                   :file-path "nrepl/load_file_sample.clj"
                   :file-name "load_file_sample.clj"}))
  (is (= [{:file "nrepl/load_file_sample.clj" :line 5}]
         (repl-values timeout-session
                      (cnrepl/code
                       (-> #'nrepl.load-file-sample/dfunction
                           meta
                           (select-keys [:file :line])))))))

(def-repl-test load-file-with-print-vars
  (set! *print-length* 3)
  (set! *print-level* 3)
  (dorun
   (cnrepl/message session {:op "load-file"
                           :file "(def a (+ 1 (+ 2 (+ 3 (+ 4 (+ 5 6))))))
                                   (def b 2) (def c 3) (def ^{:internal true} d 4)"
                           :file-path "path/from/source/root.clj"
                           :file-name "root.clj"}))
  (is (= [4]
         (repl-values session (cnrepl/code d)))))

(def-repl-test load-file-response-no-ns
  (is (not (contains? (cnrepl/combine-responses
                       (cnrepl/message session
                                      {:op "load-file"
                                       :file "(ns foo) (def x 5)"
                                       :file-path "/path/to/source.clj"
                                       :file-name "source.clj"}))
                      :ns))))