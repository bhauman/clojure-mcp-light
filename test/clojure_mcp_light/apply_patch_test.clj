(ns clojure-mcp-light.apply-patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.apply-patch :as apply-patch]))

(deftest ops-test
  (testing "parses an update and resolves relative paths against cwd"
    (is (= [{:op :update :path "/proj/src/core.clj"}]
           (apply-patch/ops
            {:cwd "/proj"
             :tool_input {:command (str "*** Begin Patch\n"
                                        "*** Update File: src/core.clj\n"
                                        "@@\n"
                                        "-(def x 1)\n"
                                        "+(def x 2)\n"
                                        "*** End Patch\n")}}))))

  (testing "leaves absolute paths alone"
    (is (= [{:op :update :path "/elsewhere/core.clj"}]
           (apply-patch/ops
            {:cwd "/proj"
             :tool_input {:command "*** Update File: /elsewhere/core.clj\n"}}))))

  (testing "folds Update File + Move to into a single :move op"
    (is (= [{:op :move :from "/proj/src/old.clj" :to "/proj/src/new.clj"}]
           (apply-patch/ops
            {:cwd "/proj"
             :tool_input {:command (str "*** Begin Patch\n"
                                        "*** Update File: src/old.clj\n"
                                        "*** Move to: src/new.clj\n"
                                        "*** End Patch\n")}}))))

  (testing "parses add and delete ops"
    (is (= [{:op :add :path "/proj/src/a.clj"}
            {:op :delete :path "/proj/src/b.clj"}]
           (apply-patch/ops
            {:cwd "/proj"
             :tool_input {:command (str "*** Begin Patch\n"
                                        "*** Add File: src/a.clj\n"
                                        "*** Delete File: src/b.clj\n"
                                        "*** End Patch\n")}}))))

  (testing "returns no ops for missing or empty commands"
    (is (= [] (apply-patch/ops {:cwd "/proj" :tool_input {}})))
    (is (= [] (apply-patch/ops {:cwd "/proj" :tool_input {:command ""}})))
    (is (= [] (apply-patch/ops {:cwd "/proj"
                                :tool_input {:command "*** Begin Patch\n*** End Patch\n"}})))))

(deftest op-source-and-target-test
  (testing "op-source is the pre-patch path needing backup"
    (is (= "/a.clj" (apply-patch/op-source {:op :update :path "/a.clj"})))
    (is (= "/a.clj" (apply-patch/op-source {:op :move :from "/a.clj" :to "/b.clj"})))
    (is (nil? (apply-patch/op-source {:op :add :path "/a.clj"})))
    (is (nil? (apply-patch/op-source {:op :delete :path "/a.clj"}))))

  (testing "op-target is the post-patch path needing repair"
    (is (= "/a.clj" (apply-patch/op-target {:op :update :path "/a.clj"})))
    (is (= "/b.clj" (apply-patch/op-target {:op :move :from "/a.clj" :to "/b.clj"})))
    (is (= "/a.clj" (apply-patch/op-target {:op :add :path "/a.clj"})))
    (is (nil? (apply-patch/op-target {:op :delete :path "/a.clj"})))))
