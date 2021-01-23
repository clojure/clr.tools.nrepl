(ns cnrepl.transport-test
  (:require [cnrepl.transport :as sut]
            [clojure.test :refer [deftest testing is]])
  (:import [System.IO MemoryStream]))                                           ;;; [java.io ByteArrayOutputStream]

(deftest bencode-safe-write-test
  (testing "safe-write-bencode only writes if the whole message is writable"
    (let [out (MemoryStream.)]                                                  ;;; ByteArrayOutputStream.
      (is (thrown? ArgumentException                                            ;;; IllegalArgumentException
                   (#'sut/safe-write-bencode out {"obj" (Object.)})))
      (is (empty? (.ToArray out))))))                                           ;;; .toByteArray