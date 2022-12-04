(ns cnrepl.util.completion-test
  "Unit tests for completion utilities."
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [cnrepl.util.completion :as completion :refer [completions]]))

(def t-var "var" nil)
(defn t-fn "fn" [x] x)
(defmacro t-macro "macro" [y] y)

(defn- candidates
  "Return only the candidate names without any additional
  metadata for them."
  ([prefix]
   (candidates prefix *ns*))
  ([prefix ns]
   (map :candidate (completions prefix ns))))

(defn- distinct-candidates?
  "Return true if every candidate occurs in the list of
   candidates only once."
  ([prefix]
   (distinct-candidates? prefix *ns*))
  ([prefix ns]
   (apply distinct? (candidates prefix ns))))

(deftest completions-test
  (testing "var completion"
    (is (= '("alength" "alias" "all-ns" "alter" "alter-meta!" "alter-var-root")
           (candidates "al" 'clojure.core)))

    (is (= '("cio/make-binary-reader" "cio/make-binary-writer" "cio/make-input-stream" "cio/make-output-stream" "cio/make-text-reader" "cio/make-text-writer")      ;;; a bunch of things replaced
           (candidates "cio/make" 'clojure.core)))                   ;;; jio

    (is (= '("clojure.core/alter" "clojure.core/alter-meta!" "clojure.core/alter-var-root")
           (candidates "clojure.core/alt" 'clojure.core)))

    (is (= () (candidates "fake-ns-here/")))

    (is (= () (candidates "/"))))

  (testing "namespace completion"
    (is (= '("cnrepl.util.completion" "cnrepl.util.completion-test")
           (candidates "cnrepl.util.comp")))

    #_(is (set/subset?                                   ;;; I'm not sure wha tthe provlem is -- not getting any of these internals.  ClojureCLR bug or completions bug?
         #{"clojure.core" "clojure.core.ArrayChunk" "clojure.core.ArrayManager" "clojure.core.IVecImpl" "clojure.core.Vec" "clojure.core.VecNode" "clojure.core.VecSeq" "clojure.core.protocols" "clojure.core.protocols.InternalReduce"}
         (set (candidates "clojure.co")))))

  (testing "Java instance methods completion"
    (is (= '(".ToUpper" ".ToUpperInvariant")              ;;; .toUpperCase
           (candidates ".ToUpper")))                      ;;; .toUpper

    (is (distinct-candidates? ".ToString")))              ;;; .toString

  (testing "static members completion"
    (is (= '("Console/KeyAvailable")               ;;; "System/out"
           (candidates "Console/K")))              ;;; "System/o"

    (is (= '("System.Console/KeyAvailable")               ;;; "java.lang.System/out"
           (candidates "System.Console/KeyAvailable")))   ;;; "java.lang.System/out"

    (is (some #{"String/Concat"} (candidates "String/")))    ;;; "String/valueOf
    (is (distinct-candidates? "String/C"))                    ;;; String/v

    (is (not (some #{"String/IndexOf" ".IndexOf"} (candidates "String/")))))    ;;; indexOf

  (testing "candidate types"
    (is (some #{{:candidate "t-var"
                 :type :var}}
              (completions "t-var" 'cnrepl.util.completion-test)))
    (is (some #{{:candidate "t-var"
                 :type :var
                 :doc "var"}}
              (completions "t-var" 'cnrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "t-fn"
                 :type :function}}
              (completions "t-fn" 'cnrepl.util.completion-test)))
    (is (some #{{:candidate "t-fn"
                 :type :function
                 :arglists "([x])"
                 :doc "fn"}}
              (completions "t-fn" 'cnrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "t-macro"
                 :type :macro}}
              (completions "t-macro" 'cnrepl.util.completion-test)))
    (is (some #{{:candidate "t-macro"
                 :type :macro
                 :arglists "([y])"
                 :doc "macro"}}
              (completions "t-macro" 'cnrepl.util.completion-test {:extra-metadata #{:arglists :doc}})))
    (is (some #{{:candidate "unquote" :type :var}}
              (completions "unquote" 'clojure.core)))
    (is (some #{{:candidate "if" :ns "clojure.core" :type :special-form}}
              (completions "if" 'clojure.core)))
    (is (some #{{:candidate "ArgumentException" :type :class}}          ;;; UnsatisfiedLinkError
              (completions "ArgumentEx" 'clojure.core)))                  ;;;
    ;; ns with :doc meta
    (is (some #{{:candidate "clojure.core"
                 :type :namespace}}
              (completions "clojure.core" 'clojure.core)))
    (is (some #{{:candidate "clojure.core"
                 :type :namespace
                 :doc "Fundamental library of the Clojure language"}}
              (completions "clojure.core" 'clojure.core {:extra-metadata #{:doc}})))
    ;; ns with docstring argument
    (is (some #{{:candidate "cnrepl.util.completion-test"
                 :type :namespace}}
              (completions "cnrepl.util.completion-test" 'clojure.core)))
    (is (some #{{:candidate "cnrepl.util.completion-test"
                 :type :namespace
                 :doc "Unit tests for completion utilities."}}
              (completions "cnrepl.util.completion-test" 'clojure.core {:extra-metadata #{:doc}})))
    (is (some #{{:candidate "Int32/Parse" :type :static-method}}                                       ;;; Integer/parseInt
              (completions "Int32/Parse" 'clojure.core)))                                              ;;; Integer/parseInt
    (is (some #{{:candidate "Environment/SetEnvironmentVariable", :type :static-method}}                                   ;;; "File/separator"
              (completions "Environment/" 'cnrepl.util.completion)))                                          ;;; "File/"
    (is (some #{{:candidate ".ToString" :type :method}}                                                ;;;.toString                                
              (completions ".ToString" 'clojure.core)))))                                              ;;;.toString  

(deftest keyword-completions-test
  (testing "colon prefix"
    (is (set/subset? #{":doc" ":refer" ":refer-clojure"}
                     (set (candidates ":" *ns*)))))

  (testing "unqualified keywords"
    (do #{:t-key-foo :t-key-bar :t-key-baz :t-key/quux}
        (is (set/subset? #{":t-key-foo" ":t-key-bar" ":t-key-baz" ":t-key/quux"}
                         (set (candidates ":t-key" *ns*))))))

  (testing "auto-resolved unqualified keywords"
    (do #{::foo ::bar ::baz}
        (is (set/subset? #{":cnrepl.util.completion-test/bar" ":cnrepl.util.completion-test/baz"}
                         (set (candidates ":cnrepl.util.completion-test/ba" *ns*))))
        (is (set/subset? #{"::bar" "::baz"}
                         (set (candidates "::ba" 'cnrepl.util.completion-test))))))

  (testing "auto-resolved qualified keywords"
    (do #{:cnrepl.core/aliased-one :cnrepl.core/aliased-two}
        (require '[cnrepl.core :as core])
        (is (set/subset? #{"::core/aliased-one" "::core/aliased-two"}
                         (set (candidates "::core/ali" *ns*))))))

  (testing "namespace aliases"
    (is (set/subset? #{"::set"}
                     (set (candidates "::s" 'cnrepl.util.completion-test)))))

  (testing "namespace aliases without namespace"
    (is (empty? (candidates "::/" *ns*)))))