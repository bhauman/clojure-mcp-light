(ns clojure-mcp-light.hook
  "Claude Code hook for delimiter error detection and repair"
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure-mcp-light.delimiter-repair :refer [delimiter-error? fix-delimiters]]
            [clojure-mcp-light.tmp :as tmp]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:dynamic *enable-logging*
  (= "true" (System/getenv "CML_ENABLE_LOGGING")))
(def ^:dynamic *log-file* "hook-logs/clj-paren-repair-hook.log")
(def ^:dynamic *enable-cljfmt* false)

(defn log-msg
  "Log message if logging is enabled"
  [& args]
  (when *enable-logging*
    (try
      (let [timestamp (java.time.LocalDateTime/now)
            msg (str timestamp " - " (string/join " " args) "\n")]
        (spit *log-file* msg :append true))
      (catch Exception _
        ;; Silently fail - don't break hook if logging fails
        nil))))

;; ============================================================================
;; CLI Options
;; ============================================================================

(def cli-options
  [["-c" "--cljfmt" "Enable cljfmt formatting on files after edit/write"]
   ["-h" "--help" "Show help message"]])

(defn usage []
  (str "clj-paren-repair-claude-hook - Claude Code hook for Clojure delimiter repair\n"
       "\n"
       "Usage: clj-paren-repair-claude-hook [OPTIONS]\n"
       "\n"
       "Options:\n"
       "  -c, --cljfmt    Enable cljfmt formatting on files after edit/write\n"
       "  -h, --help      Show this help message"))

(defn error-msg [errors]
  (str "The following errors occurred while parsing command:\n\n"
       (string/join \newline errors)))

(defn handle-cli-args
  "Parse CLI arguments and handle help/errors. Returns options map or exits."
  [args]
  (let [actual-args (if (seq args) args *command-line-args*)
        {:keys [options errors]} (parse-opts actual-args cli-options)]
    (log-msg "CLI args received:" actual-args)
    (log-msg "*command-line-args*:" *command-line-args*)
    (log-msg "Parsed options:" options)
    (log-msg "Parse errors:" errors)

    (cond
      (:help options)
      (do
        (println (usage))
        (System/exit 0))

      errors
      (do
        (binding [*out* *err*]
          (println (error-msg errors))
          (println)
          (println (usage)))
        (System/exit 1))

      :else
      options)))

;; ============================================================================
;; Claude Code Hook Functions
;; ============================================================================

(defn clojure-file? [file-path]
  (some #(string/ends-with? file-path %)
        [".clj" ".cljs" ".cljc" ".bb" ".edn"]))

(defn run-cljfmt
  "Run cljfmt fix on the given file path.
  Returns true if successful, false otherwise."
  [file-path]
  (when *enable-cljfmt*
    (try
      (log-msg "Running cljfmt fix on:" file-path)
      (let [result (sh "cljfmt" "fix" file-path)]
        (if (zero? (:exit result))
          (do
            (log-msg "  cljfmt succeeded")
            true)
          (do
            (log-msg "  cljfmt failed:" (:err result))
            false)))
      (catch Exception e
        (log-msg "  cljfmt error:" (.getMessage e))
        false))))

(defn backup-file
  "Backup file to temp location, returns backup path"
  [file-path session-id]
  (let [ctx {:session-id session-id}
        backup (tmp/backup-path ctx file-path)
        backup-file (io/file backup)
        content (slurp file-path)]
    ;; Ensure parent directories exist
    (.mkdirs (.getParentFile backup-file))
    (spit backup content)
    backup))

(defn restore-file
  "Restore file from backup and delete backup"
  [file-path backup-path]
  (when (.exists (io/file backup-path))
    (try
      (let [backup-content (slurp backup-path)]
        (spit file-path backup-content)
        true)
      (finally
        (io/delete-file backup-path)))))

(defn delete-backup
  "Delete backup file if it exists"
  [backup-path]
  (when (.exists (io/file backup-path))
    (io/delete-file backup-path)))

(defn process-pre-write
  "Process content before write operation.
  Returns fixed content if Clojure file has delimiter errors, nil otherwise."
  [file-path content]
  (when (and (clojure-file? file-path) (delimiter-error? content))
    (fix-delimiters content)))

(defn process-pre-edit
  "Process file before edit operation.
  Creates a backup of Clojure files, returns backup path if created."
  [file-path session-id]
  (when (clojure-file? file-path)
    (backup-file file-path session-id)))

(defn process-post-edit
  "Process file after edit operation.
  Compares edited file with backup, fixes delimiters if content changed,
  and cleans up backup file."
  [file-path session-id]
  (when (clojure-file? file-path)
    (let [ctx {:session-id session-id}
          backup-file (tmp/backup-path ctx file-path)]
      (try
        (let [backup-content (try (slurp backup-file) (catch Exception _ nil))
              file-content (slurp file-path)]
          (when (not= backup-content file-content)
            (process-pre-write file-path file-content)))
        (finally
          (delete-backup backup-file))))))

(defmulti process-hook
  (fn [hook-input]
    [(:hook_event_name hook-input) (:tool_name hook-input)]))

(defmethod process-hook :default
  [hook-input]
  (when (= (:hook_event_name hook-input) "PreToolUse")
    {:hookSpecificOutput
     {:hookEventName "PreToolUse"
      :permissionDecision "allow"}}))

(defmethod process-hook ["PreToolUse" "Bash"]
  [{:keys [tool_input session_id]}]
  (let [command (:command tool_input)
        updated-command (str "CML_CLAUDE_CODE_SESSION_ID=" session_id " " command)]
    (log-msg (str "[PreToolUse Bash] Prepending session ID: " session_id))
    (log-msg (str "[PreToolUse Bash] Original command: " command))
    (log-msg (str "[PreToolUse Bash] Updated command: " updated-command))
    {:hookSpecificOutput
     {:hookEventName "PreToolUse"
      :permissionDecision "allow"
      :updatedInput {:command updated-command}}}))

(defmethod process-hook ["PreToolUse" "Write"]
  [{:keys [tool_input]}]
  (let [{:keys [file_path content]} tool_input
        base-output {:hookSpecificOutput
                     {:hookEventName "PreToolUse"
                      :permissionDecision "allow"}}]
    (log-msg "PreWrite:" file_path)
    (if-not (clojure-file? file_path)
      (do
        (log-msg "  Skipping non-Clojure file")
        base-output)
      (if (delimiter-error? content)
        (do
          (log-msg "  Delimiter error detected, attempting fix")
          (let [fixed-content (fix-delimiters content)]
            (if fixed-content
              (do
                (log-msg "  Fix successful, allowing write with updated content")
                (-> base-output
                    (assoc-in [:hookSpecificOutput :permissionDecisionReason] "Auto-fixed delimiter errors")
                    (assoc-in [:hookSpecificOutput :updatedInput] {:file_path file_path
                                                                   :content fixed-content})))
              (do
                (log-msg "  Fix failed, denying write")
                (-> base-output
                    (assoc-in [:hookSpecificOutput :permissionDecision] "deny")
                    (assoc-in [:hookSpecificOutput :permissionDecisionReason] "Delimiter errors found and could not be auto-fixed"))))))
        (do
          (log-msg "  No delimiter errors, allowing write")
          base-output)))))

(defmethod process-hook ["PreToolUse" "Edit"]
  [{:keys [tool_input session_id]}]
  (let [{:keys [file_path]} tool_input
        base-output {:hookSpecificOutput
                     {:hookEventName "PreToolUse"
                      :permissionDecision "allow"}}]
    (log-msg "PreEdit:" file_path)
    (if-not (clojure-file? file_path)
      (do
        (log-msg "  Skipping non-Clojure file")
        base-output)
      (try
        (let [backup (backup-file file_path session_id)]
          (log-msg "  Created backup:" backup)
          base-output)
        (catch Exception e
          (log-msg "  Backup failed:" (.getMessage e))
          base-output)))))

(defmethod process-hook ["PostToolUse" "Write"]
  [{:keys [tool_input tool_response]}]
  (let [{:keys [file_path]} tool_input]
    (log-msg "PostWrite:" file_path)
    (when (and (clojure-file? file_path) tool_response *enable-cljfmt*)
      (run-cljfmt file_path)
      ;; Return nil to not interfere with the hook chain
      nil)))

(defmethod process-hook ["PostToolUse" "Edit"]
  [{:keys [tool_input tool_response session_id]}]
  (let [{:keys [file_path]} tool_input
        ctx {:session-id session_id}
        backup (tmp/backup-path ctx file_path)]
    (log-msg "PostEdit:" file_path)
    (if-not (and (clojure-file? file_path) tool_response)
      nil
      (let [file-content (slurp file_path)]
        (if (delimiter-error? file-content)
          (do
            (log-msg "  Delimiter error detected, attempting fix")
            (if-let [fixed-content (fix-delimiters file-content)]
              (try
                (log-msg "  Fix successful, applying fix and deleting backup")
                (spit file_path fixed-content)
                ;; Run cljfmt after successful delimiter fix
                (when *enable-cljfmt*
                  (run-cljfmt file_path))
                {:hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext (str "Auto-fixed delimiter errors in " file_path
                                          (when *enable-cljfmt* " and applied cljfmt formatting"))}}
                (finally
                  (delete-backup backup)))
              (do
                (log-msg "  Fix failed, restoring from backup:" backup)
                (restore-file file_path backup)
                {:decision "block"
                 :reason (str "Delimiter errors could not be auto-fixed. File was restored from backup to previous state: " file_path)
                 :hookSpecificOutput {:hookEventName "PostToolUse"
                                      :additionalContext "There are delimiter errors in the file. So we restored from backup."}})))
          (try
            (log-msg "  No delimiter errors, deleting backup")
            ;; Run cljfmt even when no delimiter errors
            (when *enable-cljfmt*
              (run-cljfmt file_path))
            {:reason (str "No delimiter errors were found in the file."
                          (when *enable-cljfmt* " Applied cljfmt formatting."))
             :hookSpecificOutput {:hookEventName "PostToolUse"}}
            (finally
              (delete-backup backup))))))))

(defmethod process-hook ["SessionEnd" nil]
  [{:keys [session_id]}]
  (log-msg "SessionEnd: cleaning up session" session_id)
  (try
    (let [report (tmp/cleanup-session! {:session-id session_id})]
      (log-msg "  Cleanup attempted for session IDs:" (:attempted report))
      (log-msg "  Deleted directories:" (:deleted report))
      (log-msg "  Skipped (non-existent):" (:skipped report))
      (when (seq (:errors report))
        (log-msg "  Errors during cleanup:")
        (doseq [{:keys [path error]} (:errors report)]
          (log-msg "    " path "-" error)))
      ;; SessionEnd hooks use simpler response format (no hookSpecificOutput)
      nil)
    (catch Exception e
      (log-msg "  Unexpected error during cleanup:" (.getMessage e))
      ;; Even on complete failure, return nil (success)
      nil)))

(defn -main [& args]
  (let [options (handle-cli-args args)]
    ;; Set cljfmt flag from CLI options
    (binding [*enable-cljfmt* (:cljfmt options)]
      (try
        (let [input-json (slurp *in*)
              _ (log-msg "INPUT:" input-json)
              _ (when *enable-cljfmt*
                  (log-msg "cljfmt formatting is ENABLED"))
              hook-input (json/parse-string input-json true)
              response (process-hook hook-input)
              _ (log-msg "OUTPUT:" (json/generate-string response))]
          (when response
            (println (json/generate-string response)))
          (System/exit 0))
        (catch Exception e
          (log-msg "Hook error:" (.getMessage e))
          (log-msg "Stack trace:" (with-out-str (.printStackTrace e)))
          (binding [*out* *err*]
            (println "Hook error:" (.getMessage e))
            (println "Stack trace:" (with-out-str (.printStackTrace e))))
          (System/exit 2))))))
