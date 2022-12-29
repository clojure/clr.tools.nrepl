(ns cnrepl.misc-test
  (:require [clojure.test :refer [deftest is]]
            [cnrepl.misc :as misc]
            [clojure.clr.io :as io])                                                   ;;; clojure.java.io
  )                                                                                    ;;; (:import [java.net URL])

(deftest sanitize-meta-test
  (is (not-empty (:file (misc/sanitize-meta {:file "clojure/core.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file "/foo/bar/baz.clj"}))))

  (is (= "/foo/bar/baz.clj"
         (:file (misc/sanitize-meta {:file (io/as-file "/foo/bar/baz.clj")}))))        ;;; io/file 

  (is (= "https://foo.bar/"                                                            ;;; "https://foo.bar"  -- I don't know ny System.Uri ctor adds the /
         (:file (misc/sanitize-meta {:file (System.Uri. "https://foo.bar")})))))       ;;; URL.