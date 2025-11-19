(babashka.deps/add-deps '{:deps {dev.weavejester/cljfmt {:mvn/version "0.15.5"}
                                 parinferish/parinferish {:mvn/version "0.8.0"}}})

(ns clojure-mcp-light.paren-repair
  "Standalone CLI tool for fixing delimiter errors and formatting Clojure files"
  (:require [babashka.fs :as fs]
            [clojure.string :as string]
            [clojure-mcp-light.hook :refer [clojure-file? fix-and-format-file!]]
            [clojure-mcp-light.stats :as stats]
            [taoensso.timbre :as timbre]))

;; ============================================================================
;; File Processing
;; ============================================================================

(defn process-file
  "Process a single file: fix delimiters and format.
   Returns a map with:
   - :success - boolean indicating overall success
   - :file-path - the processed file path
   - :message - human-readable message about what happened
   - :delimiter-fixed - boolean indicating if delimiter was fixed
   - :formatted - boolean indicating if file was formatted"
  [file-path]
  (cond
    (not (fs/exists? file-path))
    {:success false
     :file-path file-path
     :message "File does not exist"
     :delimiter-fixed false
     :formatted false}

    (not (clojure-file? file-path))
    {:success false
     :file-path file-path
     :message "Not a Clojure file (skipping)"
     :delimiter-fixed false
     :formatted false}

    :else
    ;; Use shared fix-and-format-file! from hook.clj
    (assoc (fix-and-format-file! file-path true "paren-repair")
           :file-path file-path)))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main [& args]
  (if (empty? args)
    (do
      (binding [*out* *err*]
        (println "Usage: clj-paren-repair FILE [FILE ...]")
        (println)
        (println "Fix delimiter errors and format Clojure files.")
        (println)
        (println "Features enabled by default:")
        (println "  - Delimiter error detection and repair")
        (println "  - cljfmt formatting"))
      (System/exit 1))

    (let [stats-path (stats/normalize-stats-path
                      (str (fs/home) "/.clojure-mcp-light/stats.log"))]

      ;; Disable logging and stats for standalone tool
      (timbre/set-config! {:appenders {}})

      (binding [stats/*enable-stats* false
                stats/*stats-file-path* stats-path]
        (try
          (let [results (doall (map process-file args))
                successes (filter :success results)
                failures (filter (complement :success) results)
                success-count (count successes)
                failure-count (count failures)]

            ;; Print results
            (println)
            (println "clj-paren-repair Results")
            (println "========================")
            (println)

            (doseq [{:keys [file-path message delimiter-fixed formatted]} results]
              (let [tags (when (or delimiter-fixed formatted)
                           (str " ["
                                (string/join ", "
                                             (filter some?
                                                     [(when delimiter-fixed "delimiter-fixed")
                                                      (when formatted "formatted")]))
                                "]"))]
                (println (str "  " file-path ": " message tags))))

            (println)
            (println "Summary:")
            (println "  Success:" success-count)
            (println "  Failed: " failure-count)
            (println)

            (if (zero? failure-count)
              (System/exit 0)
              (System/exit 1)))
          (catch Exception e
            (binding [*out* *err*]
              (println "Fatal error:" (.getMessage e)))
            (System/exit 1)))))))
