(ns clojure-mcp-light.tmp
  "Unified temporary file management for Claude Code sessions.

  Provides consistent temp file paths for backups, nREPL sessions, and other
  temporary files with automatic cleanup support via SessionEnd hooks."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ============================================================================
;; Config & Helpers
;; ============================================================================

(defn user-id
  "Get current username from system properties."
  []
  (or (System/getProperty "user.name") "unknown"))

(defn hostname
  "Get hostname of the current machine."
  []
  (try
    (.. java.net.InetAddress getLocalHost getHostName)
    (catch Exception _
      "unknown-host")))

(defn runtime-base-dir
  "Get base directory for runtime temporary files.
  Prefers XDG_RUNTIME_DIR if present, otherwise falls back to java.io.tmpdir."
  []
  (or (System/getenv "XDG_RUNTIME_DIR")
      (System/getProperty "java.io.tmpdir")))

(defn sanitize
  "Sanitize a string for safe use in filesystem paths.
  Replaces non-alphanumeric characters (except ._-) with underscores,
  and collapses multiple underscores into one."
  [s]
  (-> s
      (str/replace #"[^\p{Alnum}._-]+" "_")
      (str/replace #"_{2,}" "_")))

(defn sha1
  "Compute SHA-1 hash of a string, returning hex digest."
  [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")]
    (.update md (.getBytes s))
    (format "%040x" (BigInteger. 1 (.digest md)))))

(defn project-root-path
  "Get the project root path (current working directory).
  Returns absolute normalized path."
  []
  (-> (System/getProperty "user.dir")
      fs/absolutize
      fs/normalize
      str))

(defn editor-scope-id
  "Get editor session scope identifier with fallback strategy.

  Tries in order:
  1. CML_CLAUDE_CODE_SESSION_ID environment variable
  2. Parent process ID with start time (ppid-{pid}-{startInstant})
  3. Literal string 'global' as last resort

  The PPID approach provides a stable identifier for the Claude Code session
  lifetime even when the session ID environment variable is not set."
  []
  (or (System/getenv "CML_CLAUDE_CODE_SESSION_ID")
      (try
        (when-let [ph (some-> (java.lang.ProcessHandle/current) .parent (.orElse nil))]
          (let [pid (.pid ph)
                start (some-> (.info ph) .startInstant (.orElse nil) str)]
            (str "ppid-" pid (when start (str "-" start)))))
        (catch Exception _
          nil))
      "global"))

;; ============================================================================
;; Unified Session/Project Root
;; ============================================================================

(defn session-root
  "Returns the unified root directory for this Claude Code session + project.

  Structure:
    {runtime-dir}/claude-code/{user}/{hostname}/{session-id}/proj-{hash}/

  Parameters:
  - :project-root - Optional project root path (defaults to current directory)
  - :session-id   - Optional session ID (defaults to editor-scope-id)

  The project is identified by SHA-1 hash of its absolute path for stability
  across different session invocations."
  [{:keys [project-root session-id]}]
  (let [runtime (runtime-base-dir)
        sess    (or session-id (editor-scope-id))
        proj    (or project-root (project-root-path))
        proj-id (sha1 proj)]
    (str (fs/path runtime
                  "claude-code"
                  (sanitize (user-id))
                  (sanitize (hostname))
                  ;; one dir per editor session
                  (sanitize sess)
                  ;; one subdir per project root (stable across runs)
                  (str "proj-" proj-id)))))

(defn ensure-dir!
  "Ensure directory exists, creating it if necessary.
  Returns the path string."
  [p]
  (let [f (io/file p)]
    (.mkdirs f)
    p))

;; ============================================================================
;; Convenience Subpaths
;; ============================================================================

(defn backups-dir
  "Get the backups directory for this session/project context.
  Creates directory if it doesn't exist."
  [ctx]
  (ensure-dir! (str (fs/path (session-root ctx) "backups"))))

(defn nrepl-dir
  "Get the nREPL directory for this session/project context.
  Creates directory if it doesn't exist."
  [ctx]
  (ensure-dir! (str (fs/path (session-root ctx) "nrepl"))))

;; ============================================================================
;; Specific File Paths
;; ============================================================================

(defn nrepl-session-file
  "Get path to nREPL session file.
  This file stores the persistent nREPL session ID."
  [ctx]
  (str (fs/path (nrepl-dir ctx) "session.edn")))

(defn backup-path
  "Get deterministic backup path for a given absolute file path.

  The backup preserves the relative path structure under the backups directory,
  stripping only the root component to make it portable across filesystems."
  [ctx ^String absolute-file]
  (let [path (fs/path absolute-file)
        normalized (fs/normalize (fs/absolutize path))
        ;; Get all name elements (excludes root component)
        rel (str (.subpath ^java.nio.file.Path normalized
                          0
                          (.getNameCount ^java.nio.file.Path normalized)))]
    (str (fs/path (backups-dir ctx) rel))))
