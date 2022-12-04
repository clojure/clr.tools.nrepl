(ns cnrepl.config
  "Server configuration utilities.
  Some server options can be configured via configuration
  files (local or global).  This namespace provides
  convenient API to work with them.

  The config resolution algorithm is the following:
  The global config file .nrepl/nrepl.edn is merged with
  any local config file (.nrepl.edn) if present.
  The values in the local config file take precedence."
  {:author "Bozhidar Batsov"
   :added  "0.5"}
  (:require
   [clojure.clr.io :as io]                                            ;;; clojure.java.io
   [clojure.edn :as edn]))

(def ^:private home-dir
  "The user's home directory."
  (Environment/GetEnvironmentVariable "USERPROFILE"))                 ;;; (System/getProperty "user.home")

(def config-dir
  "nREPL's configuration directory.
  By default it's ~/.nrepl, but this can be overridden
  with the NREPL_CONFIG_DIR env variable."
  (or (Environment/GetEnvironmentVariable "NREPL_CONFIG_DIR")         ;;; (System/getenv "NREPL_CONFIG_DIR")
      (Environment/GetEnvironmentVariable "nrepl.config.dir")
      (str home-dir System.IO.Path/DirectorySeparatorChar ".nrepl")))         ;;; java.io.File/separator

(def config-file
  "nREPL's config file."
  (str config-dir System.IO.Path/PathSeparator "nrepl.edn"))         ;;; java.io.File/separator

(defn- load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  (with-open [r (io/text-reader source)]                                  ;;; io/reader 
    (edn/read (clojure.lang.PushbackTextReader. r))))                ;;; java.io.PushbackReader.

(defn- load-config
  "Load the configuration file identified by `filename`.
  Return its contents as EDN if the file exists,
  or an empty map otherwise."
  [filename]
  (let [file (System.IO.FileInfo. filename)]                            ;;; io/file
    (if (.Exists file)                                              ;;; .exists
      (load-edn file)
      {})))

(def config
  "Configuration map.
  It's created by merging the global configuration file
  with a local configuration file that would normally
  the placed in the directory in which you're running
  nREPL."
  (merge
   (load-config config-file)
   (load-config ".nrepl.edn")))