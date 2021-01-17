(ns cnrepl.misc-test
  (:require [clojure.test :refer [deftest is]]
            [cnrepl.misc :as misc]))

(deftest sanitize-meta-test
  (is (not-empty (:file (misc/sanitize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file "/foo/bar/baz.clj"})))))