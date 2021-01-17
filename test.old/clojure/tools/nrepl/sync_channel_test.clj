;-
;   Copyright (c) David Miller 2013. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns clojure.tools.nrepl.sync-channel-test
  (:refer-clojure :exclude [take])
  (:use
    [clojure.test :only [deftest is are]]
    [clojure.tools.nrepl.sync-channel :as sc]))
	
	
(def ^:private prn-agent (agent nil))
(defn- sprn [& strings] (send-off prn-agent (fn [_] (apply prn strings))))  

(deftest basic-prod-cons
 (let [sc (sc/make-simple-sync-channel)
        p (agent nil)
		c (agent nil)
		n 5]
	(send c (fn [_] (doall (repeatedly n  (fn [] (sc/take sc))))))
	(send p (fn [_] (dotimes [i n] (sc/put sc i))))
	(await c)
	(is (= (range n) @c))))
	
  
(deftest poll-with-timeout-no-producer
   (let [sc (sc/make-simple-sync-channel)
         c (agent 12)]
	  (send c (fn [_] (sc/poll sc 100)))
	  (await c)
	  (is (= nil @c))))
	  
(deftest poll-with-no-timeout
   (let [sc (sc/make-simple-sync-channel)
         c (agent 12)]
	  (send c (fn [_] (sc/poll sc)))
	  (await c)
	  (is (= nil @c))))
	  
(deftest poll-with-waiting-value
  (let [sc (sc/make-simple-sync-channel)
        p (agent nil)
		c (agent nil)
		v 100]
    (send p (fn [_] (sc/put sc v)))
	(System.Threading.Thread/Sleep 10)
	(send c (fn [_] (sc/poll sc)))
	(await c)
	(is (= v @c))))
	
(deftest poll-with-timeout-with-producer
  (let [sc (sc/make-simple-sync-channel)
        p (agent nil)
		c (agent nil)
		v 100]
	(send c (fn [_] (sc/poll sc  10000000)))	
    (send p (fn [_] (sc/put sc v)))
	(await c)
	(is (= v @c))))	

(deftest single-threaded-on-consumer
  (let [sc (sc/make-simple-sync-channel)
        p  (agent nil)
		c1 (agent nil)
		c2 (agent nil)
		v  10]
	(send c1 (fn [_] (sc/take sc)))
    (System.Threading.Thread/Sleep 10)
    (send c2 (fn [_] (sc/take sc)))
    (is (= (class (agent-error c2)) Exception))
	(restart-agent c2 nil)
	(send p (fn [_] (sc/put sc v)))
	(await c1)
	(is (= v @c1))))
	
(deftest single-threaded-on-producer
  (let [sc (sc/make-simple-sync-channel)
        p1 (agent nil)
		p2 (agent nil)
		c  (agent nil)
		v  10]
	(send p1 (fn [_] (sc/put sc v)))
    (System.Threading.Thread/Sleep 10)
    (send p2 (fn [_] (sc/put sc (inc v))))
    (is (= (class (agent-error p2)) Exception))
	(restart-agent p2 nil)
	(send c (fn [_] (sc/take sc)))
	(await c)
	(is (= v @c))))
	
(deftest no-nil-value
  (let [sc (sc/make-simple-sync-channel)]
    (is (thrown? NullReferenceException (sc/put sc nil)))))
        
	
	