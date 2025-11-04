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

(defn get-delimiter-error-info
  "Returns detailed information about a delimiter error, or nil if no error."
  [s]
  (try
    (e/parse-string-all s)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (when (and (= :edamame/error (:type data))
                   (contains? data :edamame/opened-delimiter))
          {:message (.getMessage ex)
           :error-location {:row (:row data) :col (:col data)}
           :opened-delimiter (:edamame/opened-delimiter data)
           :opened-location (:edamame/opened-delimiter-loc data)
           :expected-delimiter (:edamame/expected-delimiter data)})))))

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
  [hook-input]
  (let [tool-input (:tool_input hook-input)
        file-path (:file_path tool-input)
        content (:content tool-input)]

    (log-msg "PreWrite:" file-path)

    (if-not (clojure-file? file-path)
      (do
        (log-msg "  Skipping non-Clojure file")
        {:hookSpecificOutput
         {:hookEventName "PreToolUse"
          :permissionDecision "allow"}})

      (if (delimiter-error? content)
        (do
          (log-msg "  Delimiter error detected, attempting fix")
          (let [fixed-content (fix-delimiters content)]
            (if fixed-content
              (do
                (log-msg "  Fix successful, allowing write with updated content")
                {:hookSpecificOutput
                 {:hookEventName "PreToolUse"
                  :permissionDecision "allow"
                  :permissionDecisionReason "Auto-fixed delimiter errors"
                  :updatedInput {:file_path file-path
                                 :content fixed-content}}})
              (do
                (log-msg "  Fix failed, denying write")
                {:hookSpecificOutput
                 {:hookEventName "PreToolUse"
                  :permissionDecision "deny"
                  :permissionDecisionReason "Delimiter errors found and could not be auto-fixed"}}))))
        (do
          (log-msg "  No delimiter errors, allowing write")
          {:hookSpecificOutput
           {:hookEventName "PreToolUse"
            :permissionDecision "allow"}})))))

(defn process-pre-edit
  "Backup file before Edit operation"
  [hook-input]
  (let [tool-input (:tool_input hook-input)
        file-path (:file_path tool-input)
        session-id (:session_id hook-input)]

    (log-msg "PreEdit:" file-path)

    (if-not (clojure-file? file-path)
      ;; Not a Clojure file, allow without backup
      (do
        (log-msg "  Skipping non-Clojure file")
        {:hookSpecificOutput
         {:hookEventName "PreToolUse"
          :permissionDecision "allow"}})

      ;; Clojure file - create backup and allow
      (try
        (let [backup (backup-file file-path session-id)]
          (log-msg "  Created backup:" backup)
          {:hookSpecificOutput
           {:hookEventName "PreToolUse"
            :permissionDecision "allow"}})
        (catch Exception e
          ;; Backup failed, but still allow edit
          (log-msg "  Backup failed:" (.getMessage e))
          (binding [*out* *err*]
            (println "Warning: Failed to backup file:" (.getMessage e)))
          {:hookSpecificOutput
           {:hookEventName "PreToolUse"
            :permissionDecision "allow"}})))))

(defn process-post-edit
  "Check edited file and restore from backup if unfixable delimiter errors"
  [hook-input]
  (let [tool-input (:tool_input hook-input)
        tool-response (:tool_response hook-input)
        file-path (:file_path tool-input)
        session-id (:session_id hook-input)
        backup (backup-path file-path session-id)]

    (log-msg "PostEdit:" file-path)

    ;; PostToolUse only fires on success, so if we're here, the edit succeeded
    (when (and (clojure-file? file-path)
               tool-response)
      (let [file-content (slurp file-path)]
        (if (delimiter-error? file-content)
          ;; Has delimiter error - try to fix
          (do
            (log-msg "  Delimiter error detected, attempting fix")
            (if-let [fixed-content (fix-delimiters file-content)]
              ;; Successfully fixed
              (do
                (log-msg "  Fix successful, applying fix and deleting backup")
                (spit file-path fixed-content)
                (delete-backup backup)
                (binding [*out* *err*]
                  (println "Auto-fixed delimiter errors in" file-path)))
              ;; Could not fix - restore backup
              (do
                (log-msg "  Fix failed, restoring from backup:" backup)
                (restore-file file-path backup)
                (binding [*out* *err*]
                  (println "Warning: Delimiter errors could not be fixed, restored from backup:" file-path)))))
          ;; No delimiter error - just cleanup backup
          (do
            (log-msg "  No delimiter errors, deleting backup")
            (delete-backup backup)))))

    nil))

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
