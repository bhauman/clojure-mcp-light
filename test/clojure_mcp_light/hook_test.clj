(ns clojure-mcp-light.hook-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.hook :as hook]))

(deftest clojure-file?-test
  (testing "identifies Clojure files"
    (is (hook/clojure-file? "test.clj"))
    (is (hook/clojure-file? "test.cljs"))
    (is (hook/clojure-file? "test.cljc"))
    (is (hook/clojure-file? "test.bb"))
    (is (hook/clojure-file? "config.edn")))

  (testing "rejects non-Clojure files"
    (is (nil? (hook/clojure-file? "test.js")))
    (is (nil? (hook/clojure-file? "test.py")))
    (is (nil? (hook/clojure-file? "README.md")))
    (is (nil? (hook/clojure-file? "package.json")))))

(deftest backup-path-test
  (testing "generates deterministic backup paths"
    (let [session-id "test-session-123"
          file-path "/path/to/file.clj"]
      (is (string? (hook/backup-path file-path session-id)))
      (is (= (hook/backup-path file-path session-id)
             (hook/backup-path file-path session-id)))
      (is (not= (hook/backup-path file-path "different-session")
                (hook/backup-path file-path session-id))))))

(deftest process-hook-test
  (testing "allows non-Clojure files through unchanged"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.js"
                                   :content "console.log('hello')"}}
          result (hook/process-hook hook-input)]
      (is (map? result))
      (is (= "allow" (get-in result [:hookSpecificOutput :permissionDecision])))
      (is (nil? (get-in result [:hookSpecificOutput :updatedInput])))))

  (testing "allows valid Clojure code through unchanged"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.clj"
                                   :content "(def x 1)"}}
          result (hook/process-hook hook-input)]
      (is (map? result))
      (is (= "allow" (get-in result [:hookSpecificOutput :permissionDecision])))
      (is (nil? (get-in result [:hookSpecificOutput :updatedInput])))))

  (testing "fixes delimiter errors in Write operations"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.clj"
                                   :content "(def x 1"}}
          result (hook/process-hook hook-input)]
      (is (map? result))
      (is (= "allow" (get-in result [:hookSpecificOutput :permissionDecision])))
      (is (= "Auto-fixed delimiter errors"
             (get-in result [:hookSpecificOutput :permissionDecisionReason])))
      (is (= "(def x 1)"
             (get-in result [:hookSpecificOutput :updatedInput :content])))))

  (testing "allows Edit operations for Clojure files"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Edit"
                      :tool_input {:file_path "test.clj"
                                   :old_string "(def x 1)"
                                   :new_string "(def x 2)"}
                      :session_id "test-session"}
          result (hook/process-hook hook-input)]
      (is (map? result))
      (is (= "allow" (get-in result [:hookSpecificOutput :permissionDecision]))))))
