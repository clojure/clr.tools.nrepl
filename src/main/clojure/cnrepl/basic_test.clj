(ns cnrepl.basic-test
  (:require [cnrepl.debug :as debug])
  (:import [System.Net.Sockets TcpClient TcpListener]
           [System.Net IPEndPoint IPAddress]))


(defn echo-handler [tcp-client]
  (let [net-stream (.GetStream tcp-client)]
    (loop []
	  (debug/prn-thread "echo-handler -- reading byte")
	  (let [c (.ReadByte net-stream)]
	    (debug/prn-thread "echo-handler -- read " c)
		(debug/prn-thread "echo-handler -- writing " c)
	    (.WriteByte net-stream (byte c)))
	  (recur))))
	  
(defn accept-connections [listener]
  (let [client (.AcceptTcpClient listener)]
	 (future (echo-handler client))
	 (future (accept-connections listener))))
	 
(def x (byte-array [(byte 0x43) 
				   (byte 0x6c)
				   (byte 0x6f)
				   (byte 0x6a)
				   (byte 0x75)
				   (byte 0x72)
				   (byte 0x65)
				   (byte 0x21)]))
				   
(defn create-client [host port]
  (TcpClient. host port))
  
(defn read-counted [^System.IO.Stream stream ^long count]
  (let [buffer (byte-array count)]
    (loop [offset 0]
      (when (< offset count)
         (let [num-read (.Read stream buffer offset (- count offset))]
            (recur (+ offset num-read)))))
    buffer))			
  
(defn echo-client [client]
  (debug/prn-thread "echo-client -- sending bytes")
  (let [net-stream (.GetStream client)]
    (debug/prn-thread "echo-client: writing " x)		  
	(.Write net-stream x 0 (count x))
	(debug/prn-thread "echo-client: reading #bytes = " (count x)) 
	(let [buffer (read-counted net-stream (count x))]
	  (debug/prn-thread "echo-client: done reading, got: " buffer)
	   buffer)))
		
(def host "127.0.0.1")

(defn start-server []
  (let [ipe (IPEndPoint. (IPAddress/Parse host) (int 0))
        listener (TcpListener. ipe)]
	(.Start listener)
	(future (accept-connections listener))
	(.Port ^IPEndPoint (.LocalEndPoint  (.Server listener)))))


(comment

  (def port (start-server))	  
  (def c (create-client host port))
  (echo-client c)   
  
  )