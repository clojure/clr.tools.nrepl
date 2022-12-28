(ns cnrepl.basic-test3
  (:require [cnrepl.debug :as debug]
            [clojure.clr.io :as io]
			[cnrepl.transport :as t])
  (:import [System.Net.Sockets TcpClient TcpListener]
           [System.Net IPEndPoint IPAddress]))


(defn echo-handler [transport]
    (loop []

	  (let [msg (t/recv transport)]

	    (t/send transport msg))
	  (recur)))
	  
(defn accept-connections [listener]
  (let [client (.AcceptTcpClient listener)
        transport (t/bencode (.Client client))]
	 (future (echo-handler transport))
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
  		
  
(defn echo-client [transport]
   	  
	(t/send transport "Clojure!")
	(let [msg (t/recv transport)]
	   msg))
		
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
  (def cben (t/bencode (.Client c)))
  (echo-client cben)   
  
  )