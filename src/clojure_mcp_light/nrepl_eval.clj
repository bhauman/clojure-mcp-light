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

(defn eval-expr
  "Execute :expr in nREPL on given :host (defaults to localhost)
  and :port. Returns map with :vals. Prints any output to *out*.

  :vals is a vector with eval results from all the top-level
  forms in the :expr. See the README for an example."
  [{:keys [host port expr]}]
  (let [fixed-expr (or (fix-delimiters expr) expr)
        s (java.net.Socket. (or host "localhost") (coerce-long port))
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        id (next-id)
        _ (b/write-bencode out {"op" "clone" "id" id})
        {session :new-session} (read-msg (b/read-bencode in))
        id (next-id)
        _ (b/write-bencode out {"op" "eval" "code" fixed-expr "id" id "session" session})]
    (loop [m {:vals [] :responses []}]
      (let [{:keys [status out value err] :as resp} (read-reply in session id)]
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
          (if (= ["done"] status)
            m
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
  If timeout-ms is exceeded, sends an interrupt to the nREPL server."
  [{:keys [host port expr timeout-ms] :or {timeout-ms 120000}}]
  (let [fixed-expr (or (fix-delimiters expr) expr)
        s (java.net.Socket. (or host "localhost") (coerce-long port))
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        clone-id (next-id)
        _ (b/write-bencode out {"op" "clone" "id" clone-id})
        {session :new-session} (read-msg (b/read-bencode in))
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
                    (b/write-bencode out {"op" "close" "session" session})
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
                        (b/write-bencode out {"op" "close" "session" session})
                        (.close s)
                        (println "✋ Evaluation interrupted.")
                        result)
                      (recur (inc i) (update result :responses conj resp))))
                  (recur (inc i) result))
                (do
                  (b/write-bencode out {"op" "close" "session" session})
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
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (str/join \newline
            ["clj-nrepl-eval - Evaluate Clojure code via nREPL"
             ""
             "Usage: clj-nrepl-eval [OPTIONS] CODE"
             ""
             "Options:"
             options-summary
             ""
             "Environment Variables:"
             "  NREPL_PORT    Default nREPL port"
             "  NREPL_HOST    Default nREPL host"
             ""
             "Examples:"
             "  clj-nrepl-eval \"(+ 1 2 3)\""
             "  clj-nrepl-eval --port 7888 \"(println \\\"Hello\\\")\""
             "  clj-nrepl-eval --timeout 5000 \"(Thread/sleep 10000)\""]))

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
