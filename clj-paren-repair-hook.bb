#!/usr/bin/env bb

(require '[edamame.core :as e]
         '[clojure.java.shell :as shell]
         '[cheshire.core :as json])

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
        (let [parsed (json/parse-string (:out result) true)
              success (:success parsed)
              text (:text parsed)
              error (:error parsed)]
          (if success
            {:success true
             :repaired-text text
             :error nil}
            {:success false
             :repaired-text nil
             :error (or error "Parinfer repair failed")}))
        (catch Exception e
          {:success false
           :repaired-text nil
           :error (str "Failed to parse parinfer output: " (.getMessage e))}))
      {:success false
       :repaired-text nil
       :error (str "parinfer-rust exited with code " exit-code ": " (:err result))})))

(defn fix-delimiters
  "Takes a Clojure string and attempts to fix delimiter errors.
   Returns the repaired string if successful, false otherwise.
   If no delimiter errors exist, returns the original string."
  [s]
  (if (delimiter-error? s)
    ;; Has delimiter error - try to repair
    (let [repair-result (parinfer-repair s)]
      (if (:success repair-result)
        ;; Parinfer succeeded - verify the repair
        (let [repaired-text (:repaired-text repair-result)]
          (if (delimiter-error? repaired-text)
            ;; Still has delimiter errors after repair
            false
            ;; Successfully repaired!
            repaired-text))
        ;; Parinfer failed
        false))
    ;; No delimiter errors - return original
    s))

;; ============================================================================
;; Claude Code Hook Functions
;; ============================================================================

(defn clojure-file? [file-path]
  (some #(clojure.string/ends-with? file-path %)
        [".clj" ".cljs" ".cljc" ".bb" ".edn"]))

(defn process-pre-write
  [hook-input]
  (let [tool-input (:tool_input hook-input)
        file-path (:file_path tool-input)
        content (:content tool-input)]

    (if-not (clojure-file? file-path)
      {:hookSpecificOutput
       {:hookEventName "PreToolUse"
        :permissionDecision "allow"}}

      (if (delimiter-error? content)
        (let [fixed-content (fix-delimiters content)]
          (if fixed-content
            {:hookSpecificOutput
             {:hookEventName "PreToolUse"
              :permissionDecision "allow"
              :permissionDecisionReason "Auto-fixed delimiter errors"
              :updatedInput {:file_path file-path
                             :content fixed-content}}}
            {:hookSpecificOutput
             {:hookEventName "PreToolUse"
              :permissionDecision "deny"
              :permissionDecisionReason "Delimiter errors found and could not be auto-fixed"}}))
        {:hookSpecificOutput
         {:hookEventName "PreToolUse"
          :permissionDecision "allow"}}))))

(defn process-post-edit
  [hook-input]
  (let [tool-input (:tool_input hook-input)
        tool-response (:tool_response hook-input)
        file-path (:file_path tool-input)]

    ;; PostToolUse only fires on success, so if we're here, the edit succeeded
    (when (and (clojure-file? file-path)
               tool-response)
      (let [file-content (slurp file-path)]
        (when (delimiter-error? file-content)
          (when-let [fixed-content (fix-delimiters file-content)]
            (spit file-path fixed-content)
            (binding [*out* *err*]
              (println "Auto-fixed delimiter errors in" file-path))))))

    nil))

(defn process-hook
  [hook-input]
  (let [hook-event (:hook_event_name hook-input)
        tool-name (:tool_name hook-input)]
    (cond
      (and (= hook-event "PreToolUse") (= tool-name "Write"))
      (process-pre-write hook-input)

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
          _ (spit "hook-logs/delimiter-hook-debug.log"
                  (str "INPUT: " input-json "\n")
                  :append true)
          hook-input (json/parse-string input-json true)
          response (process-hook hook-input)
          _ (spit "hook-logs/delimiter-hook-debug.log"
                  (str "OUTPUT: " (json/generate-string response) "\n\n")
                  :append true)]
      (when response
        (println (json/generate-string response)))
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println "Hook error:" (.getMessage e))
        (println "Stack trace:" (with-out-str (.printStackTrace e))))
      (System/exit 2))))

(apply -main *command-line-args*)
