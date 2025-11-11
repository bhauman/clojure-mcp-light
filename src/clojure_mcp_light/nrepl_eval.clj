(ns clojure-mcp-light.nrepl-eval
  "nREPL client implementation with automatic delimiter repair and timeout handling"
  (:require [bencode.core :as b]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure-mcp-light.delimiter-repair :refer [fix-delimiters]]
            [clojure-mcp-light.tmp :as tmp]))

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
    (if-let [raw (try (b/read-bencode in)
                      (catch Exception _ nil))]
      (let [msg (read-msg raw)]
        (if (and (= (:session msg) session)
                 (= (:id msg) id))
          msg
          (recur)))
      {:status ["eof"]})))

(defn coerce-long [x]
  (if (string? x) (Long/parseLong x) x))

(defn next-id []
  (str (java.util.UUID/randomUUID)))

(defn write-bencode-msg
  "Write bencode message to output stream and flush"
  [out msg]
  (b/write-bencode out msg)
  (.flush out))

;; Session file I/O utilities

(defn slurp-nrepl-session
  "Read session ID from nrepl session file for given host and port.
  Returns nil if file doesn't exist or on error."
  [host port]
  (try
    (let [ctx {}
          session-file (tmp/nrepl-target-file ctx {:host host :port port})]
      (when (.exists (java.io.File. session-file))
        (str/trim (slurp session-file))))
    (catch Exception _
      nil)))

(defn spit-nrepl-session
  "Write session ID to nrepl session file for given host and port."
  [session-id host port]
  (let [ctx {}
        session-file (tmp/nrepl-target-file ctx {:host host :port port})
        file (java.io.File. session-file)]
    ;; Ensure parent directories exist
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit session-file (str session-id "\n"))))

(defn delete-nrepl-session
  "Delete nrepl session file for given host and port if it exists."
  [host port]
  (let [ctx {}
        session-file (tmp/nrepl-target-file ctx {:host host :port port})
        f (java.io.File. session-file)]
    (when (.exists f)
      (.delete f))))

;; Session validation utilities

(defn validate-session-on-socket
  "Validate session ID on an open socket by checking ls-sessions.
   Returns the session-id if valid, nil otherwise."
  [out in session-id]
  (when session-id
    (let [id (next-id)
          _ (write-bencode-msg out {"op" "ls-sessions" "id" id})
          response (read-msg (b/read-bencode in))
          active-sessions (:sessions response)]
      (when (some #{session-id} active-sessions)
        session-id))))

(defn get-active-sessions
  "Get list of active session IDs from nREPL server.
   Returns nil if unable to connect or on error.
   Uses a 500ms timeout for connection and read operations."
  [host port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. (or host "localhost") (coerce-long port)) 500)
      (.setSoTimeout s 500)
      (let [out (java.io.BufferedOutputStream. (.getOutputStream s))
            in (java.io.PushbackInputStream. (.getInputStream s))
            id (next-id)
            _ (write-bencode-msg out {"op" "ls-sessions" "id" id})
            response (read-msg (b/read-bencode in))]
        (:sessions response)))
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
          (delete-nrepl-session host port)
          nil))
      ;; If we can't check (server down?), assume session is valid
      session-id)))

(defn validate-stored-connection
  "Validate a stored nREPL connection by checking if server is reachable
   and session is still active.

   Parameters:
   - :host       - Host string
   - :port       - Port number
   - :session-id - Session ID to validate

   Returns map with :status key:
   - {:status :active :host ... :port ... :session-id ...} if connection is valid
   - {:status :invalid :host ... :port ... :reason ...} if connection failed

   Does not clean up stale session files to preserve session persistence across
   server restarts. Use --reset-session to explicitly clean up sessions."
  [{:keys [host port session-id]}]
  (try
    (if-let [active-sessions (get-active-sessions host port)]
      (if (some #{session-id} active-sessions)
        {:status :active
         :host host
         :port port
         :session-id session-id}
        {:status :invalid
         :host host
         :port port
         :reason "Session expired"})
      {:status :invalid
       :host host
       :port port
       :reason "Server unreachable"})
    (catch Exception e
      {:status :invalid
       :host host
       :port port
       :reason (.getMessage e)})))

(defn list-connected-ports
  "List all active nREPL connections from stored session files.

   Returns a vector of maps with:
   - :host       - Host string
   - :port       - Port number
   - :session-id - Active session ID
   - :status     - :active

   Only includes connections that are currently active and reachable.
   Automatically cleans up stale session files."
  []
  (let [ctx {}
        session-files (tmp/list-nrepl-session-files ctx)]
    (->> session-files
         (map validate-stored-connection)
         (filter #(= :active (:status %)))
         vec)))

;; Port discovery

(defn read-nrepl-port-file
  "Read port number from .nrepl-port file in current directory.
   Returns nil if file doesn't exist or on error."
  []
  (try
    (let [file (java.io.File. ".nrepl-port")]
      (when (.exists file)
        (parse-long (str/trim (slurp file)))))
    (catch Exception _
      nil)))

(defn parse-lsof-ports
  "Parse port numbers from lsof output.
   Expects lines like: 'java    12345 user   49u  IPv4 0x1234  TCP *:7888 (LISTEN)'"
  [lsof-output]
  (when lsof-output
    (->> (str/split-lines lsof-output)
         (keep (fn [line]
                 (when-let [[_ port] (re-find #"\*:(\d+)\s+\(LISTEN\)" line)]
                   (parse-long port))))
         distinct
         vec)))

(defn get-listening-jvm-ports
  "Find ports where Java/Clojure/Babashka processes are listening.
   Returns vector of port numbers, empty vector on error."
  []
  (try
    (let [proc (.. (ProcessBuilder. ["sh" "-c" "lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep -Ei 'java|clojure|babashka|bb|nrepl'"])
                   (redirectErrorStream true)
                   start)
          _ (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
          output (slurp (.getInputStream proc))]
      (or (parse-lsof-ports output) []))
    (catch Exception _
      [])))

(defn discover-nrepl-ports
  "Discover potential nREPL ports by:
   1. Checking .nrepl-port file in current directory
   2. Finding Java/Clojure/Babashka processes listening on TCP ports (lsof)
   3. Validating discovered ports by checking if they respond to ls-sessions

   Returns vector of maps with:
   - :host - Host string (always \"localhost\")
   - :port - Port number
   - :source - How port was discovered (:nrepl-port-file or :lsof)
   - :valid - Boolean indicating if port responds to nREPL ls-sessions op"
  []
  (let [;; Collect port candidates
        port-file-port (read-nrepl-port-file)
        lsof-ports (get-listening-jvm-ports)

        ;; Combine and deduplicate
        all-ports (distinct (concat (when port-file-port [port-file-port])
                                    lsof-ports))

        ;; Validate each port
        results (for [port all-ports]
                  (let [source (if (= port port-file-port) :nrepl-port-file :lsof)
                        sessions (get-active-sessions "localhost" port)
                        valid (some? sessions)]
                    {:host "localhost"
                     :port port
                     :source source
                     :valid valid
                     :session-count (if sessions (count sessions) 0)}))]
    (vec results)))

;; Utility functions

(defn now-ms [] (System/currentTimeMillis))

(defn ->uuid [] (str (java.util.UUID/randomUUID)))

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

  Uses persistent sessions: reuses existing session ID from per-target session file
  or creates a new one if none exists. Session persists across invocations."
  [{:keys [host port expr timeout-ms] :or {timeout-ms 120000}}]
  (let [fixed-expr (or (fix-delimiters expr) expr)
        host (or host "localhost")]
    (with-open [s (java.net.Socket. host (coerce-long port))]
      (let [out (java.io.BufferedOutputStream. (.getOutputStream s))
            in (java.io.PushbackInputStream. (.getInputStream s))
            ;; Try to reuse existing session or create new one
            existing-session (slurp-nrepl-session host port)
            ;; Validate session on same socket
            validated-session (validate-session-on-socket out in existing-session)
            session (if validated-session
                      validated-session
                      (let [_ (when (and existing-session (not validated-session))
                                (delete-nrepl-session host port))
                            clone-id (next-id)
                            _ (write-bencode-msg out {"op" "clone" "id" clone-id})
                            {new-session :new-session} (read-msg (b/read-bencode in))]
                        (spit-nrepl-session new-session host port)
                        new-session))
            eval-id (next-id)
            deadline (+ (now-ms) timeout-ms)
            _ (write-bencode-msg out {"op" "eval"
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
                  (when-let [value (:value resp)]
                    (println (str "=> " value))
                    (println "*============================*")
                    (flush))
                  ;; Collect values
                  (let [m (cond-> (update m :responses conj resp)
                            (:value resp)
                            (update :vals conj (:value resp)))]
                    ;; Stop when server says we're done
                    (if (some #{"done"} (:status resp))
                      m
                      (recur m))))
                ;; No message this tick; loop again until timeout
                (recur m))
              ;; Timeout hit — send interrupt
              (do
                (println "\n⚠️  Timeout hit, sending nREPL :interrupt …")
                (write-bencode-msg out {"op" "interrupt"
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
                            (println "✋ Evaluation interrupted.")
                            result)
                          (recur (inc i) (update result :responses conj resp))))
                      (recur (inc i) result))
                    (do
                      (println "✋ Evaluation interrupted.")
                      result)))))))))))

;; ============================================================================
;; Command-line interface
;; ============================================================================

(def cli-options
  [["-p" "--port PORT" "nREPL port (required)"
    :parse-fn parse-long
    :validate [#(> % 0) "Must be a positive number"]]
   ["-H" "--host HOST" "nREPL host (default: 127.0.0.1)"]
   ["-t" "--timeout MILLISECONDS" "Timeout in milliseconds (default: 120000)"
    :default 120000
    :parse-fn parse-long
    :validate [#(> % 0) "Must be a positive number"]]
   ["-r" "--reset-session" "Reset the persistent nREPL session"]
   ["-c" "--connected-ports" "List all active nREPL connections"]
   ["-h" "--help" "Show this help message"]])

(defn has-stdin-data?
  "Check if stdin has data available (not a TTY).
  Returns true if stdin is ready to be read (e.g., piped input or heredoc)."
  []
  (try
    (.ready *in*)
    (catch Exception _ false)))

(defn get-code
  "Get code from command-line arguments or stdin.
  Arguments take precedence over stdin.
  Returns nil if no code is available from either source."
  [arguments]
  (cond
    (seq arguments) (first arguments)
    (has-stdin-data?) (slurp *in*)
    :else nil))

(defn usage [options-summary]
  (str/join \newline
            ["clj-nrepl-eval - Evaluate Clojure code via nREPL"
             ""
             "Usage: clj-nrepl-eval --port PORT CODE"
             "       clj-nrepl-eval --port PORT --reset-session [CODE]"
             "       clj-nrepl-eval --connected-ports"
             "       echo CODE | clj-nrepl-eval --port PORT"
             "       clj-nrepl-eval --port PORT <<'EOF' ... EOF"
             ""
             "Options:"
             options-summary
             ""
             "Session Persistence:"
             "  Sessions are persistent by default. Each host:port combination has its own"
             "  session file. State (vars, namespaces, loaded libraries) persists across"
             "  invocations until the nREPL server restarts or --reset-session is used."
             ""
             "Input Methods:"
             "  Code can be provided as a command-line argument or via stdin (pipe/heredoc)."
             "  Arguments take precedence over stdin when both are provided."
             ""
             "Workflow:"
             "  1. Use --connected-ports to discover available nREPL servers"
             "  2. Use --port to connect to a specific server"
             ""
             "Examples:"
             "  # Discover available connections"
             "  clj-nrepl-eval --connected-ports"
             ""
             "  # Evaluate code (argument)"
             "  clj-nrepl-eval -p 7888 \"(+ 1 2 3)\""
             "  clj-nrepl-eval --port 7888 \"(println \\\"Hello\\\")\""
             ""
             "  # Evaluate code (stdin pipe)"
             "  echo \"(+ 1 2 3)\" | clj-nrepl-eval -p 7888"
             ""
             "  # Evaluate code (heredoc)"
             "  clj-nrepl-eval -p 7888 <<'EOF'"
             "  (def x 10)"
             "  (+ x 20)"
             "  EOF"
             ""
             "  # With timeout"
             "  clj-nrepl-eval -p 7888 --timeout 5000 \"(Thread/sleep 10000)\""
             ""
             "  # Reset session"
             "  clj-nrepl-eval -p 7888 --reset-session"
             "  clj-nrepl-eval -p 7888 --reset-session \"(def x 1)\""]))

(defn error-msg [errors]
  (str "Error parsing command line:\n\n"
       (str/join \newline errors)))

(defn get-host
  "Get host from options, defaulting to 127.0.0.1"
  [opts]
  (or (:host opts) "127.0.0.1"))

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

      ;; Handle --connected-ports flag
      (:connected-ports options)
      (let [connections (list-connected-ports)]
        (if (empty? connections)
          (println "No active nREPL connections found.")
          (do
            (println "Active nREPL connections:")
            (doseq [{:keys [host port session-id]} connections]
              (println (format "  %s:%d (session: %s)" host port session-id)))
            (println)
            (println (format "Total: %d active connection%s"
                             (count connections)
                             (if (= 1 (count connections)) "" "s"))))))

      ;; Handle --reset-session flag
      (:reset-session options)
      (if-let [port (:port options)]
        (let [host (get-host options)]
          (delete-nrepl-session host port)
          (println (str "Session reset for " host ":" port))
          ;; If code is provided, continue to evaluate it with new session
          (when-let [expr (get-code arguments)]
            (eval-expr-with-timeout (cond-> {:host host
                                             :port port
                                             :expr expr}
                                      (:timeout options)
                                      (assoc :timeout-ms (:timeout options))))))
        (do
          (binding [*out* *err*]
            (println "Error: --port is required for --reset-session")
            (println "Use --connected-ports to see available connections"))
          (System/exit 1)))

      :else
      (let [code (get-code arguments)]
        (if-not code
          (do
            (binding [*out* *err*]
              (println "Error: No code provided")
              (println "Provide code as an argument or via stdin (pipe/heredoc)")
              (println)
              (println (usage summary)))
            (System/exit 1))
          (if-let [port (:port options)]
            (eval-expr-with-timeout (cond-> {:host (get-host options)
                                             :port port
                                             :expr code}
                                      (:timeout options)
                                      (assoc :timeout-ms (:timeout options))))
            (do
              (binding [*out* *err*]
                (println "Error: --port is required")
                (println "Use --connected-ports to see available connections"))
              (System/exit 1))))))))
