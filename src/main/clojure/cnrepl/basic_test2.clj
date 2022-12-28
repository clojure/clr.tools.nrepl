(ns cnrepl.basic-test2
  (:require [cnrepl.debug :as debug]
            [clojure.clr.io :as io])
  (:import [System.Net.Sockets TcpClient TcpListener]
           [System.Net IPEndPoint IPAddress]))


(defn echo-handler [tcp-client]
  (let [socket (.Client tcp-client)
        in0 (io/input-stream socket)  
        in (clojure.lang.PushbackInputStream. in0)
		out (io/output-stream socket)]
    (loop []
	  (let [c (.ReadByte in)]
	    (.WriteByte out (byte c)))
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
  (let [socket (.Client client)
        in0 (io/input-stream socket)  
        in (clojure.lang.PushbackInputStream. in0)
		out (io/output-stream socket)]	  
	(.Write out x 0 (count x))
	(let [buffer (read-counted in (count x))]
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