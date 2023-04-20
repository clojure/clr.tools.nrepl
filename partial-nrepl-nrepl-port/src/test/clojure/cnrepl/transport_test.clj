(ns cnrepl.transport-test
  (:require [cnrepl.transport :as sut]
            [clojure.test :refer [deftest testing is]])
  (:import [System.IO MemoryStream]))                                                 ;;; [java.io ByteArrayOutputStream]

(deftest bencode-safe-write-test
  (testing "safe-write-bencode only writes if the whole message is writable"
    (let [out (MemoryStream.)]                                                        ;;; ByteArrayOutputStream.
      (is (thrown? ArgumentException                                                  ;;; IllegalArgumentException
                   (#'sut/safe-write-bencode out {"obj" (Object.)})))
      (is (empty? (.ToArray out))))))                                                 ;;; .toByteArray

(deftest tty-read-conditional-test
  (testing "tty-read-msg is configured to read conditionals"
    (let [in (-> "(try nil (catch #?(:clj Throwable :cljr Exception) e nil))"
                 (#(MemoryStream. (.GetBytes System.Text.Encoding/UTF8 %)))                                                                     ;;; (java.io.StringReader.)
                 (clojure.lang.PushbackInputStream.))                                 ;;; (java.io.PushbackReader.)
          out (MemoryStream.)]                                                        ;;; (ByteArrayOutputStream.)
      (is (= ['(try nil (catch Exception e nil))]                                     ;;; Throwable
             (let [^cnrepl.transport.FnTransport fn-transport (sut/tty in out nil)]
               (.recv fn-transport)     ;; :op "clone"
               (-> (.recv fn-transport) ;; :op "eval"
                   :code)))))))	  