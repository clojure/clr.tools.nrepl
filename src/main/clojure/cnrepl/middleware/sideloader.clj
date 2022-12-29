(ns cnrepl.middleware.sideloader
  "Support the ability to interactively load resources (including Clojure source
  files) and classes from the client. This can be used to add dependencies to
  the nREPL environment after initial startup."
  {:author "Christophe Grand"
   :added  "0.7"}
  (:require
   [clojure.clr.io :as io]                                                              ;;; clojure.java.io
   [cnrepl.middleware :as middleware :refer [set-descriptor!]]
   [cnrepl.misc :refer [response-for]]
   [cnrepl.transport :as t]))
;;;;;;;;;;;;;;;;;;  Making this a no-op for now -- no idea what the equivalent would be in CLR ;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: dedup with base64 in elisions branch once both are merged
(defn base64-encode [^System.IO.Stream in]                                            ;;; ^java.io.InputStream
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        sb (StringBuilder.)]
    (loop [shift 4 buf 0]
      (let [got (.Read in)]                                                           ;;; .read
        (if (neg? got)
          (do
            (when-not (= shift 4)
              (let [n (bit-and (bit-shift-right buf 6) 63)]
                (.Append sb (.get_Chars table n))))                                   ;;; .append .charAt
            (cond
              (= shift 2) (.append sb "==")
              (= shift 0) (.append sb \=))
            (str sb))
          (let [buf (bit-or buf (bit-shift-left got shift))
                n (bit-and (bit-shift-right buf 6) 63)]
            (.Append sb (.get_Chars table n))                                        ;;; .append .charAt
            (let [shift (- shift 2)]
              (if (neg? shift)
                (do
                  (.Append sb (.get_Chars table (bit-and buf 63)))                   ;;; .append .charAt
                  (recur 4 0))
                (recur shift (bit-shift-left buf 6))))))))))

(defn base64-decode [^String s]
  (let [table "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        in (System.IO.StringReader. s)                                               ;;; java.io.StringReader.
        bos (System.IO.MemoryStream.)]                                               ;;; java.io.ByteArrayOutputStream.
    (loop [bits 0 buf 0]
      (let [got (.Read in)]                                                          ;;; .read
        (when-not (or (neg? got) (= 61 got))
          (let [table-idx (.IndexOf table got)]                                      ;;; .indexOf
            (if (= -1 table-idx)
              (recur bits buf)
              (let [buf (bit-or table-idx (bit-shift-left buf 6))
                    bits (+ bits 6)]
                (if (<= 8 bits)
                  (let [bits (- bits 8)]
                    (.Write bos (bit-shift-right buf bits))                         ;;; .write
                    (recur bits (bit-and 63 buf)))
                  (recur bits buf))))))))
    (.ToArray bos)))                                                                ;;; .toByteArray	
		
(defn- sideloader
  "Creates a classloader that obey standard delegating policy."
  [{:keys [session id transport] :as msg} pending]
  (fn [] nil))
    #_(let [resolve-fn
          (fn [type name]
            (let [p (promise)]
              ;; Swap into the atom *before* sending the lookup request to ensure that the server
              ;; knows about the pending request when the client sends the response.
              (swap! pending assoc [(clojure.core/name type) name] p)
              (t/send transport (response-for msg
                                              {:status :sideloader-lookup
                                               :type type
                                               :name name}))
              @p))]
      (proxy [clojure.lang.DynamicClassLoader] [(.getContextClassLoader (Thread/currentThread))]
        (findResource [name]
          (when-some  [bytes (resolve-fn "resource" name)]
            (let [file (doto (java.io.File/createTempFile "nrepl-sideload-" (str "-" (re-find #"[^/]*$" name)))
                         .deleteOnExit)]
              (io/copy bytes file)
              (-> file .toURI .toURL))))
        (findClass [name]
          (if-some  [bytes (resolve-fn "class" name)]
            (.defineClass ^clojure.lang.DynamicClassLoader this name bytes nil)
            (throw (ClassNotFoundException. name))))))

(defn wrap-sideloader
  "Middleware that enables the client to serve resources and classes to the server."
  [h]
  (let [pending (atom {})]
    (fn [{:keys [op type name content transport session] :as msg}]
      (case op
        "sideloader-start"
        (alter-meta! session assoc  
   	                 :classloader (sideloader msg pending)
                     ::pending pending))

      "sideloader-provide"
      (let [pending (::pending (meta session))]
        (if-some [p (and pending (@pending [type name]))]
          (do
            (deliver p (let [bytes (base64-decode content)]
                         (when (pos? (count bytes))
                           bytes)))
            (swap! pending dissoc [type name])
            (t/send transport (response-for msg {:status :done})))
          (t/send transport (response-for msg {:status #{:done :unexpected-provide}
                                               :type type
                                               :name name}))))

      (h msg))))

(set-descriptor! #'wrap-sideloader
                 {:requires #{"clone"}
                  :expects #{"eval"}
                  :handles {"sideloader-start"
                            {:doc "Starts a sideloading session."
                             :requires {"session" "the id of the session"}
                             :optional {}
                             :returns {"status" "\"sideloader-lookup\", never ever returns \"done\"."}}
                            "sideloader-provide"
                            {:doc "Provides a requested class or resource."
                             :requires {"session" "the id of the session"
                                        "content" "base64 string"
                                        "type" "\"class\" or \"resource\""
                                        "name" "the class or resource name"}
                             :optional {}
                             :returns {}}}})