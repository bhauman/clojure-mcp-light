#!/usr/bin/env bb

(require '[edamame.core :as e]
         '[clojure.java.shell :as shell]
         '[cheshire.core :as json])

;; ============================================================================
;; Logging Configuration
;; ============================================================================

(def ^:dynamic *enable-logging* true)
(def ^:dynamic *log-file* "hook-logs/clj-paren-repair-hook.log")

(defn log-msg
  "Log message if logging is enabled"
  [& args]
  (when *enable-logging*
    (try
      (let [timestamp (java.time.LocalDateTime/now)
            msg (str timestamp " - " (clojure.string/join " " args) "\n")]
        (spit *log-file* msg :append true))
      (catch Exception e
        ;; Silently fail - don't break hook if logging fails
        nil))))

;; ============================================================================
;; Delimiter Error Detection and Repair Functions
;; ============================================================================

(defn delimiter-error?
  "Returns true if the string has a delimiter error specifically.
   Checks both that it's an :edamame/error and has delimiter info."
  [s]
  (try
    (e/parse-string-all s)
    false ; No error = no delimiter error
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))))

(defn parinfer-repair
  "Attempts to repair delimiter errors using parinfer-rust.
   Returns a map with:
   - :success - boolean indicating if repair was successful
   - :repaired-text - the repaired code (if successful)
   - :error - error message (if unsuccessful)"
  [s]
  (let [result (shell/sh "parinfer-rust"
                         "--mode" "indent"
                         "--language" "clojure"
                         "--output-format" "json"
                         :in s)
        exit-code (:exit result)]
    (if (zero? exit-code)
      (try
        (json/parse-string (:out result) true)
        (catch Exception e
          {:success false}))
      {:success false})))

(defn fix-delimiters
  "Takes a Clojure string and attempts to fix delimiter errors.
   Returns the repaired string if successful, false otherwise.
   If no delimiter errors exist, returns the original string."
  [s]
  (if (delimiter-error? s)
    (let [{:keys [text success]} (parinfer-repair s)]
      (when (and success text (not (delimiter-error? text)))
        text))
    s))

;; ============================================================================
;; Claude Code Hook Functions
;; ============================================================================

(defn clojure-file? [file-path]
  (some #(clojure.string/ends-with? file-path %)
        [".clj" ".cljs" ".cljc" ".bb" ".edn"]))

(defn backup-path
  "Generate deterministic backup file path for a given file and session"
  [file-path session-id]
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        session-dir (str "claude-hook-backup-" session-id)
        ;; Remove leading / or drive letter (C:) to make relative
        relative-path (clojure.string/replace-first file-path #"^[A-Za-z]:|^/" "")]
    (.getPath (clojure.java.io/file tmp-dir session-dir relative-path))))

(defn backup-file
  "Backup file to temp location, returns backup path"
  [file-path session-id]
  (let [backup (backup-path file-path session-id)
        backup-file (clojure.java.io/file backup)
        content (slurp file-path)]
    ;; Ensure parent directories exist
    (.mkdirs (.getParentFile backup-file))
    (spit backup content)
    backup))

(defn restore-file
  "Restore file from backup and delete backup"
  [file-path backup-path]
  (when (.exists (clojure.java.io/file backup-path))
    (let [backup-content (slurp backup-path)]
      (spit file-path backup-content)
      (clojure.java.io/delete-file backup-path)
      true)))

(defn delete-backup
  "Delete backup file if it exists"
  [backup-path]
  (when (.exists (clojure.java.io/file backup-path))
    (clojure.java.io/delete-file backup-path)))

(defn process-pre-write
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

(defn process-pre-edit
  "Backup file before Edit operation"
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

(defn process-post-edit
  "Check edited file and restore from backup if unfixable delimiter errors"
  [{:keys [tool_input tool_response session_id]}]
  (let [{:keys [file_path]} tool_input
        backup (backup-path file_path session_id)]
    (log-msg "PostEdit:" file_path)
    (if-not (and (clojure-file? file_path) tool_response)
      nil
      (let [file-content (slurp file_path)]
        (if (delimiter-error? file-content)
          (do
            (log-msg "  Delimiter error detected, attempting fix")
            (if-let [fixed-content (fix-delimiters file-content)]
              (do
                (log-msg "  Fix successful, applying fix and deleting backup")
                (spit file_path fixed-content)
                (delete-backup backup)
                {:hookSpecificOutput
                 {:hookEventName "PostToolUse"
                  :additionalContext (str "Auto-fixed delimiter errors in " file_path)}})
              (do
                (log-msg "  Fix failed, restoring from backup:" backup)
                (restore-file file_path backup)
                {:decision "block"
                 :reason (str "Delimiter errors could not be auto-fixed. File was restored from backup to previous state: " file_path)
                 :hookSpecificOutput {:hookEventName "PostToolUse"}})))
          (do
            (log-msg "  No delimiter errors, deleting backup")
            (delete-backup backup)
            nil))))))

(defn process-hook
  [hook-input]
  (let [hook-event (:hook_event_name hook-input)
        tool-name (:tool_name hook-input)]
    (cond
      (and (= hook-event "PreToolUse") (= tool-name "Write"))
      (process-pre-write hook-input)

      (and (= hook-event "PreToolUse") (= tool-name "Edit"))
      (process-pre-edit hook-input)

      (and (= hook-event "PostToolUse") (= tool-name "Edit"))
      (process-post-edit hook-input)

      :else
      (when (= hook-event "PreToolUse")
        {:hookSpecificOutput
         {:hookEventName "PreToolUse"
          :permissionDecision "allow"}}))))

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

(apply -main *command-line-args*)
