(ns clojure-mcp-light.paren-repair-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.paren-repair :as pr]))

(deftest process-stdin-test
  (testing "handles valid code via stdin without delimiter errors"
    (let [input "(defn foo [x] (+ x 1))\n"
          out-capture (java.io.StringWriter.)
          result (binding [*in* (java.io.StringReader. input)
                           *out* out-capture]
                   (pr/process-stdin))]
      (is (true? (:success result)))
      (is (false? (:delimiter-fixed result)))
      (is (= "(defn foo [x] (+ x 1))\n" (str out-capture)))))

  (testing "repairs delimiter errors via stdin and reports :delimiter-fixed true"
    (let [input "[ui-button {:text \"Play\" :on-click [[:actions/send-prompt \"foo\"]}]]"
          out-capture (java.io.StringWriter.)
          result (binding [*in* (java.io.StringReader. input)
                           *out* out-capture]
                   (pr/process-stdin))]
      (is (true? (:success result)))
      (is (true? (:delimiter-fixed result)))
      (is (true? (:changed result)))
      (is (= "[ui-button {:text \"Play\" :on-click [[:actions/send-prompt \"foo\"]]}]"
             (str out-capture))))))

(deftest process-file-test
  (testing "returns failure for nonexistent file"
    (let [result (pr/process-file "nonexistent-file-12345.clj")]
      (is (false? (:success result)))
      (is (= "File does not exist" (:message result)))))

  (testing "skips non-Clojure files"
    (let [tmp (fs/create-temp-file {:suffix ".txt"})]
      (try
        (let [result (pr/process-file (str tmp))]
          (is (false? (:success result)))
          (is (= "Not a Clojure file (skipping)" (:message result))))
        (finally
          (fs/delete-if-exists tmp)))))

  (testing "repairs and formats Clojure files on disk"
    (let [tmp (fs/create-temp-file {:suffix ".clj"})]
      (try
        (spit (fs/file tmp) "(defn bar [x (+ x 2))")
        (let [result (pr/process-file (str tmp))]
          (is (true? (:success result)))
          (is (true? (:delimiter-fixed result)))
          (is (= "(defn bar [x (+ x 2)])" (slurp (fs/file tmp)))))
        (finally
          (fs/delete-if-exists tmp))))))
