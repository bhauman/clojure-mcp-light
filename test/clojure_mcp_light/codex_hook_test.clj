(ns clojure-mcp-light.codex-hook-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.codex-hook :as codex-hook]
            [clojure-mcp-light.claude-hook :as hook]
            [clojure-mcp-light.tmp :as tmp]))

(defn- apply-patch-command
  [file-path]
  (str "*** Begin Patch\n"
       "*** Update File: " file-path "\n"
       "@@\n"
       "-(def x 1)\n"
       "+(def x 1\n"
       "*** End Patch\n"))

(defn- with-temp-project*
  [f]
  (let [dir (fs/create-temp-dir {:prefix "codex-hook-test-"})]
    (try
      (f (str dir))
      (finally
        (fs/delete-tree dir)))))

(deftest apply-patch-file-paths-test
  (testing "extracts apply_patch file metadata lines"
    (is (= ["src/core.clj" "src/old.clj" "src/new.clj" "README.md"]
           (codex-hook/apply-patch-file-paths
            (str "*** Begin Patch\n"
                 "*** Update File: src/core.clj\n"
                 "*** Delete File: src/old.clj\n"
                 "*** Move to: src/new.clj\n"
                 "*** Add File: README.md\n"
                 "*** End Patch\n"))))))

(deftest apply-patch-ops-test
  (testing "resolves relative paths against Codex cwd"
    (with-temp-project*
      (fn [dir]
        (let [target (str (fs/path dir "src/core.clj"))]
          (is (= [{:op :update :path target}]
                 (codex-hook/apply-patch-ops
                  {:cwd dir
                   :tool_input {:command (apply-patch-command "src/core.clj")}})))))))

  (testing "folds Update File + Move to into a single :move op"
    (with-temp-project*
      (fn [dir]
        (let [from (str (fs/path dir "src/old.clj"))
              to (str (fs/path dir "src/new.clj"))]
          (is (= [{:op :move :from from :to to}]
                 (codex-hook/apply-patch-ops
                  {:cwd dir
                   :tool_input {:command (str "*** Begin Patch\n"
                                              "*** Update File: src/old.clj\n"
                                              "*** Move to: src/new.clj\n"
                                              "*** End Patch\n")}}))))))))

(deftest post-apply-patch-move-failure-test
  (testing "unfixable delimiter errors after a move are reverted to the old path"
    (with-temp-project*
      (fn [dir]
        (let [from (str (fs/path dir "src/old.clj"))
              to (str (fs/path dir "src/new.clj"))
              session-id "codex-move-failure-test"
              command (str "*** Begin Patch\n"
                           "*** Update File: src/old.clj\n"
                           "*** Move to: src/new.clj\n"
                           "*** End Patch\n")]
          (fs/create-dirs (fs/parent from))
          (spit from "(def x 1)" :encoding "UTF-8")

          ;; Pre-hook backs up the source.
          (is (nil? (codex-hook/process-codex-hook
                     {:session_id session-id
                      :cwd dir
                      :hook_event_name "PreToolUse"
                      :tool_name "apply_patch"
                      :tool_input {:command command}})))

          ;; Simulate apply_patch performing a move and introducing an unfixable
          ;; delimiter error in the new file.
          (fs/delete-if-exists from)
          (spit to "(def x 1" :encoding "UTF-8")

          ;; Force fix-and-format-file! to fail so we hit the revert path.
          (with-redefs [hook/fix-and-format-file!
                        (fn [_ _ _]
                          {:success false :delimiter-fixed false :formatted false
                           :message "stub failure"})]
            (let [response (codex-hook/process-codex-hook
                            {:session_id session-id
                             :cwd dir
                             :hook_event_name "PostToolUse"
                             :tool_name "apply_patch"
                             :tool_input {:command command}
                             :tool_response {:exit_code 0}})]
              (is (= "block" (:decision response)))
              (is (re-find #"Restored from backup" (:reason response)))))

          ;; Old path is back with original content, new path is gone.
          (is (fs/exists? from))
          (is (= "(def x 1)" (slurp from :encoding "UTF-8")))
          (is (not (fs/exists? to))))))))

(deftest post-apply-patch-skips-failed-patch-test
  (testing "non-zero apply_patch exit_code skips repair"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1" :encoding "UTF-8")

          (binding [hook/*enable-cljfmt* false]
            (is (nil? (codex-hook/process-codex-hook
                       {:session_id "codex-failed-patch-test"
                        :cwd dir
                        :hook_event_name "PostToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}
                        :tool_response {:exit_code 1}}))))

          ;; File content untouched because patch did not run.
          (is (= "(def x 1" (slurp file-path :encoding "UTF-8"))))))))

(deftest pre-apply-patch-test
  (testing "backs up existing Clojure files before apply_patch runs"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))
              session-id "codex-pre-apply-patch-test"
              backup-path (tmp/backup-path {:session-id session-id} file-path)]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1)" :encoding "UTF-8")

          (try
            (is (nil? (codex-hook/process-codex-hook
                       {:session_id session-id
                        :cwd dir
                        :hook_event_name "PreToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}})))
            (is (fs/exists? backup-path))
            (is (= "(def x 1)" (slurp backup-path :encoding "UTF-8")))
            (finally
              (hook/delete-backup backup-path))))))))

(deftest pre-apply-patch-no-revert-test
  (testing "does not back up files when revert is disabled"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))
              session-id "codex-pre-apply-patch-no-revert-test"
              backup-path (tmp/backup-path {:session-id session-id} file-path)]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1)" :encoding "UTF-8")

          (binding [hook/*enable-revert* false]
            (is (nil? (codex-hook/process-codex-hook
                       {:session_id session-id
                        :cwd dir
                        :hook_event_name "PreToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}}))))

          (is (not (fs/exists? backup-path))))))))

(deftest post-apply-patch-test
  (testing "repairs Clojure files after apply_patch runs"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1" :encoding "UTF-8")

          (binding [hook/*enable-cljfmt* false]
            (is (nil? (codex-hook/process-codex-hook
                       {:session_id "codex-post-apply-patch-test"
                        :cwd dir
                        :hook_event_name "PostToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}
                        :tool_response {:exit_code 0}}))))

          (is (= "(def x 1)" (slurp file-path :encoding "UTF-8"))))))))
