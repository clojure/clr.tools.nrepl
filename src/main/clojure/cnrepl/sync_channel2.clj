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
  cnrepl.sync-channel2
  (:refer-clojure :exclude (take))
  (:import [System.Threading Monitor WaitHandle EventWaitHandle AutoResetEvent]))
  
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
  "A synchronous channel (single-threaded on producer and consumer)"
  (put   [this value]   "Put a value to this channel (Producer)")
  (take  [this]         "Get a value from this channel (Consumer)")
  (poll  [this] [this timeout]    "Get a value from this channel if one is available (within the designated timeout period)"))
  
	   
 
 ;; SimpleSyncChannel assumes there is a single producer thread and a single consumer thread.
 
 (deftype SimpleSyncChannel [^:volatile-mutable value 
                             p-lock
							 c-lock
							 ^AutoResetEvent producer-wait-event
							 ^AutoResetEvent consumer-wait-event]
  SyncChannel
  (put [this v] 
    (try 
	  (when-not (Monitor/TryEnter p-lock)
	    (throw (Exception. "Producer not single-threaded")))
	  (set! value v)
	  (WaitHandle/SignalAndWait consumer-wait-event producer-wait-event)
	  (finally 
	    (Monitor/Exit p-lock))))
	  
	
  (take [this]
    (poll this -1))	

  (poll [this] 
    (poll this 0))
	
  (poll [this timeout]
    (try 
	  (when-not (Monitor/TryEnter c-lock)
	    (throw (Exception. "Consumer not single-threaded")))
		
	  (when (.WaitOne consumer-wait-event timeout)
	    ;; We were signaled, so a value is waiting
	    (let [v value]
	      (set! value nil)
		  (.Set producer-wait-event)
		 v))
	  (finally 
	    (Monitor/Exit c-lock)))))
	    
		 
			
(defn make-simple-sync-channel []
  (SimpleSyncChannel. nil (Object.) (Object.) (AutoResetEvent. false) (AutoResetEvent. false)))	
  
(comment

  (require '[cnrepl.debug :as debug])
  (def q (make-simple-sync-channel))
  (future (dotimes [i 5] (debug/prn-thread "put start " i) (put q i) (debug/prn-thread "put finish " i)))
  (future (dotimes [i 5] (debug/prn-thread "take start " i) (debug/prn-thread "take finish " (take q))))
  
  )
  