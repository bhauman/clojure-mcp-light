(ns clojure-mcp-light.nrepl-eval
  "nREPL client implementation with automatic delimiter repair and timeout handling"
  (:require [bencode.core :as b]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure-mcp-light.delimiter-repair :refer [fix-delimiters]]))

;; ============================================================================
;; nREPL client implementation
;; ============================================================================

(defn bytes->str [x]
  (if (bytes? x) (String. (bytes x))
      (str x)))

(defn read-msg [msg]
  (let [res (zipmap (map keyword (keys msg))
                    (map #(if (bytes? %)
                            (String. (bytes %))
                            %)
                         (vals msg)))
        res (if-let [status (:status res)]
              (assoc res :status (mapv bytes->str status))
              res)
        res (if-let [status (:sessions res)]
              (assoc res :sessions (mapv bytes->str status))
              res)]
    res))

(defn read-reply [in session id]
  (loop []
    (let [msg (read-msg (b/read-bencode in))]
      (if (and (= (:session msg) session)
               (= (:id msg) id))
        msg
        (recur)))))

(defn coerce-long [x]
  (if (string? x) (Long/parseLong x) x))

(defn next-id []
  (str (java.util.UUID/randomUUID)))

;; Session file I/O utilities

(defn session-file-path []
  "Returns path to nrepl session file. Uses Claude Code session-specific
   tmp directory if CML_CLAUDE_CODE_SESSION_ID is set, otherwise uses
   .nrepl-session in current directory."
  (if-let [cc-session-id (System/getenv "CML_CLAUDE_CODE_SESSION_ID")]
    (let [tmp-dir (System/getProperty "java.io.tmpdir")
          session-dir (str "cc-" cc-session-id)
          parent-dir (java.io.File. tmp-dir session-dir)]
      (.getPath (java.io.File. parent-dir "nrepl-session")))
    ".nrepl-session"))

(defn slurp-nrepl-session []
  "Read session ID from nrepl session file. Returns nil if file doesn't exist or on error."
  (try
    (let [session-file (session-file-path)]
      (when (.exists (java.io.File. session-file))
        (str/trim (slurp session-file))))
    (catch Exception _
      nil)))

(defn spit-nrepl-session [session-id]
  "Write session ID to nrepl session file."
  (let [session-file (session-file-path)
        file (java.io.File. session-file)]
    ;; Ensure parent directories exist
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit session-file (str session-id "\n"))))

(defn delete-nrepl-session []
  "Delete nrepl session file if it exists."
  (let [f (java.io.File. (session-file-path))]
    (when (.exists f)
      (.delete f))))

;; Session validation utilities

(defn get-active-sessions
  "Get list of active session IDs from nREPL server.
   Returns nil if unable to connect or on error."
  [host port]
  (try
    (let [s (java.net.Socket. (or host "localhost") (coerce-long port))
          out (.getOutputStream s)
          in (java.io.PushbackInputStream. (.getInputStream s))
          id (next-id)
          _ (b/write-bencode out {"op" "ls-sessions" "id" id})
          response (read-msg (b/read-bencode in))]
      (.close s)
      (:sessions response))
    (catch Exception _
      nil)))

(defn validate-session
  "Check if session-id is still valid on the nREPL server.
   Returns the session-id if valid, nil otherwise.
   If invalid, deletes the session file."
  [session-id host port]
  (when session-id
    (if-let [active-sessions (get-active-sessions host port)]
      (if (some #{session-id} active-sessions)
        session-id
        (do
          (delete-nrepl-session)
          nil))
      ;; If we can't check (server down?), assume session is valid
      session-id)))

(defn eval-expr
  "Execute :expr in nREPL on given :host (defaults to localhost)
  and :port. Returns map with :vals. Prints any output to *out*.

  :vals is a vector with eval results from all the top-level
  forms in the :expr. See the README for an example.

  Uses persistent sessions: reuses existing session ID from .nrepl-session
  or creates a new one if none exists. Session persists across invocations."
  [{:keys [host port expr]}]
  (let [fixed-expr (or (fix-delimiters expr) expr)
        s (java.net.Socket. (or host "localhost") (coerce-long port))
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        ;; Try to reuse existing session or create new one
        existing-session (slurp-nrepl-session)
        validated-session (validate-session existing-session host port)
        session (if validated-session
                  validated-session
                  (let [id (next-id)
                        _ (b/write-bencode out {"op" "clone" "id" id})
                        {new-session :new-session} (read-msg (b/read-bencode in))]
                    (spit-nrepl-session new-session)
                    new-session))
        eval-id (next-id)
        _ (b/write-bencode out {"op" "eval" "code" fixed-expr "id" eval-id "session" session})]
    (loop [m {:vals [] :responses []}]
      (let [{:keys [status out value err] :as resp} (read-reply in session eval-id)]
        (when out
          (print out)
          (flush))
        (when err
          (binding [*out* *err*]
            (print err)
            (flush)))
        (let [m (cond-> (update m :responses conj resp)
                  value
                  (update :vals conj value))]
          (if (some #{"done"} status)
            (do
              ;; Close socket but keep session alive on server
              (.close s)
              m)
            (recur m)))))))

;; Utility functions

(defn now-ms [] (System/currentTimeMillis))

(defn ->uuid [] (str (java.util.UUID/randomUUID)))

(defn slurp-nrepl-port []
  (when (.exists (java.io.File. ".nrepl-port"))
    (parse-long (str/trim (slurp ".nrepl-port")))))

;; Timeout and interrupt handling

(defn try-read-msg
  "Try to read a message from the input stream with a timeout in milliseconds.
  Returns nil if timeout occurs, otherwise returns the decoded message."
  [socket in timeout-ms]
  (try
    (.setSoTimeout socket timeout-ms)
    (read-msg (b/read-bencode in))
    (catch java.net.SocketTimeoutException _
      nil)))

(defn eval-expr-with-timeout
  "Evaluate expression with timeout support and interrupt handling.
  If timeout-ms is exceeded, sends an interrupt to the nREPL server.

  Uses persistent sessions: reuses existing session ID from .nrepl-session
  or creates a new one if none exists. Session persists across invocations."
  [{:keys [host port expr timeout-ms] :or {timeout-ms 120000}}]
  (let [fixed-expr (or (fix-delimiters expr) expr)
        s (java.net.Socket. (or host "localhost") (coerce-long port))
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        ;; Try to reuse existing session or create new one
        existing-session (slurp-nrepl-session)
        validated-session (validate-session existing-session host port)
        session (if validated-session
                  validated-session
                  (let [clone-id (next-id)
                        _ (b/write-bencode out {"op" "clone" "id" clone-id})
                        {new-session :new-session} (read-msg (b/read-bencode in))]
                    (spit-nrepl-session new-session)
                    new-session))
        eval-id (next-id)
        deadline (+ (now-ms) timeout-ms)
        _ (b/write-bencode out {"op" "eval"
                                "code" fixed-expr
                                "id" eval-id
                                "session" session})]
    (loop [m {:vals [] :responses [] :interrupted false}]
      (let [remaining (max 0 (- deadline (now-ms)))]
        (if (pos? remaining)
          ;; Wait up to 250ms at a time for responses so we can honor timeout
          (if-let [resp (try-read-msg s in (min remaining 250))]
            (do
              ;; Handle output
              (when-let [out-str (:out resp)]
                (print out-str)
                (flush))
              (when-let [err-str (:err resp)]
                (binding [*out* *err*]
                  (print err-str)
                  (flush)))
              ;; Collect values
              (let [m (cond-> (update m :responses conj resp)
                        (:value resp)
                        (update :vals conj (:value resp)))]
                ;; Stop when server says we're done
                (if (some #{"done"} (:status resp))
                  (do
                    ;; Close socket but keep session alive on server
                    (.close s)
                    m)
                  (recur m))))
            ;; No message this tick; loop again until timeout
            (recur m))
          ;; Timeout hit — send interrupt
          (do
            (println "\n⚠️  Timeout hit, sending nREPL :interrupt …")
            (b/write-bencode out {"op" "interrupt"
                                  "session" session
                                  "interrupt-id" eval-id})
            ;; Read a few responses to observe the interruption
            (loop [i 0
                   result (assoc m :interrupted true)]
              (if (< i 20)
                (if-let [resp (try-read-msg s in 250)]
                  (do
                    (when-let [out-str (:out resp)]
                      (print out-str)
                      (flush))
                    (when-let [err-str (:err resp)]
                      (binding [*out* *err*]
                        (print err-str)
                        (flush)))
                    (if (or (some #{"interrupted"} (:status resp))
                            (some #{"done"} (:status resp)))
                      (do
                        ;; Close socket but keep session alive on server
                        (.close s)
                        (println "✋ Evaluation interrupted.")
                        result)
                      (recur (inc i) (update result :responses conj resp))))
                  (recur (inc i) result))
                (do
                  ;; Close socket but keep session alive on server
                  (.close s)
                  (println "✋ Evaluation interrupted.")
                  result)))))))))

;; Main evaluation function with formatted output

(defn eval-and-print
  "Evaluate expression and print results with formatting.
  Each result is printed as => <value> with dividing lines between them.
  If timeout-ms is provided, will use timeout/interrupt handling."
  [{:keys [host port expr timeout-ms] :as opts}]
  (let [result (if timeout-ms
                 (eval-expr-with-timeout opts)
                 (eval-expr opts))
        vals (:vals result)]
    (doseq [v vals]
      (println (str "=> " v))
      (println "*============================*"))
    result))

;; ============================================================================
;; Command-line interface
;; ============================================================================

(def cli-options
  [["-p" "--port PORT" "nREPL port (default: from .nrepl-port or NREPL_PORT env)"
    :parse-fn parse-long
    :validate [#(> % 0) "Must be a positive number"]]
   ["-H" "--host HOST" "nREPL host (default: 127.0.0.1 or NREPL_HOST env)"]
   ["-t" "--timeout MILLISECONDS" "Timeout in milliseconds (default: 120000)"
    :parse-fn parse-long
    :validate [#(> % 0) "Must be a positive number"]]
   ["-r" "--reset-session" "Reset the persistent nREPL session"]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (str/join \newline
            ["clj-nrepl-eval - Evaluate Clojure code via nREPL"
             ""
             "Usage: clj-nrepl-eval [OPTIONS] CODE"
             "       clj-nrepl-eval --reset-session"
             ""
             "Options:"
             options-summary
             ""
             "Session Persistence:"
             "  Sessions are persistent by default. State (vars, namespaces, loaded"
             "  libraries) persists across invocations until the nREPL server restarts"
             "  or --reset-session is used."
             ""
             "Environment Variables:"
             "  NREPL_PORT    Default nREPL port"
             "  NREPL_HOST    Default nREPL host"
             ""
             "Examples:"
             "  clj-nrepl-eval \"(+ 1 2 3)\""
             "  clj-nrepl-eval --port 7888 \"(println \\\"Hello\\\")\""
             "  clj-nrepl-eval --timeout 5000 \"(Thread/sleep 10000)\""
             "  clj-nrepl-eval --reset-session"
             "  clj-nrepl-eval --reset-session \"(def x 1)\""]))

(defn error-msg [errors]
  (str "Error parsing command line:\n\n"
       (str/join \newline errors)))

(defn get-port
  "Get port from options, environment, or .nrepl-port file.
  Returns nil if no port can be found."
  [opts]
  (or (:port opts)
      (some-> (System/getenv "NREPL_PORT") parse-long)
      (slurp-nrepl-port)))

(defn get-host
  "Get host from options or environment"
  [opts]
  (or (:host opts)
      (System/getenv "NREPL_HOST")
      "127.0.0.1"))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (println (usage summary))

      errors
      (do
        (binding [*out* *err*]
          (println (error-msg errors))
          (println)
          (println (usage summary)))
        (System/exit 1))

      ;; Handle --reset-session flag
      (:reset-session options)
      (do
        (delete-nrepl-session)
        (println "Session reset: .nrepl-session file deleted")
        ;; If code is provided, continue to evaluate it with new session
        (when (seq arguments)
          (let [port (get-port options)
                expr (first arguments)]
            (if port
              (eval-and-print {:host (get-host options)
                               :port port
                               :expr expr
                               :timeout-ms (:timeout options)})
              (do
                (binding [*out* *err*]
                  (println "Error: No nREPL port found")
                  (println "Provide port via --port, NREPL_PORT env var, or .nrepl-port file"))
                (System/exit 1))))))

      (empty? arguments)
      (do
        (binding [*out* *err*]
          (println "Error: No code provided")
          (println)
          (println (usage summary)))
        (System/exit 1))

      :else
      (let [port (get-port options)
            expr (first arguments)]
        (if port
          (eval-and-print {:host (get-host options)
                           :port port
                           :expr expr
                           :timeout-ms (:timeout options)})
          (do
            (binding [*out* *err*]
              (println "Error: No nREPL port found")
              (println "Provide port via --port, NREPL_PORT env var, or .nrepl-port file"))
            (System/exit 1)))))))
