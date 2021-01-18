(ns cnrepl.core
  "High level nREPL client support."
  {:author "Chas Emerick"}
  (:require
   clojure.set
   [cnrepl.misc :refer [uuid]]
   ;;;[nrepl.transport :as transport]
   [cnrepl.version :as version])
  (:import
   clojure.lang.LineNumberingTextReader                                ;;; LineNumberingPushbackReader
   #_[java.io Reader StringReader Writer PrintWriter]))
   
   
   ;;; Just a stub for now -- one of the tests in completion_tests needs this around to load.