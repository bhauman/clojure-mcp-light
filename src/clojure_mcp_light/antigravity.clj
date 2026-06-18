(ns clojure-mcp-light.antigravity
  "Antigravity hook adapter.

  Antigravity (https://antigravity.google/docs/hooks) supports command hooks,
  but with a wire format that differs from Claude Code / Codex:

   - camelCase fields: toolCall.name, toolCall.args, conversationId,
     workspacePaths (an array), transcriptPath, stepIdx
   - no hook_event_name in the payload; the event is passed via --event
   - PreToolUse can only allow/deny (it cannot rewrite the tool input)
   - PostToolUse receives no toolCall/path, only stepIdx + error, and must
     return {}
   - Stop returns {decision ...} ({} allows the agent to stop)

  Strategy: repair on disk after the edit. Since PostToolUse doesn't carry the
  edited path, PreToolUse stashes it keyed by (conversationId, stepIdx) and
  PostToolUse looks it up. Files that can't be repaired are left exactly as the
  model wrote them - no backup, no revert. Reverting a file out from under the
  agent (which PostToolUse can't even explain, since its output is {}) is more
  confusing than leaving a delimiter error in place."
  (:require [clojure-mcp-light.hook :as hook]
            [clojure-mcp-light.tmp :as tmp]
            [babashka.fs :as fs]
            [clojure.string :as string]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]))

(def edit-tools
  "Antigravity file-edit tools whose single target file lives in args.TargetFile."
  #{"write_to_file" "replace_file_content" "multi_replace_file_content"})

(defn resolve-target
  "Resolve an Antigravity TargetFile against the workspace roots. Absolute paths
  pass through; relative paths resolve against the first workspace root."
  [target workspace-paths]
  (when (and target (seq (str target)))
    (let [p (fs/path target)
          abs (if (fs/absolute? p)
                p
                (fs/path (or (first workspace-paths) ".") target))]
      (str (fs/normalize (fs/absolutize abs))))))

(defn- pending-path
  "Temp file used to hand the edited path from PreToolUse to PostToolUse."
  [conversation-id step-idx]
  (fs/path (tmp/session-root {:session-id (or conversation-id "global")})
           "antigravity-pending"
           (str step-idx ".path")))

(defn- stash-target! [conversation-id step-idx file-path]
  (let [pf (pending-path conversation-id step-idx)]
    (fs/create-dirs (fs/parent pf))
    (spit (str pf) file-path :encoding "UTF-8")))

(defn- read-target [conversation-id step-idx]
  (let [pf (pending-path conversation-id step-idx)]
    (when (fs/exists? pf)
      (string/trim (slurp (str pf) :encoding "UTF-8")))))

(defn- clear-target! [conversation-id step-idx]
  (fs/delete-if-exists (pending-path conversation-id step-idx)))

(defn pre-tool-use
  "Before an edit tool runs we have the target path but can't repair yet (the
  file isn't written, and we can't rewrite the tool input). Stash the path so
  PostToolUse can repair on disk, then allow the edit."
  [{:keys [toolCall stepIdx conversationId workspacePaths]}]
  (let [tool (:name toolCall)
        target (resolve-target (get-in toolCall [:args :TargetFile]) workspacePaths)]
    (when (and (contains? edit-tools tool) target (hook/clojure-file? target))
      (stash-target! conversationId stepIdx target)
      (timbre/debug "Antigravity PreToolUse: stashed" target "for step" stepIdx))
    {:decision "allow"}))

(defn post-tool-use
  "After the edit the file is on disk. Look up the stashed path and repair it if
  it's a Clojure file. Unfixable files are left as the model wrote them."
  [{:keys [stepIdx conversationId]}]
  (let [target (read-target conversationId stepIdx)]
    (try
      (when (and target (fs/exists? target) (hook/clojure-file? target))
        (let [result (hook/fix-and-format-file! target hook/*enable-cljfmt* "Antigravity:PostToolUse")]
          (timbre/debug "Antigravity PostToolUse:" target
                        "repaired?" (:delimiter-fixed result)
                        "success?" (:success result))))
      (finally
        (clear-target! conversationId stepIdx))))
  {})

(defn stop
  "End of the execution loop. Antigravity has no SessionStart/SessionEnd, so the
  stale temp-dir sweep runs here. Returns {} to let the agent stop."
  [{:keys [conversationId]}]
  (try
    (let [report (tmp/cleanup-stale-sessions! {:session-id conversationId})]
      (timbre/info "Antigravity Stop: swept stale sessions, deleted"
                   (count (:deleted report))))
    (catch Exception e
      (timbre/error "Antigravity Stop sweep error:" (.getMessage e))))
  {})

(defn process
  "Dispatch a parsed Antigravity payload by event name. Unhandled events return
  a safe empty response."
  [event input]
  (case event
    "PreToolUse"  (pre-tool-use input)
    "PostToolUse" (post-tool-use input)
    "Stop"        (stop input)
    {}))

(defn run-hook!
  "Entry point for Antigravity mode. Reads the camelCase payload from stdin,
  dispatches on `event`, prints the response JSON, and exits 0. Fails safe: on
  any error it emits a non-blocking response so the agent is never wedged."
  [event]
  (try
    (let [input-json (slurp *in*)
          _ (timbre/debug "Antigravity INPUT [" event "]:" input-json)
          input (json/parse-string input-json true)
          response (process event input)]
      (timbre/debug "Antigravity OUTPUT:" (json/generate-string response))
      (println (json/generate-string response))
      (System/exit 0))
    (catch Exception e
      (timbre/error "Antigravity hook error:" (.getMessage e))
      (println (json/generate-string (if (= event "PreToolUse")
                                       {:decision "allow"}
                                       {})))
      (System/exit 0))))
