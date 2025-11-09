(ns clojure-mcp-light.hook
  "Claude Code hook for delimiter error detection and repair"
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure-mcp-light.delimiter-repair :refer [delimiter-error? fix-delimiters]]
            [clojure-mcp-light.tmp :as tmp]
            [taoensso.timbre :as timbre]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:dynamic *enable-cljfmt* false)

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
    (timbre/debug "CLI args received:" actual-args)
    (timbre/debug "*command-line-args*:" *command-line-args*)
    (timbre/debug "Parsed options:" options)
    (timbre/debug "Parse errors:" errors)

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
      (timbre/debug "Running cljfmt fix on:" file-path)
      (let [result (sh "cljfmt" "fix" file-path)]
        (if (zero? (:exit result))
          (do
            (timbre/debug "  cljfmt succeeded")
            true)
          (do
            (timbre/debug "  cljfmt failed:" (:err result))
            false)))
      (catch Exception e
        (timbre/debug "  cljfmt error:" (.getMessage e))
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

(defmethod process-hook :default [_] nil)

;; the only way I could see to supply the CC session id to the clj-nrepl-eval cmd
;; not convinced we need this, so I'm still trying this out
(defmethod process-hook ["PreToolUse" "Bash"]
  [{:keys [tool_input session_id]}]
  (let [command (:command tool_input)
        updated-command (str "CML_CLAUDE_CODE_SESSION_ID=" session_id " " command)]
    (timbre/debug "[PreToolUse Bash] Prepending session ID:" session_id)
    (timbre/debug "[PreToolUse Bash] Original command:" command)
    (timbre/debug "[PreToolUse Bash] Updated command:" updated-command)
    {:hookSpecificOutput
     {:hookEventName "PreToolUse"
      :updatedInput {:command updated-command}}}))

(defmethod process-hook ["PreToolUse" "Write"]
  [{:keys [tool_input]}]
  (let [{:keys [file_path content]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreWrite: clojure" file_path)
      (if (delimiter-error? content)
        (do
          (timbre/debug "  Delimiter error detected, attempting fix")
          (if-let [fixed-content (fix-delimiters content)]
            (do
              (timbre/debug "  Fix successful, allowing write with updated content")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :updatedInput {:file_path file_path
                               :content fixed-content}}})
            (do
              (timbre/debug "  Fix failed, denying write")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :permissionDecision "deny"
                :permissionDecisionReason "Delimiter errors found and could not be auto-fixed"}})))
        (do
          (timbre/debug "  No delimiter errors, allowing write")
          nil)))))

(defmethod process-hook ["PreToolUse" "Edit"]
  [{:keys [tool_input session_id]}]
  (let [{:keys [file_path]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreEdit: clojure" file_path)
      (try
        (let [backup (backup-file file_path session_id)]
          (timbre/debug "  Created backup:" backup)
          nil)
        (catch Exception e
          (timbre/debug "  Backup failed:" (.getMessage e))
          nil)))))

(defmethod process-hook ["PostToolUse" "Write"]
  [{:keys [tool_input tool_response]}]
  (let [{:keys [file_path]} tool_input]
    (when (and (clojure-file? file_path) tool_response *enable-cljfmt*)
      (timbre/debug "PostWrite: clojure cljfmt" file_path)
      (run-cljfmt file_path)
      nil)))

(defmethod process-hook ["PostToolUse" "Edit"]
  [{:keys [tool_input tool_response session_id]}]
  (let [{:keys [file_path]} tool_input]
    (when (and (clojure-file? file_path) tool_response)
      (timbre/debug "PostEdit: clojure" file_path)
      (let [backup (tmp/backup-path {:session-id session_id} file_path)
            file-content (slurp file_path)]
        (if (delimiter-error? file-content)
          (do
            (timbre/debug "  Delimiter error detected, attempting fix")
            (if-let [fixed-content (fix-delimiters file-content)]
              (try
                (timbre/debug "  Fix successful, applying fix and deleting backup")
                (spit file_path fixed-content)
                (when *enable-cljfmt*
                  (run-cljfmt file_path))
                nil
                (finally
                  (delete-backup backup)))
              (do
                (timbre/debug "  Fix failed, restoring from backup:" backup)
                (restore-file file_path backup)
                {:decision "block"
                 :reason (str "Delimiter errors could not be auto-fixed. File was restored from backup to previous state: " file_path)
                 :hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext "There are delimiter errors in the file. So we restored from backup."}})))
          (try
            (timbre/debug "  No delimiter errors, deleting backup")
            (when *enable-cljfmt*
              (run-cljfmt file_path))
            nil
            (finally
              (delete-backup backup))))))))

(defmethod process-hook ["SessionEnd" nil]
  [{:keys [session_id]}]
  (timbre/info "SessionEnd: cleaning up session" session_id)
  (try
    (let [report (tmp/cleanup-session! {:session-id session_id})]
      (timbre/info "  Cleanup attempted for session IDs:" (:attempted report))
      (timbre/info "  Deleted directories:" (:deleted report))
      (timbre/info "  Skipped (non-existent):" (:skipped report))
      (when (seq (:errors report))
        (timbre/warn "  Errors during cleanup:")
        (doseq [{:keys [path error]} (:errors report)]
          (timbre/warn "    " path "-" error)))
      nil)
    (catch Exception e
      (timbre/error "  Unexpected error during cleanup:" (.getMessage e))
      nil)))

(defn -main [& args]
  (let [enable-logging? (= "true" (System/getenv "CML_ENABLE_LOGGING"))
        log-file "hook-logs/clj-paren-repair-hook-timbre.log"]

    ;; Configure timbre logging BEFORE any logging calls
    ;; Always use set-config! to replace default println appender
    (timbre/set-config!
     {:min-level (if enable-logging? :debug :report)
      :appenders {:spit (assoc
                         (timbre/spit-appender {:fname log-file})
                         :enabled? enable-logging?)}})

    (let [options (handle-cli-args args)]
      ;; Set cljfmt flag from CLI options
      (binding [*enable-cljfmt* (:cljfmt options)]
        (try
          (let [input-json (slurp *in*)
                _ (timbre/debug "INPUT:" input-json)
                _ (when *enable-cljfmt*
                    (timbre/debug "cljfmt formatting is ENABLED"))
                hook-input (json/parse-string input-json true)
                response (process-hook hook-input)
                _ (timbre/debug "OUTPUT:" (json/generate-string response))]
            (when response
              (println (json/generate-string response)))
            (System/exit 0))
          (catch Exception e
            (timbre/error "Hook error:" (.getMessage e))
            (timbre/error "Stack trace:" (with-out-str (.printStackTrace e)))
            (binding [*out* *err*]
              (println "Hook error:" (.getMessage e))
              (println "Stack trace:" (with-out-str (.printStackTrace e))))
            (System/exit 2)))))))
