;-
;   Copyright (c) David Miller. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns #^{:author "David Miller"
       :doc "A simple synchronous channel"}
  clojure.tools.nrepl.sync-channel)
  
 ;; Reason for existence
 ;;
 ;; The original (ClojureJVM) FnTransport code uses a java.util.concurrent.SynchronousQueue.
 ;; However, in that use there is a single producer and a single consumer.
 ;; The CLR does not supply such a construct.  
 ;; (The closest equivalent would be a System.Collections.Concurrent.BlockingCollection<T> with zero capacity, 
 ;; but that class only allows capacity greater than zero.)
 ;;
 ;; Rather then do a full-blown implementation of a synchronous queue, 
 ;; something along the lines of Doug Lea's C# implementation
 ;; http://code.google.com/p/netconcurrent/source/browse/trunk/src/Spring/Spring.Threading/Threading/Collections/SynchronousQueue.cs
 ;; we can go with a much simpler construct - a synchronous channel between producer and consumer
;;  that blocks either one if the other is not waiting.
 

 
 (defprotocol SyncChannel
  "Defines the interface for a simple synchronous channel"
  (stuff [this value] "Send a value to this channel. (Producer)")
  (grab  [this]       "Get a value from this channel. (Consumer)"))
  
 ;; SimpleSyncChannel assumes there is a single producer thread and a single consumer thread.
 (deftype SimpleSyncChannel [#^:volatile-mutable value 
                             #^:volatile-mutable waiting?
							 lock]
  SyncChannel
  (stuff [this v] 
    (locking lock
	  (set! value v)
      (when-not waiting?
        (set! waiting? true)
        (System.Threading.Monitor/Wait lock))
   	  (set! waiting? false)
      (System.Threading.Monitor/Pulse lock)))
	     

   (grab [this]
    (locking lock
      (when-not waiting?
        (set! waiting? true)
        (System.Threading.Monitor/Wait lock))
	  (let [curval value]
	    (set! value nil)
   	    (set! waiting? false)
        (System.Threading.Monitor/Pulse lock)
		curval))))
		
   
;(def prn-agent (agent nil))
;(defn sprn [& strings] (send-off prn-agent (fn [v] (apply prn strings))))  
;(defn f [n]
;   (let [sc (SimpleSyncChannel. nil false (Object.))
;	      p (agent nil)
;		  c (agent nil)]
;	  (send c (fn [v] (dotimes [i n] (sprn (str "Consumer " i)) (sprn (str "====> "(grab sc))))))
;	  (send p (fn [v] (dotimes [i n] (sprn (str "Producer " i)) (stuff sc i))))
;	  [p c sc]))
	  
	  
   