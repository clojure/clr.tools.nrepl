(ns cnrepl.server
  "Default server implementations"
  {:author "Chas Emerick"}
  (:require
   [cnrepl.ack :as ack]
   [cnrepl.middleware.dynamic-loader :as dynamic-loader]
   [cnrepl.middleware :as middleware]
   cnrepl.middleware.completion
   cnrepl.middleware.interruptible-eval
   cnrepl.middleware.load-file
   cnrepl.middleware.lookup
   cnrepl.middleware.session
   cnrepl.middleware.sideloader
   [cnrepl.misc :refer [log response-for returning]]
   [cnrepl.transport :as t])
  (:import
   [System.Net.Sockets  SocketOptionLevel SocketOptionName 
                        Socket SocketType ProtocolType SocketShutdown]
   [System.Net IPAddress IPEndPoint]))                                             ;;;[java.net InetSocketAddress ServerSocket]

(defn handle*
  [msg handler transport]
  (try
    #_(debug/prn-thread "handle* " msg ", transport " (.GetHashCode transport))    ;DEBUG  
    (handler (assoc msg :transport transport))
    (catch Exception t                                                             ;;; Throwable
      (log t "Unhandled REPL handler exception processing message" msg))))

(defn- normalize-msg
  "Normalize messages that are not quite in spec. This comes into effect with
   The EDN transport, and other transports that allow more types/data structures
   than bencode, as there's more opportunity to be out of specification."
  [msg]
  (cond-> msg
    (keyword? (:op msg)) (update :op name)))

(defn handle
  "Handles requests received via [transport] using [handler].
   Returns nil when [recv] returns nil for the given transport."
  [handler transport]
  (when-let [msg (normalize-msg (t/recv transport))]
    #_(debug/prn-thread "handle posting future for " msg) ;DEBUG
    (future (handle* msg handler transport))
    (recur handler transport)))

(defn- safe-close
  [^IDisposable x]                                                                    ;;; ^java.io.Closeable
  (try
    #_(debug/prn-thread "safe-close: Disposing a " (class x) " " (.GetHashCode x))
    (.Dispose x)                                                                      ;;; .close
    (catch Exception e                                                                ;;; java.io.IOException
      (log e "Failed to close " x))))

(defn- accept-connection
  [{:keys [^Socket server-socket open-transports transport greeting handler]          ;;; ^ServerSocket
    :as server}]
  (when (.IsBound server-socket)                                                      ;;; when-not (.isClosed server-socket)
    (let [sock (.Accept server-socket)]                                               ;;; .accept
	  #_(debug/prn-thread "Accepting connection")  ;DEBUG
      (future (let [transport (transport sock)]
				#_(debug/prn-thread "accept-connection: created transport " (.GetHashCode transport))  ;DEBUG
                (try
                  (swap! open-transports conj transport)
                  (when greeting (greeting transport))
                  (handle handler transport)
                  (finally
                    (swap! open-transports disj transport)
					#_(debug/prn-thread "accept-connection: closing transport " (.GetHashCode transport))  ;DEBUG
                    (safe-close transport)))))
      (future (accept-connection server)))))

(defn stop-server
  "Stops a server started via `start-server`."
  [{:keys [open-transports ^Socket server-socket] :as server}]                        ;;; ^ServerSocket
  (returning server
             #_(debug/prn-thread "Stoping server " (:port server))                    ;DEBUG 
            (when (.Connected server-socket)                                          ;;; DM: ADDED
	          (.Shutdown server-socket SocketShutdown/Both))                          ;;; DM: ADDED
            (.Close server-socket)                                                    ;;; .close
             (swap! open-transports
                    #(reduce
                      (fn [s t]
                        ;; should always be true for the socket server...
                        (if (instance? IDisposable t)                                 ;;; java.io.Closeable
                          (do
                            (safe-close t)
                            (disj s t))
                          s))
                      % %))))

(defn unknown-op
  "Sends an :unknown-op :error for the given message."
  [{:keys [op transport] :as msg}]
  (t/send transport (response-for msg :status #{:error :unknown-op :done} :op op)))

(def default-middleware
  "Middleware vars that are implicitly merged with any additional
   middleware provided to nrepl.server/default-handler."
  [#'cnrepl.middleware/wrap-describe
   #'cnrepl.middleware.completion/wrap-completion
   #'cnrepl.middleware.interruptible-eval/interruptible-eval
   #'cnrepl.middleware.load-file/wrap-load-file
   #'cnrepl.middleware.lookup/wrap-lookup
   #'cnrepl.middleware.session/add-stdin
   #'cnrepl.middleware.session/session
   #'cnrepl.middleware.sideloader/wrap-sideloader
   #'cnrepl.middleware.dynamic-loader/wrap-dynamic-loader])

(def built-in-ops
  "Get all the op names from default middleware automatically"
  (->> default-middleware
       (map #(-> % meta :nrepl.middleware/descriptor :handles keys))
       (reduce concat)
       set))

(def ^{:deprecated "0.8.0"} default-middlewares
  "Use `nrepl.server/default-middleware` instead. Middleware"
  default-middleware)

(defn default-handler
  "A default handler supporting interruptible evaluation, stdin, sessions,
   readable representations of evaluated expressions via `pr`, sideloading, and
   dynamic loading of middleware.

   Additional middleware to mix into the default stack may be provided; these
   should all be values (usually vars) that have an nREPL middleware descriptor
   in their metadata (see `nrepl.middleware/set-descriptor!`).

   This handler bootstraps by initiating with just the dynamic loader, then
   using that to load the other middleware."
  [& additional-middleware]
  (let [initial-handler (dynamic-loader/wrap-dynamic-loader nil)
        state           (atom {:handler initial-handler
                               :stack   [#'cnrepl.middleware.dynamic-loader/wrap-dynamic-loader]})]
    (binding [dynamic-loader/*state* state]
      (initial-handler {:op          "swap-middleware"
                        :state       state
                        :middleware (concat default-middleware additional-middleware)}))
    (fn [msg]
      (binding [dynamic-loader/*state* state]
        ((:handler @state) msg)))))

(defrecord Server [server-socket port open-transports transport greeting handler]
  IDisposable                                                                                      ;;; java.io.Closeable
  (Dispose [this] (stop-server this)))                                                             ;;; (close [this] (stop-server this))

(defn start-server
  "Starts a socket-based nREPL server.  Configuration options include:

   * :port — defaults to 0, which autoselects an open port
   * :bind — bind address, by default \"127.0.0.1\"
   * :handler — the nREPL message handler to use for each incoming connection;
       defaults to the result of `(default-handler)`
   * :transport-fn — a function that, given a java.net.Socket corresponding
       to an incoming connection, will return a value satisfying the
       nrepl.Transport protocol for that Socket.
   * :ack-port — if specified, the port of an already-running server
       that will be connected to inform of the new server's port.
       Useful only by Clojure tooling implementations.
   * :greeting-fn - called after a client connects, receives
       a nrepl.transport/Transport. Usually, Clojure-aware client-side tooling
       would provide this greeting upon connecting to the server, but telnet et
       al. isn't that. See `nrepl.transport/tty-greeting` for an example of such
       a function.

   Returns a (record) handle to the server that is started, which may be stopped
   either via `stop-server`, (.close server), or automatically via `with-open`.
   The port that the server is open on is available in the :port slot of the
   server map (useful if the :port option is 0 or was left unspecified."
  [& {:keys [port bind transport-fn handler ack-port greeting-fn]}]
  (let [port (or port 0)
                                                                    ;;; addr (fn [^String bind ^Integer port]  (INetSockeAddress. bind port))
        transport-fn (or transport-fn t/bencode)
        ;; We fallback to 127.0.0.1 instead of to localhost to avoid
        ;; a dependency on the order of ipv4 and ipv6 records for
        ;; localhost in /etc/hosts
        bind (or bind "127.0.0.1")
		ipe (IPEndPoint. (IPAddress/Parse bind) port)                                                       ;;; DM:ADDED
        ss (doto                                                                                 ;;; (ServerSocket.)
		     (Socket. (.AddressFamily ipe)  SocketType/Stream  ProtocolType/Tcp)                 ;;;  DM:Added
             (.SetSocketOption SocketOptionLevel/Socket SocketOptionName/ReuseAddress true)      ;;; (.setReuseAddress true)
             (.Bind ^IPEndPoint ipe))                                                            ;;; (.bind (addr bind port))
        server (Server. ss
                          (.Port ^IPEndPoint (.LocalEndPoint ss))                                ;;; (.getLocalPort ss)
                        (atom #{})
                        transport-fn
                        greeting-fn
                        (or handler (default-handler)))]
	#_(debug/prn-thread "Starting server " server) ;DEBUG
    (.Listen ss 0)                                                                               ;;; DM: ADDED
    (future (accept-connection server))
    (when ack-port
      (ack/send-ack (:port server) ack-port transport-fn))
    server))