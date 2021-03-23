(ns dotnet-tasks

  "Dotnet related tasks such as compiling and testing on the clr using `magic` and `nostrand`

  ## Motivation

  This namespace is dedicated to
  - compiling the `clr.tools.nrepl` project into .NET dll
  - running the `clr.tools.nrepl` tests on the clr

  ## Use case

  To compile a project from clojure to .net, we need the `nasser/magic` project.
  Another project called `nasser/nostrand` was made to simplify the compiler setup and use.

  `nasser/nostrand` runs a clojure function that describes the files to be compiled."

  (:require [clojure.test :refer [run-all-tests]]))

(def nrepl-namespaces
  '[ ;; SRC
    cnrepl.ack
    cnrepl.bencode
    cnrepl.core
    cnrepl.debug
    cnrepl.middleware
    cnrepl.sync.channel
    ;; TEST
    cnrepl.bencode-test
    cnrepl.misc-test
    cnrepl transport-test])

(defn build-nrepl
  "Compiles the nrepl project to dlls.
  This function is used my `nostrand` and is called from terminal in the root folder as:
  nos dotnet-tasks/build-nrepl"
  []
  (binding [*compile-path* "build"
            *unchecked-math* *warn-on-reflection*]
    (println "Compile into DLL To : " *compile-path*)
    (doseq [ns nrepl-namespaces]
      (println (str "Compiling " ns))
      (compile ns))))

(defn test-nrepl
  "Run all the nrepl tests.
  This function is used my `nostrand` and is called from terminal in the root folder as:
  nos dotnet-tasks/test-nrepl"
  []
  (binding [ *unchecked-math* *warn-on-reflection*]
    (doseq [ns nrepl-namespaces]
      (require ns))
    (run-all-tests)))
