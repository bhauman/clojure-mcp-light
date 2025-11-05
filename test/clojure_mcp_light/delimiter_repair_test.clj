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
