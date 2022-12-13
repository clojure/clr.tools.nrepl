(ns cnrepl.debug)

(def ^{:private true} pr-agent (agent *out*))

(defn- write-out [out & args]
  (binding [*out* out]
    (pr "Thd " (-> System.Threading.Thread/CurrentThread (.ManagedThreadId)) ": ")
    (prn (apply str args))
	out))

(defn prn-thread [& args]
  (send pr-agent write-out  args))
  
  
   