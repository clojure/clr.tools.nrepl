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
  cnrepl.sync-channel
  (:refer-clojure :exclude (take))
  (:require [cnrepl.debug :as debug])
  (:import [System.Threading Monitor WaitHandle AutoResetEvent]))
  
 ;; Reason for existence
 ;;
 ;; The original (ClojureJVM) FnTransport code uses a java.util.concurrent.SynchronousQueue.
 ;;  The description in the Java documentation reads:
 ;;
 ;;     A blocking queue in which each insert operation must wait for a corresponding remove operation by another thread, 
 ;;     and vice versa. A synchronous queue does not have any internal capacity, not even a capacity of one. You cannot 
 ;;     peek at a synchronous queue because an element is only present when you try to remove it; you cannot insert an 
 ;;     element (using any method) unless another thread is trying to remove it; you cannot iterate as there is nothing 
 ;;     to iterate. The head of the queue is the element that the first queued inserting thread is trying to add to the 
 ;;     queue; if there is no such queued thread then no element is available for removal and poll() will return null. 
 ;;     For purposes of other Collection methods (for example contains), a SynchronousQueue acts as an empty collection. 
 ;;     This queue does not permit null elements. Synchronous queues are similar to rendezvous channels used in CSP and Ada. 
 ;;     They are well suited for handoff designs, in which an object running in one thread must sync up with an object 
 ;;     running in another thread in order to hand it some information, event, or task.
 ;;
 ;;  In the use here, there is a single producer and a single consuer.
 ;;
 ;;  The CLR does not supply such a construct.  The closest equivalent would be a System.Collections.Concurrent.BlockingCollection<T> 
 ;;  with zero capacity, but that class only allows capacity greater than zero.  With capacity one, it would not require a producer 
 ;;  or a consumer to wait for its matching consumer/producer to come along.
 ;;  
 ;;  Another possibility might be the channels in System.Threading.Channels.  However, it does not seem to be able to force 
 ;;  a producer to wait for a consumer.  And at any rate, it is not available for .NET Framework.
 ;;
 ;;
 ;;  Because our usage can be restricted to one allowing only one consumer waiting and one producer waiting at a time, we do not
 ;;  need to deal with queuing producers or consumers and hence with fairness issues and the like.
 ;;
 ;;  Because this sort of construct can be tricky to get right, I'll spend a little time describing the implementation
 ;;  in the hopes of convincing you -- well, me, really -- that it's correct.
 ;;
 ;;  Single-threadedness for producers and consumers is enforced by lock management.
 ;;  A producer calling put trys to get the p-lock.  
 ;;    If it fails to get the lock, there must be a producer already working, so an error is thrown.
 ;;    If it succeeds, it has the lock until it is released by a consumer.
 ;;
 ;;  Similarly for a consumer.
 ;;
 ;;  Coordination between consumer and producer is implemented by a pair of AutoResetEvents.
 ;;  A producer enters, sets the value field, signals the consumer-wait-event and waits to be signaled to continue.
 ;;  A consumer enters, waits for its signal, grabs the value, sets the value field to nil, and signals the waiting producer.
 ;;  Access to the value field comes before the wait for the producer and after the wait for the consumer.
 ;;  Thus, we do not need to protect access to the value field.
 
 
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