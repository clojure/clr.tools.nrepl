(ns cnrepl.socket
  "Compatibility layer for java.io vs java.nio sockets to allow an
  incremental transition to nio, since the JDK's filesystem sockets
  don't support the java.io socket interface, and we can't use the
  compatibility layer for bidirectional read and write:
  https://bugs.openjdk.java.net/browse/JDK-4509080."
  #_(:require
   [clojure.java.io :as io]
   [nrepl.misc :refer [log]]
   [nrepl.tls :as tls]
   [nrepl.socket.dynamic :refer [get-path]])
  #_(:import
   (java.io BufferedInputStream BufferedOutputStream File OutputStream)
   (java.net InetSocketAddress ProtocolFamily ServerSocket Socket SocketAddress
             StandardProtocolFamily URI)
   (java.nio ByteBuffer)
   (java.nio.file Path)
   (java.nio.channels Channels ClosedChannelException NetworkChannel
                      ServerSocketChannel SocketChannel)
   (javax.net.ssl SSLServerSocket)))


;;; I don't have the time, energy, or patience to deal with this at this time.  Anyone else? ;;;
