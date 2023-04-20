(ns cnrepl.version
  {:author "Colin Jones"
   :added  "0.5"}
  #_(:import java.util.Properties))

#_(defn- map-from-property-filepath [file]                         ;;; makes no sense in CLR environment
  (try
    (let [file-reader (.. (Thread/currentThread)
                          (getContextClassLoader)
                          (getResourceAsStream file))
          props (Properties.)]
      (.load props file-reader)
      (into {} props))
    (catch Exception e nil)))

#_(defn- get-properties-filename [group artifact]
  (str "META-INF/maven/" group "/" artifact "/pom.properties"))

(defn- get-version
  "Attempts to get the project version from system properties (set when running
  Leiningen), or a properties file based on the group and artifact ids (in jars
  built by Leiningen), or a default version passed in.  Falls back to an empty
  string when no default is present."
  ([group artifact]
   (get-version group artifact ""))
  ([group artifact default-version]
   (or #_(System/getProperty (str artifact ".version"))            ;;; makes no sense in CLR environment
       #_(-> (get-properties-filename group artifact)              ;;; makes no sense in CLR environment
           map-from-property-filepath
           (get "version"))
       default-version)))

(def ^{:private true} version-string
  "Current version of nREPL as a string.
  See also `version`."
  (get-version "nrepl" "nrepl"))

(def version
  "Current version of nREPL.
  Map of :major, :minor, :incremental, :qualifier, and :version-string."
  (assoc (->> version-string
              (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
              rest
              (map #(try (Int32/Parse %) (catch Exception e nil)))    ;;; Integer/parseInt
              (zipmap [:major :minor :incremental :qualifier]))
         :version-string version-string))
		 
		 
;;; IF WE WANT THIS TO WORK, WE'LL HAVE TO HAND-CODE IT!!