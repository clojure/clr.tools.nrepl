(ns cnrepl.misc
  "Misc utilities used in nREPL's implementation (potentially also
  useful for anyone extending it)."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [requiring-resolve])
  (:require [clojure.clr.io :as io]                        ;;; clojure.java.io
            [clojure.string :as str]))
	
(defn log
  [ex & msgs]
  (let [ex (when (instance? Exception ex) ex)              ;;; Throwable
        msgs (if ex msgs (cons ex msgs))]
    (binding [*out* *err*]
      (apply println "ERROR:" msgs)
      (when ex (println (.StackTrace ^Exception ex))))))   ;;; (.printStackTrace ^Throwable ex)
	  

(defmacro returning
  "Executes `body`, returning `x`."
  [x & body]
  `(let [x# ~x] ~@body x#))

(defn uuid
  "Returns a new UUID string."
  []
  (str (Guid/NewGuid)))                                    ;;; java.util.UUID/randomUUID

(defn response-for
  "Returns a map containing the :session and :id from the \"request\" `msg`
   as well as all entries specified in `response-data`, which can be one
   or more maps (which will be merged), *or* key-value pairs.
   
   (response-for msg :status :done :value \"5\")
   (response-for msg {:status :interrupted})
   
   The :session value in `msg` may be any Clojure reference type (to accommodate
   likely implementations of sessions) that has an :id slot in its metadata,
   or a string."
  [{:keys [session id]} & response-data]
  {:pre [(seq response-data)]}
  (let [{:keys [status] :as response} (if (map? (first response-data))
                                        (reduce merge response-data)
                                        (apply hash-map response-data))
        response (if (not status)
                   response
                   (assoc response :status (if (coll? status)
                                             status
                                             #{status})))
        basis (merge (when id {:id id})
                     ;; AReference should make this suitable for any session implementation?
                     (when session {:session (if (instance? clojure.lang.AReference session)
                                               (-> session meta :id)
                                               session)}))]
    (merge basis response)))

(defn requiring-resolve
  "Resolves namespace-qualified sym per 'resolve'. If initial resolve fails,
  attempts to require sym's namespace and retries. Returns nil if sym could not
  be resolved."
  [sym]
  (or (resolve sym)
      (try
        (require (symbol (namespace sym)))
        (resolve sym)
        (catch Exception _))))

#_(defmacro with-session-classloader                                        ;;; for now, definitely a no-op
  "This macro does two things:
  
   1. If the session has a classloader set, then execute the body using that.
      This is typically used to trigger the sideloader, when active.
	  
   2. Bind `clojure.lang.Compiler/LOADER` to the context classloader, which
      might also be the sideloader. This is required to get hotloading with
      pomegranate working under certain conditions."
  [session & body]
  `(let [ctxcl#  (.getContextClassLoader (Thread/currentThread))
         alt-cl# (when-let [classloader# (:classloader (meta ~session))]
                   (classloader#))
         cl#     (or alt-cl# ctxcl#)]
     (.setContextClassLoader (Thread/currentThread) cl#)
     (try
       (with-bindings {clojure.lang.Compiler/LOADER cl#}
         ~@body)
       (finally
         (.setContextClassLoader (Thread/currentThread) ctxcl#)))))

(defn java-8?                                                               ;;; definitely a no-oop.
  "Util to check if we are using Java 8. Useful for features that behave
  differently after version 8."
  []
  false)                     ;;; (.startsWith (System/getProperty "java.runtime.version")
                             ;;;               "1.8")

(def safe-var-metadata
  "A list of var metadata attributes are safe to return to the clients.
  We need to guard ourselves against EDN data that's not encodeable/decodable
  with bencode. We also optimize the response payloads by not returning
  redundant metadata."
  [:ns :name :doc :file :arglists :forms :macro :special-form
   :protocol :line :column :added :deprecated :resource])

(defn sanitize-meta
  "Sanitize a Clojure metadata map such that it can be bencoded."
  [m]
  (-> m
      (select-keys safe-var-metadata)
      (update :ns str)
      (update :name str)
      (update :protocol str)
      #_(update :file #(or (some-> % io/resource str) %))                  ;;; not sure what the equivalent would be
      (cond-> (:macro m) (update :macro str))
      (cond-> (:special-form m) (update :special-form str))
      (assoc :arglists-str (str (:arglists m)))
      (cond-> (:arglists m) (update :arglists str))))