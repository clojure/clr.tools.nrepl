(ns cnrepl.transport
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [send])
  (:require
   [clojure.clr.io :as io]                                                           ;;; clojure.java.io
   [clojure.walk :as walk]
   [cnrepl.bencode :as bencode]
   [clojure.edn :as edn]
   [cnrepl.misc :refer [uuid]]
   [cnrepl.sync-channel :as sc]                                                      ;;; DM: Added this 
   cnrepl.version)
  (:import
   clojure.lang.RT
   [System.IO Stream EndOfStreamException MemoryStream]                                           ;;; [java.io ByteArrayOutputStream
   [clojure.lang PushbackInputStream PushbackTextReader]                             ;;; EOFException PushbackInputStream PushbackReader OutputStream]
   (System.Net.Sockets Socket SocketException)                                       ;;; [java.net Socket SocketException]
   [System.Collections.Concurrent |BlockingCollection`1[System.Object]|]))           ;;; [java.util.concurrent BlockingQueue LinkedBlockingQueue SynchronousQueue TimeUnit]))

(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg. Implementations should return the transport."))

(deftype FnTransport [recv-fn send-fn close]
  Transport
  (send [this msg] (send-fn msg) this)
  (recv [this] (.recv this Int64/MaxValue))                                         ;;; Long/MAX_VALUE
  (recv [this timeout] (recv-fn timeout))
  System.IDisposable                                                                ;;; java.io.Closeable
  (Dispose [this] (close)))                                                         ;;; (close [this] (close)))  TODO: This violates good IDisposable practice

(defn fn-transport
  "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
  ([transport-read write] (fn-transport transport-read write nil))
  ([transport-read write close]
   (let [read-queue (sc/make-simple-sync-channel)                                   ;;; (SynchronousQueue.)
         msg-pump (future (try
                            (while true
                              (sc/put read-queue (transport-read)))                 ;;; .put
                            (catch Exception t                                      ;;; Throwable
                              (sc/put read-queue t))))]                             ;;; .put
     (FnTransport.
      (let [failure (atom nil)]
        #(if @failure
           (throw @failure)
           (let [msg (sc/poll read-queue % )]                                       ;;; .poll, remove TimeUnit/MILLISECONDS
             (if (instance? Exception msg)                                          ;;; Throwable
               (do (reset! failure msg) (throw msg))
               msg))))
      write
      (fn [] (close) (future-cancel msg-pump))))))

(defmulti #^{:private true} <bytes class)

(defmethod <bytes :default
  [input]
  input)

(def #^{:private true} utf8 (System.Text.UTF8Encoding.))                   ;;; DM:Added

(defmethod <bytes |System.Byte[]|                                          ;;; (RT/classForName "[B")
  [#^|System.Byte[]| input]                                                ;;; #^"[B"
  (.GetString utf8 input))                                                 ;;; (String. input "UTF-8"))

(defmethod <bytes clojure.lang.IPersistentVector
  [input]
  (vec (map <bytes input)))

(defmethod <bytes clojure.lang.IPersistentMap
  [input]
  (->> input
       (map (fn [[k v]] [k (<bytes v)]))
       (into {})))

(defmacro ^{:private true} rethrow-on-disconnection
  [^Socket s & body]
  `(try
     ~@body
     #_(catch RuntimeException e#                                              ;;; I'm not sure what is covered here
       (if (= "EOF while reading" (.Message e#))                            ;;; .getMessage
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch EndOfStreamException e#                                             ;;; EOFException
       (if (= "Invalid netstring. Unexpected end of input." (.Message e#))      ;;; .getMessage 
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch Exception e#                                                        ;;; Throwable
       (if (and ~s (not (.Connected ~s)))                                       ;;; .isConnected
         (throw (SocketException. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))))

(defn ^{:private true} safe-write-bencode
  "Similar to `bencode/write-bencode`, except it will only writes to the output
   stream if the whole `thing` is writable. In practice, it avoids sending partial
    messages down the transport, which is almost always bad news for the client.

   This will still throw an exception if called with something unencodable."
  [output thing]
  (let [buffer (MemoryStream.)]                                           ;;; ByteArrayOutputStream
    (try
      (bencode/write-bencode buffer thing))
    (.Write ^Stream output (.ToArray buffer))))                 ;;; .write .toByteArray  ^OutputStream

(defn bencode
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using bencode."
  ([^Socket s] (bencode s s s))
  ([in out & [^Socket s]]
   (let [in (PushbackInputStream. (io/input-stream in))
         out (io/output-stream out)]
     (fn-transport
      #(let [payload (rethrow-on-disconnection s (bencode/read-bencode in))
             unencoded (<bytes (payload "-unencoded"))
             to-decode (apply dissoc payload "-unencoded" unencoded)]
         (walk/keywordize-keys (merge (dissoc payload "-unencoded")
                                      (when unencoded {"-unencoded" unencoded})
                                      (<bytes to-decode))))
      #(rethrow-on-disconnection s
                                 (locking out
                                   (doto out
                                     (safe-write-bencode %)
                                     .Flush)))                            ;;; .flush
      (fn []
        (if s
          (.Close s)                                                      ;;; .close
          (do
            (.Close in)                                                   ;;; .close
            (.Close out))))))))                                           ;;; .close

(defn edn
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using EDN."
  {:added "0.7"}
  ([^Socket s] (edn s s s))
  ([in out & [^Socket s]]
   (let [in (PushbackTextReader. (io/text-reader in))                     ;;; java.io.PushbackReader.  io/reader
         out (io/text-writer out)]                                        ;;; io/writer
     (fn-transport
      #(rethrow-on-disconnection s (edn/read in))
      #(rethrow-on-disconnection s
                                 (locking out
                                   ;; TODO: The transport doesn't seem to work
                                   ;; without these bindings. Worth investigating
                                   ;; why
                                   (binding [*print-readably* true
                                             *print-length*   nil
                                             *print-level*    nil]
                                     (doto out
                                       (.Write (str %))                    ;;; .write
                                       (.Flush)))))                        ;;; .flush
      (fn []
        (if s
          (.Close s)                                                       ;;; .close
          (do
            (.Close in)                                                    ;;; .close
            (.Close out))))))))                                            ;;; .close

(defn tty
  "Returns a Transport implementation suitable for serving an nREPL backend
   via simple in/out readers, as with a tty or telnet connection."
  ([^Socket s] (tty s s s))
  ([in out & [^Socket s]]
    (let [r (PushbackTextReader. (io/text-reader in))                     ;;; PushbackReader. io/reader
          w (io/text-writer out)                                          ;;; io/writer
         cns (atom "user")
         prompt (fn [newline?]
                   (when newline? (.Write w (int \newline)))              ;;; .write
                   (.Write w (str @cns "=> ")))                           ;;; .write
         session-id (atom nil)
         read-msg #(let [code (read r)]
                     (merge {:op "eval" :code [code] :ns @cns :id (str "eval" (uuid))}
                            (when @session-id {:session @session-id})))
         read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
         write (fn [{:keys [out err value status ns new-session id] :as msg}]
                 (when new-session (reset! session-id new-session))
                 (when ns (reset! cns ns))
                 (doseq [^String x [out err value] :when x]
                    (.Write w x))                                                    ;;; .write
                 (when (and (= status #{:done}) id (.StartsWith ^String id "eval"))  ;;; .startsWith
                   (prompt true))
                  (.Flush w))                                                        ;;; .flush
         read #(let [head (promise)]
                 (swap! read-seq (fn [s]
                                   (deliver head (first s))
                                   (rest s)))
                 @head)]
     (fn-transport read write
                   (when s
                     (swap! read-seq (partial cons {:session @session-id :op "close"}))
                     #(.Close s))))))                                                ;;; .close

(defn tty-greeting
  "A greeting fn usable with `nrepl.server/start-server`,
   meant to be used in conjunction with Transports returned by the
   `tty` function.

   Usually, Clojure-aware client-side tooling would provide this upon connecting
   to the server, but telnet et al. isn't that."
  [transport]
  (send transport {:out (str ";; nREPL " (:version-string cnrepl.version/version)             ;;; nrepl
                             \newline
                             ";; Clojure " (clojure-version)
                             \newline
                             "user=> ")}))

(defmulti uri-scheme
  "Return the uri scheme associated with a transport var."
  identity)

(defmethod uri-scheme #'bencode [_] "nrepl")

(defmethod uri-scheme #'tty [_] "telnet")

(defmethod uri-scheme #'edn [_] "nrepl+edn")

(defmethod uri-scheme :default
  [transport]
  (printf "WARNING: No uri scheme associated with transport %s\n" transport)
  "unknown")

(deftype QueueTransport [^|System.Collections.Concurrent.BlockingCollection`1[System.Object]| in 
                         ^|System.Collections.Concurrent.BlockingCollection`1[System.Object]| out]           ;DM: ^BlockingQueue
  cnrepl.transport.Transport
  (send [this msg] (.Add out msg) this)                                            ;DM: .put
  (recv [this] (.Take in))                                                         ;DM: .take
  (recv [this timeout] (let [x nil] (.TryTake in (by-ref x) (int timeout)) x)))    ;DM: .poll, removed TimeUnit/MILLISECONDS, added (int .), let, ref

(defn piped-transports
  "Returns a pair of Transports that read from and write to each other."
  []
  (let [a (|System.Collections.Concurrent.BlockingCollection`1[System.Object]|.)               ;;; LinkedBlockingQueue      
        b (|System.Collections.Concurrent.BlockingCollection`1[System.Object]|.)]              ;;; LinkedBlockingQueue  
    [(QueueTransport. a b) (QueueTransport. b a)]))