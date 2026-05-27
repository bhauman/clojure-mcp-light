(babashka.deps/add-deps '{:deps {dev.weavejester/cljfmt {:mvn/version "0.15.5"}
                                 parinferish/parinferish {:mvn/version "0.8.0"}}})

(ns clojure-mcp-light.codex-hook
  "Codex hook for delimiter error detection and repair."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure-mcp-light.claude-hook :as hook]
            [clojure-mcp-light.stats :as stats]
            [clojure-mcp-light.tmp :as tmp]
            [taoensso.timbre :as timbre]))

;; ============================================================================
;; CLI
;; ============================================================================

(defn usage []
  (str "clj-paren-repair-codex-hook - Codex hook for Clojure delimiter repair\n"
       "\n"
       "Usage: clj-paren-repair-codex-hook [OPTIONS]\n"
       "\n"
       "Options:\n"
       "      --cljfmt              Enable cljfmt formatting on files after apply_patch\n"
       "      --no-revert           Disable automatic file revert on unfixable delimiter errors\n"
       "      --stats               Enable statistics tracking for delimiter events\n"
       "                            (default: ~/.clojure-mcp-light/stats.log)\n"
       "      --stats-file PATH     Path to stats file (only used when --stats is enabled)\n"
       "      --log-level LEVEL     Set log level for file logging\n"
       "                            Levels: trace, debug, info, warn, error, fatal, report\n"
       "      --log-file PATH       Path to log file (default: ./.clojure-mcp-light-hooks.log)\n"
       "  -h, --help                Show this help message"))

;; ============================================================================
;; apply_patch parsing
;; ============================================================================

(def ^:private directive-prefixes
  [["*** Update File: "  :update]
   ["*** Move to: "      :move-to]
   ["*** Add File: "     :add]
   ["*** Delete File: "  :delete]])

(defn- parse-line
  [line]
  (some (fn [[prefix kind]]
          (when (string/starts-with? line prefix)
            (let [path (string/trim (subs line (count prefix)))]
              (when-not (string/blank? path)
                {:directive kind :path path}))))
        directive-prefixes))

(defn apply-patch-file-paths
  "Extract affected file paths from a Codex apply_patch command string,
  in encounter order and including Move-to destinations."
  [command]
  (->> (string/split-lines (or command ""))
       (keep parse-line)
       (map :path)
       distinct
       vec))

(defn- parse-ops
  "Group parsed directives into ordered ops. An `:update` immediately
  followed by `:move-to` is folded into a single `:move` op."
  [directives]
  (loop [ds directives
         ops []]
    (if-let [d (first ds)]
      (case (:directive d)
        :update
        (if (= :move-to (:directive (second ds)))
          (recur (drop 2 ds)
                 (conj ops {:op :move :from (:path d) :to (:path (second ds))}))
          (recur (rest ds) (conj ops {:op :update :path (:path d)})))
        :add     (recur (rest ds) (conj ops {:op :add :path (:path d)}))
        :delete  (recur (rest ds) (conj ops {:op :delete :path (:path d)}))
        :move-to (recur (rest ds) ops))
      ops)))

(defn resolve-session-path
  "Resolve a hook file path against the Codex session cwd."
  [cwd file-path]
  (let [file (io/file file-path)]
    (-> (if (.isAbsolute file)
          file
          (io/file (or cwd (System/getProperty "user.dir")) file-path))
        fs/absolutize
        fs/normalize
        str)))

(defn- resolve-op-paths
  [cwd op]
  (let [resolve* (fn [p] (some->> p (resolve-session-path cwd)))]
    (cond-> op
      (:path op) (update :path resolve*)
      (:from op) (update :from resolve*)
      (:to op)   (update :to resolve*))))

(defn apply-patch-ops
  "Parse a Codex apply_patch hook input into resolved structured ops."
  [{:keys [cwd tool_input]}]
  (let [directives (->> (string/split-lines (or (:command tool_input) ""))
                        (keep parse-line))]
    (mapv #(resolve-op-paths cwd %) (parse-ops directives))))

(defn- op-source
  "Pre-patch path whose content should be backed up. nil for `:add`."
  [op]
  (case (:op op)
    :update (:path op)
    :move   (:from op)
    :delete (:path op)
    :add    nil))

(defn- op-target
  "Post-patch path that should be repaired/formatted. nil for `:delete`."
  [op]
  (case (:op op)
    :update (:path op)
    :move   (:to op)
    :add    (:path op)
    :delete nil))

;; ============================================================================
;; Codex Hook Functions
;; ============================================================================

(defn- backup-source!
  [path session-id]
  (when (and path (fs/exists? path) (hook/clojure-file? path))
    (try
      (let [backup (hook/backup-file path session-id)]
        (timbre/debug "  Created backup:" backup)
        backup)
      (catch Exception e
        (timbre/warn "  Backup failed for" path ":" (.getMessage e))
        nil))))

(defn- backup-ops!
  [ops session-id]
  (doseq [op ops]
    (backup-source! (op-source op) session-id)))

(defn- delete-op-backups!
  [ops session-id]
  (doseq [op ops
          :let [src (op-source op)]
          :when src]
    (let [backup (tmp/backup-path {:session-id session-id} src)]
      (when (fs/exists? backup)
        (hook/delete-backup backup)))))

(defn- revert-op!
  "Attempt to undo `op` from its backup. Returns one of:
  :restored  – file content restored to its pre-patch state
  :deleted   – new file removed (was added by apply_patch)
  :no-backup – wanted to restore but no usable backup exists
  :skipped   – revert disabled, or op has nothing to undo"
  [op session-id]
  (if-not hook/*enable-revert*
    :skipped
    (case (:op op)
      :update
      (let [backup (tmp/backup-path {:session-id session-id} (:path op))]
        (if (fs/exists? backup)
          (do (hook/restore-file (:path op) backup) :restored)
          :no-backup))

      :move
      (let [backup (tmp/backup-path {:session-id session-id} (:from op))]
        (if (fs/exists? backup)
          (do (hook/restore-file (:from op) backup)
              (fs/delete-if-exists (:to op))
              :restored)
          :no-backup))

      :add
      (do (fs/delete-if-exists (:path op))
          :deleted)

      :delete :skipped)))

(defn- failure-reason
  [failures outcomes]
  (let [by-outcome (group-by :outcome outcomes)
        paths-of   (fn [k] (map :file-path (by-outcome k)))
        restored   (paths-of :restored)
        deleted    (paths-of :deleted)
        kept       (concat (paths-of :no-backup) (paths-of :skipped))]
    (str "Delimiter errors could not be auto-fixed in "
         (string/join ", " (map :file-path failures))
         (when (seq restored)
           (str ". Restored from backup: " (string/join ", " restored)))
         (when (seq deleted)
           (str ". Removed newly added files: " (string/join ", " deleted)))
         (when (seq kept)
           (str ". Left in place (no backup or revert disabled): "
                (string/join ", " kept))))))

(defn- process-post-apply-patch!
  [ops session-id tool-response]
  (let [exit-code (:exit_code tool-response)
        patch-applied? (or (nil? exit-code) (zero? exit-code))
        repair-ops (when patch-applied?
                     (filter (fn [op]
                               (when-let [t (op-target op)]
                                 (and (fs/exists? t)
                                      (hook/clojure-file? t))))
                             ops))
        results (doall
                 (for [op repair-ops
                       :let [target (op-target op)]]
                   (assoc (hook/fix-and-format-file!
                           target hook/*enable-cljfmt* "PostToolUse:apply_patch")
                          :op op
                          :file-path target)))
        failures (remove :success results)]
    (try
      (when (seq failures)
        (let [outcomes (doall
                        (for [{:keys [op file-path]} failures]
                          {:file-path file-path
                           :outcome (revert-op! op session-id)}))]
          {:decision "block"
           :reason (failure-reason failures outcomes)
           :hookSpecificOutput
           {:hookEventName "PostToolUse"
            :additionalContext "There are delimiter errors in one or more Clojure files touched by apply_patch."}}))
      (finally
        (delete-op-backups! ops session-id)))))

(defmulti process-codex-hook
  (fn [hook-input]
    [(:hook_event_name hook-input) (:tool_name hook-input)]))

(defmethod process-codex-hook :default [_] nil)

(defmethod process-codex-hook ["PreToolUse" "apply_patch"]
  [input]
  (when hook/*enable-revert*
    (backup-ops! (apply-patch-ops input) (:session_id input)))
  nil)

(defmethod process-codex-hook ["PostToolUse" "apply_patch"]
  [input]
  (process-post-apply-patch! (apply-patch-ops input)
                             (:session_id input)
                             (:tool_response input)))

(defmethod process-codex-hook ["PreToolUse" "mcp__morph-mcp__edit_file"]
  [input]
  (hook/process-hook input))

(defmethod process-codex-hook ["PostToolUse" "mcp__morph-mcp__edit_file"]
  [input]
  (hook/process-hook input))

(defn -main [& args]
  (let [options (hook/handle-cli-args args usage)]
    (hook/configure-logging! options)
    (binding [hook/*enable-cljfmt* (:cljfmt options)
              hook/*enable-revert* (not (:no-revert options))
              stats/*enable-stats* (:stats options)
              stats/*stats-file-path* (stats/normalize-stats-path (:stats-file options))]
      (hook/run-hook! process-codex-hook))))
