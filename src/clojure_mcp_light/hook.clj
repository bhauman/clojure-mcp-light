(ns clojure-mcp-light.hook
  "Claude Code hook for delimiter error detection and repair"
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure-mcp-light.delimiter-repair :refer [delimiter-error? fix-delimiters]]
            [clojure-mcp-light.tmp :as tmp]))

;; ============================================================================
;; Logging Configuration
;; ============================================================================

(def ^:dynamic *enable-logging* false)
(def ^:dynamic *log-file* "hook-logs/clj-paren-repair-hook.log")

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
;; Claude Code Hook Functions
;; ============================================================================

(defn clojure-file? [file-path]
  (some #(string/ends-with? file-path %)
        [".clj" ".cljs" ".cljc" ".bb" ".edn"]))

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
                {:hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext (str "Auto-fixed delimiter errors in " file_path)}}
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
            {:reason "No delimiter errors were found in the file."
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
      ;; Always return success, never block SessionEnd
      {:hookSpecificOutput
       {:hookEventName "SessionEnd"}})
    (catch Exception e
      (log-msg "  Unexpected error during cleanup:" (.getMessage e))
      ;; Even on complete failure, return success
      {:hookSpecificOutput
       {:hookEventName "SessionEnd"}})))

(defn -main []
  (try
    (let [input-json (slurp *in*)
          _ (log-msg "INPUT:" input-json)
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
      (System/exit 2))))
