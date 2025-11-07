(ns clojure-mcp-light.delimiter-repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.delimiter-repair :as dr]))

(deftest delimiter-error?-test
  (testing "detects no error in valid code"
    (is (false? (dr/delimiter-error? "(def x 1)")))
    (is (false? (dr/delimiter-error? "(defn foo [x] (* x 2))")))
    (is (false? (dr/delimiter-error? "(let [x 1 y 2] (+ x y))"))))

  (testing "detects delimiter errors"
    (is (true? (dr/delimiter-error? "(def x 1")))
    (is (true? (dr/delimiter-error? "(defn foo [x (* x 2))")))
    (is (true? (dr/delimiter-error? "(let [x 1 y 2] (+ x y)"))))

  (testing "handles empty strings"
    (is (false? (dr/delimiter-error? ""))))

  (testing "handles multiple forms"
    (is (false? (dr/delimiter-error? "(def x 1) (def y 2)")))
    (is (true? (dr/delimiter-error? "(def x 1) (def y 2")))))

(deftest fix-delimiters-test
  (testing "returns original string when no errors"
    (is (= "(def x 1)" (dr/fix-delimiters "(def x 1)")))
    (is (= "(defn foo [x] (* x 2))" (dr/fix-delimiters "(defn foo [x] (* x 2))"))))

  (testing "fixes missing closing delimiters"
    (is (= "(def x 1)" (dr/fix-delimiters "(def x 1")))
    (is (= "(+ 1 2 3)" (dr/fix-delimiters "(+ 1 2 3"))))

  (testing "fixes nested delimiter errors"
    (let [result (dr/fix-delimiters "(let [x 1] (+ x 2")]
      (is (string? result))
      (is (false? (dr/delimiter-error? result)))))

  (testing "returns string for valid input"
    (is (string? (dr/fix-delimiters "(def x 1)")))))

(deftest parinfer-repair-test
  (testing "returns success map for fixable code"
    (let [result (dr/parinfer-repair "(def x 1")]
      (is (map? result))
      (is (contains? result :success))
      (is (contains? result :text)))))

(deftest delimiter-error-with-function-literals-test
  (testing "does not error on valid function literals"
    (is (false? (dr/delimiter-error? "#(+ % 1)")))
    (is (false? (dr/delimiter-error? "(map #(* % 2) [1 2 3])")))
    (is (false? (dr/delimiter-error? "(filter #(> % 10) nums)"))))

  (testing "detects delimiter errors in code with function literals"
    (is (true? (dr/delimiter-error? "#(+ % 1")))
    (is (true? (dr/delimiter-error? "(map #(* % 2) [1 2 3")))
    (is (true? (dr/delimiter-error? "(filter #(> % 10 nums)")))))

(deftest delimiter-error-with-regex-test
  (testing "does not error on valid regex literals"
    (is (false? (dr/delimiter-error? "#\"pattern\"")))
    (is (false? (dr/delimiter-error? "(re-find #\"[0-9]+\" s)")))
    (is (false? (dr/delimiter-error? "#\"\\s+\""))))

  (testing "detects delimiter errors in code with regex literals"
    (is (true? (dr/delimiter-error? "(re-find #\"[0-9]+\" s")))
    (is (true? (dr/delimiter-error? "(if (re-matches #\"test\" x) true")))))

(deftest delimiter-error-with-quotes-test
  (testing "does not error on valid quoted forms"
    (is (false? (dr/delimiter-error? "'(1 2 3)")))
    (is (false? (dr/delimiter-error? "`(foo ~bar)")))
    (is (false? (dr/delimiter-error? "(quote (a b c))"))))

  (testing "detects delimiter errors in code with quotes"
    (is (true? (dr/delimiter-error? "'(1 2 3")))
    (is (true? (dr/delimiter-error? "`(foo ~bar")))))

(deftest fix-delimiters-with-function-literals-test
  (testing "fixes delimiter errors in code with function literals"
    (let [result (dr/fix-delimiters "(map #(* % 2) [1 2 3")]
      (is (string? result))
      (is (false? (dr/delimiter-error? result)))))

  (testing "preserves function literals when fixing"
    (let [code "(defn process [xs] (map #(inc %) xs"
          fixed (dr/fix-delimiters code)]
      (is (string? fixed))
      (is (false? (dr/delimiter-error? fixed)))
      (is (re-find #"#\(" fixed)))) ; Function literal preserved

  (testing "returns original when no errors in code with function literals"
    (is (= "(map #(+ % 1) [1 2 3])"
           (dr/fix-delimiters "(map #(+ % 1) [1 2 3])")))))

(deftest delimiter-error-with-deref-test
  (testing "does not error on valid deref forms"
    (is (false? (dr/delimiter-error? "@foo")))
    (is (false? (dr/delimiter-error? "(swap! @atom inc)")))
    (is (false? (dr/delimiter-error? "@(future (+ 1 2))"))))

  (testing "detects delimiter errors in code with deref"
    (is (true? (dr/delimiter-error? "(swap! @atom inc")))
    (is (true? (dr/delimiter-error? "@(future (+ 1 2")))))

(deftest delimiter-error-with-var-test
  (testing "does not error on valid var forms"
    (is (false? (dr/delimiter-error? "#'foo")))
    (is (false? (dr/delimiter-error? "(alter-var-root #'foo inc)")))
    (is (false? (dr/delimiter-error? "#'clojure.core/+"))))

  (testing "detects delimiter errors in code with var"
    (is (true? (dr/delimiter-error? "(alter-var-root #'foo inc")))
    (is (true? (dr/delimiter-error? "(let [x #'foo] (x 1 2")))))

(deftest delimiter-error-with-reader-conditionals-test
  (testing "does not error on valid reader conditional forms"
    (is (false? (dr/delimiter-error? "#?(:clj 1 :cljs 2)")))
    (is (false? (dr/delimiter-error? "#?@(:clj [1 2] :cljs [3 4])")))
    (is (false? (dr/delimiter-error? "[1 2 #?(:clj 3)]"))))

  (testing "detects delimiter errors in surrounding code with reader conditionals"
    (is (true? (dr/delimiter-error? "(let [x #?(:clj 1 :cljs 2)] x")))
    (is (true? (dr/delimiter-error? "[1 2 #?(:clj 3) 4")))))

(deftest delimiter-error-with-metadata-test
  (testing "does not error on valid metadata forms"
    (is (false? (dr/delimiter-error? "^:private foo")))
    (is (false? (dr/delimiter-error? "^{:doc \"test\"} bar")))
    (is (false? (dr/delimiter-error? "(defn ^:private foo [] 1)"))))

  (testing "detects delimiter errors in code with metadata"
    (is (true? (dr/delimiter-error? "^{:doc \"test\"} (defn foo [] 1")))
    (is (true? (dr/delimiter-error? "(defn ^:private foo [] 1")))))

(deftest delimiter-error-comprehensive-test
  (testing "handles complex real-world Clojure code without errors"
    (is (false? (dr/delimiter-error?
                  "(ns foo.bar
                     (:require [clojure.string :as str]))

                   (defn ^:private process [data]
                     (let [result @(future
                                     (map #(* % 2)
                                          (filter #(> % 10) data)))]
                       (when-let [x (first result)]
                         #?(:clj (str/upper-case x)
                            :cljs (.toUpperCase x)))))"))))

  (testing "detects delimiter errors in complex nested code"
    (is (true? (dr/delimiter-error?
                 "(ns foo.bar
                    (:require [clojure.string :as str]))

                  (defn process [data]
                    (let [result @(future
                                    (map #(* % 2)
                                         (filter #(> % 10) data)))]
                      (when-let [x (first result)]
                        (str/upper-case x"))))) ; Missing multiple closing parens
