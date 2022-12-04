(ns cnrepl.response-test
  (:require
   [clojure.test :refer :all]
   [cnrepl.core :as nrepl]
   [cnrepl.transport :as t])
  #_(:import
   (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(deftest response-seq
  (let [[local remote] (t/piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (nrepl/response-seq local 0)))

    ;; ensure timeouts don't capture later responses
    (nrepl/response-seq local 100)
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) (nrepl/response-seq local 0)))))

(deftest client
  (let [[local remote] (t/piped-transports)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= (range 10) ((nrepl/client local 100) 17)))
    (is (= 17 (t/recv remote)))))

(deftest client-heads
  (let [[local remote] (t/piped-transports)
        client1 (nrepl/client local Int32/MaxValue)        ;;; Long/MAX_VALUE, switched from Int64 to Int32 because timeout value in client is Int32
        all-seq (client1)]
    (doseq [x (range 10)] (t/send remote x))
    (is (= [0 1 2] (take 3 all-seq)))
    (is (= (range 3 7) (take 4 (client1 :a))))
    (is (= :a (t/recv remote)))
    (is (= (range 10) (take 10 all-seq)))))