(ns clojure-mcp-light.hook
  "Claude Code hook for delimiter error detection and repair"
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure-mcp-light.delimiter-repair
             :refer [delimiter-error? fix-delimiters actual-delimiter-error?]]
            [clojure-mcp-light.edit-validator :as edit-validator]
            [clojure-mcp-light.stats :as stats]
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
  [[nil "--cljfmt" "Enable cljfmt formatting on files after edit/write"]
   [nil "--stats [PATH]" "Enable statistics tracking for delimiter events (default: ~/.clojure-mcp-light/stats.log)"
    :id :stats]
   [nil "--log-level LEVEL" "Set log level for file logging"
    :id :log-level
    :parse-fn keyword
    :validate [#{:trace :debug :info :warn :error :fatal :report}
               "Must be one of: trace, debug, info, warn, error, fatal, report"]]
   [nil "--log-file PATH" "Path to log file"
    :id :log-file
    :default "./.clojure-mcp-light-hooks.log"]
   ["-h" "--help" "Show help message"]])

(defn usage []
  (str "clj-paren-repair-claude-hook - Claude Code hook for Clojure delimiter repair\n"
       "\n"
       "Usage: clj-paren-repair-claude-hook [OPTIONS]\n"
       "\n"
       "Options:\n"
       "      --cljfmt              Enable cljfmt formatting on files after edit/write\n"
       "      --stats [PATH]        Enable statistics tracking for delimiter events\n"
       "                            (default: ~/.clojure-mcp-light/stats.log)\n"
       "      --log-level LEVEL     Set log level for file logging\n"
       "                            Levels: trace, debug, info, warn, error, fatal, report\n"
       "      --log-file PATH       Path to log file (default: ./.clojure-mcp-light-hooks.log)\n"
       "  -h, --help                Show this help message"))

(defn error-msg [errors]
  (str "The following errors occurred while parsing command:\n\n"
       (string/join \newline errors)))

(defn handle-cli-args
  "Parse CLI arguments and handle help/errors. Returns options map or exits."
  [args]
  (let [actual-args (if (seq args) args *command-line-args*)
        {:keys [options errors]} (parse-opts actual-args cli-options)]
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

(defn cljfmt-should-fix?
  "Check if file needs formatting using cljfmt check.
  Returns true if file needs formatting, false otherwise.
  Logs stats for formatting state."
  [file-path]
  (try
    (let [result (sh "cljfmt" "check" file-path)
          exit-code (:exit result)]
      (case exit-code
        0 (do
            (stats/log-stats! :cljfmt-already-formatted {:file-path file-path})
            false)
        1 (do
            (stats/log-stats! :cljfmt-needed-formatting {:file-path file-path})
            true)
        ;; Exit code 2 or other = parse error
        (do
          (stats/log-stats! :cljfmt-check-error {:file-path file-path
                                                 :exit-code exit-code})
          false)))
    (catch Exception e
      (stats/log-stats! :cljfmt-check-error {:file-path file-path
                                             :ex-message (ex-message e)})
      false)))

(defn run-cljfmt
  "Run cljfmt fix on the given file path if it needs formatting.
  Returns true if successful, false otherwise."
  [file-path]
  (when *enable-cljfmt*
    (stats/log-stats! :cljfmt-run {:file-path file-path})
    (when (cljfmt-should-fix? file-path)
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
          false)))))

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

(defn validate-and-log-edit
  "Validate an edit operation and log the result.

  Reads file content, validates the edit with sliding indentation and line ending
  normalization, and logs the appropriate stats event.

  Args:
    file-path - Path to the file being edited
    old-string - Text to be replaced
    new-string - Replacement text
    replace-all - Boolean flag for replace-all behavior"
  [file-path old-string new-string replace-all]
  (when (.exists (io/file file-path))
    (let [file-content (slurp file-path)
          validation (edit-validator/validate-sliding-edit
                      file-content
                      old-string
                      new-string
                      (boolean replace-all))
          offset (:indentation-offset validation)]
      (timbre/debug "  Edit validation:" validation)

      ;; Log validation result
      (if (:valid? validation)
        (let [normalized? (:normalized? validation)]
          (cond
            ;; Match with sliding (and possibly normalization)
            (and offset (not (zero? offset)))
            (stats/log-edit-event! :edit-match-success-sliding "PreToolUse:Edit" file-path
                                   :match-count (:match-count validation)
                                   :indentation-offset offset
                                   :normalized normalized?)

            ;; Exact match with normalization only
            normalized?
            (stats/log-edit-event! :edit-match-success-normalized "PreToolUse:Edit" file-path
                                   :match-count (:match-count validation)
                                   :indentation-offset 0
                                   :normalized true)

            ;; Exact match (no adjustments)
            :else
            (stats/log-edit-event! :edit-match-success "PreToolUse:Edit" file-path
                                   :match-count (:match-count validation)
                                   :indentation-offset 0
                                   :normalized false)))
        (stats/log-edit-event! :edit-match-failed "PreToolUse:Edit" file-path
                               :match-count (:match-count validation)
                               :reason (:reason validation))))))

(defmethod process-hook ["PreToolUse" "Write"]
  [{:keys [tool_input]}]
  (let [{:keys [file_path content]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreWrite: clojure" file_path)
      (if (delimiter-error? content)
        (let [actual-error? (actual-delimiter-error? content)]
          (when actual-error?
            (stats/log-event! :delimiter-error "PreToolUse:Write" file_path))
          (timbre/debug "  Delimiter error detected, attempting fix")
          (if-let [fixed-content (fix-delimiters content)]
            (do
              (when actual-error?
                (stats/log-event! :delimiter-fixed "PreToolUse:Write" file_path))
              (timbre/debug "  Fix successful, allowing write with updated content")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :updatedInput {:file_path file_path
                               :content fixed-content}}})
            (do
              (when actual-error?
                (stats/log-event! :delimiter-fix-failed "PreToolUse:Write" file_path))
              (timbre/debug "  Fix failed, denying write")
              {:hookSpecificOutput
               {:hookEventName "PreToolUse"
                :permissionDecision "deny"
                :permissionDecisionReason "Delimiter errors found and could not be auto-fixed"}})))
        (do
          (stats/log-event! :delimiter-ok "PreToolUse:Write" file_path)
          (timbre/debug "  No delimiter errors, allowing write")
          nil)))))

(defmethod process-hook ["PreToolUse" "Edit"]
  [{:keys [tool_input session_id]}]
  (let [{:keys [file_path old_string new_string replace_all]} tool_input]
    (when (clojure-file? file_path)
      (timbre/debug "PreEdit: clojure" file_path)

      ;; Log edit submission and validate
      (stats/log-edit-event! :edit-submitted "PreToolUse:Edit" file_path)

      ;; Validate edit and log results
      ;; this is only for tracking accuracy of submitted edits
      ;; this will be helpful in making future descisions about
      ;; how to handle edits
      ;; most importantly: Is it worth it to try clj-rewrite edits
      ;; at all anymore?
      (when stats/*enable-stats*
        (try
          (validate-and-log-edit file_path old_string new_string replace_all)
          (catch Exception e
            (timbre/error "  Error: Validate Edit failed" (.getMessage e))
            nil)))

      (try
        ;; Create backup (existing behavior)
        (let [backup (backup-file file_path session_id)]
          (timbre/debug "  Created backup:" backup)
          nil)
        (catch Exception e
          (timbre/debug "  Edit processing failed:" (.getMessage e))
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
          (let [actual-error? (actual-delimiter-error? file-content)]
            (when actual-error?
              (stats/log-event! :delimiter-error "PostToolUse:Edit" file_path))
            (timbre/debug "  Delimiter error detected, attempting fix")
            (if-let [fixed-content (fix-delimiters file-content)]
              (try
                (when actual-error?
                  (stats/log-event! :delimiter-fixed "PostToolUse:Edit" file_path))
                (timbre/debug "  Fix successful, applying fix and deleting backup")
                (spit file_path fixed-content)
                (when *enable-cljfmt*
                  (run-cljfmt file_path))
                nil
                (finally
                  (delete-backup backup)))
              (do
                (when actual-error?
                  (stats/log-event! :delimiter-fix-failed "PostToolUse:Edit" file_path))
                (timbre/debug "  Fix failed, restoring from backup:" backup)
                (restore-file file_path backup)
                {:decision "block"
                 :reason (str "Delimiter errors could not be auto-fixed. File was restored from backup to previous state: " file_path)
                 :hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext "There are delimiter errors in the file. So we restored from backup."}})))
          (try
            (stats/log-event! :delimiter-ok "PostToolUse:Edit" file_path)
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
  (let [options (handle-cli-args args)
        log-level (:log-level options)
        log-file (:log-file options)
        enable-logging? (some? log-level)
        stats-opt (:stats options)
        enable-stats? (some? stats-opt)
        stats-path (if (string? stats-opt)
                     (stats/normalize-stats-path stats-opt)
                     (let [home (System/getProperty "user.home")]
                       (str home "/.clojure-mcp-light/stats.log")))]

    (timbre/set-config!
     {:appenders {:spit (assoc
                         (timbre/spit-appender {:fname log-file})
                         :enabled? enable-logging?
                         :min-level (or log-level :report)
                         :ns-filter (if enable-logging?
                                      {:allow "clojure-mcp-light.*"}
                                      {:deny "*"}))}})

    ;; Set cljfmt and stats flags from CLI options
    (binding [*enable-cljfmt* (:cljfmt options)
              stats/*enable-stats* enable-stats?
              stats/*stats-file-path* stats-path]
      (try
        (let [input-json (slurp *in*)
              _ (timbre/debug "INPUT:" input-json)
              _ (when *enable-cljfmt*
                  (timbre/debug "cljfmt formatting is ENABLED"))
              _ (when stats/*enable-stats*
                  (timbre/debug "stats tracking is ENABLED, writing to:" stats/*stats-file-path*))
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
          (System/exit 2))))))
