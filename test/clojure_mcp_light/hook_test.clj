(ns clojure-mcp-light.hook-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.hook :as hook]
            [clojure-mcp-light.tmp :as tmp]
            [babashka.fs :as fs]))

(deftest clojure-file?-test
  (testing "identifies Clojure files by extension"
    (is (hook/clojure-file? "test.clj"))
    (is (hook/clojure-file? "test.cljs"))
    (is (hook/clojure-file? "test.cljc"))
    (is (hook/clojure-file? "test.cljd"))
    (is (hook/clojure-file? "test.bb"))
    (is (hook/clojure-file? "test.lpy"))
    (is (hook/clojure-file? "config.edn")))

  (testing "case-insensitive extension matching"
    (is (hook/clojure-file? "test.CLJ"))
    (is (hook/clojure-file? "test.CLJS"))
    (is (hook/clojure-file? "test.CLJD"))
    (is (hook/clojure-file? "test.EDN"))
    (is (hook/clojure-file? "test.LPY")))

  (testing "identifies files with Babashka shebang"
    (let [temp-file (str (fs/create-temp-file {:prefix "test-bb-" :suffix ".sh"}))]
      (try
        (spit temp-file "#!/usr/bin/env bb\n(println \"hello\")" :encoding "UTF-8")
        (is (hook/clojure-file? temp-file))
        (finally
          (fs/delete-if-exists temp-file))))

    (let [temp-file (str (fs/create-temp-file {:prefix "test-bb-" :suffix ".sh"}))]
      (try
        (spit temp-file "#!/usr/bin/bb\n(println \"hello\")" :encoding "UTF-8")
        (is (hook/clojure-file? temp-file))
        (finally
          (fs/delete-if-exists temp-file))))

    (let [temp-file (str (fs/create-temp-file {:prefix "test-bb-" :suffix ".sh"}))]
      (try
        (spit temp-file "#!/usr/local/bin/bb --nrepl-server 1667\n(println \"hello\")" :encoding "UTF-8")
        (is (hook/clojure-file? temp-file))
        (finally
          (fs/delete-if-exists temp-file)))))

  (testing "rejects files without Babashka shebang"
    (let [temp-file (str (fs/create-temp-file {:prefix "test-bash-" :suffix ".sh"}))]
      (try
        (spit temp-file "#!/bin/bash\necho \"hello\"" :encoding "UTF-8")
        (is (nil? (hook/clojure-file? temp-file)))
        (finally
          (fs/delete-if-exists temp-file)))))

  (testing "rejects non-Clojure files"
    (is (nil? (hook/clojure-file? "test.js")))
    (is (nil? (hook/clojure-file? "test.py")))
    (is (nil? (hook/clojure-file? "README.md")))
    (is (nil? (hook/clojure-file? "package.json"))))

  (testing "handles nil file path"
    (is (nil? (hook/clojure-file? nil))))

  (testing "handles non-existent file without error"
    (is (nil? (hook/clojure-file? "/nonexistent/file.xyz")))))

(deftest process-hook-test
  (testing "allows non-Clojure files through unchanged"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.js"
                                   :content "console.log('hello')"}}
          result (hook/process-hook hook-input)]
      (is (nil? result))))

  (testing "allows valid Clojure code through unchanged"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.clj"
                                   :content "(def x 1)"}}
          result (hook/process-hook hook-input)]
      (is (nil? result))))

  (testing "fixes delimiter errors in Write operations"
    (let [hook-input {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path "test.clj"
                                   :content "(def x 1"}}
          result (hook/process-hook hook-input)]
      (is (map? result))
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
      (is (nil? result)))))

;; ============================================================================
;; Codex apply_patch
;; ============================================================================

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
  (let [dir (fs/create-temp-dir {:prefix "apply-patch-test-"})]
    (try
      (f (str dir))
      (finally
        (fs/delete-tree dir)))))

(deftest pre-apply-patch-test
  (testing "backs up existing Clojure files before apply_patch runs"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))
              session-id "pre-apply-patch-test"
              backup-path (tmp/backup-path {:session-id session-id} file-path)]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1)" :encoding "UTF-8")

          (try
            (is (nil? (hook/process-hook
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
              session-id "pre-apply-patch-no-revert-test"
              backup-path (tmp/backup-path {:session-id session-id} file-path)]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1)" :encoding "UTF-8")

          (binding [hook/*enable-revert* false]
            (is (nil? (hook/process-hook
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
            (is (nil? (hook/process-hook
                       {:session_id "post-apply-patch-test"
                        :cwd dir
                        :hook_event_name "PostToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}
                        :tool_response {:exit_code 0}}))))

          (is (= "(def x 1)" (slurp file-path :encoding "UTF-8"))))))))

(deftest post-apply-patch-skips-failed-patch-test
  (testing "non-zero apply_patch exit_code skips repair"
    (with-temp-project*
      (fn [dir]
        (let [file-path (str (fs/path dir "src/core.clj"))]
          (fs/create-dirs (fs/parent file-path))
          (spit file-path "(def x 1" :encoding "UTF-8")

          (binding [hook/*enable-cljfmt* false]
            (is (nil? (hook/process-hook
                       {:session_id "post-apply-patch-failed-test"
                        :cwd dir
                        :hook_event_name "PostToolUse"
                        :tool_name "apply_patch"
                        :tool_input {:command (apply-patch-command "src/core.clj")}
                        :tool_response {:exit_code 1}}))))

          ;; File content untouched because patch did not run.
          (is (= "(def x 1" (slurp file-path :encoding "UTF-8"))))))))

(deftest post-apply-patch-move-failure-test
  (testing "unfixable delimiter errors after a move are reverted to the old path"
    (with-temp-project*
      (fn [dir]
        (let [from (str (fs/path dir "src/old.clj"))
              to (str (fs/path dir "src/new.clj"))
              session-id "post-apply-patch-move-failure-test"
              command (str "*** Begin Patch\n"
                           "*** Update File: src/old.clj\n"
                           "*** Move to: src/new.clj\n"
                           "*** End Patch\n")]
          (fs/create-dirs (fs/parent from))
          (spit from "(def x 1)" :encoding "UTF-8")

          ;; Pre-hook backs up the source.
          (is (nil? (hook/process-hook
                     {:session_id session-id
                      :cwd dir
                      :hook_event_name "PreToolUse"
                      :tool_name "apply_patch"
                      :tool_input {:command command}})))

          ;; Simulate apply_patch performing a move and introducing an
          ;; unfixable delimiter error in the new file.
          (fs/delete-if-exists from)
          (spit to "(def x 1" :encoding "UTF-8")

          ;; Force fix-and-format-file! to fail so we hit the revert path.
          (with-redefs [hook/fix-and-format-file!
                        (fn [_ _ _]
                          {:success false :delimiter-fixed false :formatted false
                           :message "stub failure"})]
            (let [response (hook/process-hook
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

