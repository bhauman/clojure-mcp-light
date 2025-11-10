#!/usr/bin/env bb

(ns stats-summary
  "Analyze delimiter event statistics from stats log file"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def stats-file
  (let [home (System/getProperty "user.home")]
    (str home "/.clojure-mcp-light/stats.log")))

(defn read-stats
  "Read and parse all EDN entries from stats log file"
  [file-path]
  (if (.exists (io/file file-path))
    (try
      (->> (slurp file-path)
           (str/split-lines)
           (remove str/blank?)
           (map edn/read-string))
      (catch Exception e
        (binding [*out* *err*]
          (println "Error reading stats file:" (.getMessage e)))
        []))
    (do
      (binding [*out* *err*]
        (println "Stats file not found:" file-path))
      [])))

(defn count-by
  "Count entries grouped by a key"
  [k entries]
  (->> entries
       (group-by k)
       (map (fn [[k v]] [k (count v)]))
       (sort-by second >)
       (into {})))

(defn format-count
  "Format count with padding for alignment"
  [n width]
  (format (str "%" width "d") n))

(defn print-section
  "Print a section header"
  [title]
  (println)
  (println title)
  (println (str/join (repeat (count title) "="))))

(defn print-summary
  "Print summary statistics"
  [entries]
  (let [;; Separate delimiter and cljfmt events
        delimiter-events (filter :hook-event entries)
        cljfmt-events (filter #(str/starts-with? (name (:event-type %)) "cljfmt-") entries)
        parse-events (filter #(= :delimiter-parse-error (:event-type %)) entries)

        total (count entries)
        delimiter-total (count delimiter-events)
        cljfmt-total (count cljfmt-events)
        parse-total (count parse-events)

        by-event-type (count-by :event-type delimiter-events)
        by-hook-event (count-by :hook-event delimiter-events)
        by-file (->> delimiter-events
                     (group-by :file-path)
                     (map (fn [[k v]] [k (count v)]))
                     (sort-by second >)
                     (take 10))]

    (println)
    (println "Delimiter Event Statistics")
    (println (str/join (repeat 60 "=")))
    (println)
    (println "Total Events:" total)
    (println "  Delimiter events: " delimiter-total)
    (println "  Cljfmt events:    " cljfmt-total)
    (println "  Parse errors:     " parse-total)

    (when (pos? delimiter-total)
      (print-section "Events by Type (Delimiter)")
      (doseq [[event-type cnt] by-event-type]
        (let [pct (if (pos? delimiter-total)
                    (format "%.1f%%" (* 100.0 (/ cnt delimiter-total)))
                    "0.0%")]
          (println (format "  %-22s %5d  (%6s)" (name event-type) cnt pct))))

      (print-section "Events by Hook")
      (doseq [[hook-event cnt] by-hook-event]
        (println (format "  %-22s %5d" hook-event cnt)))

      (when (seq by-file)
        (print-section "Top 10 Files by Event Count")
        (doseq [[file-path cnt] by-file]
          (let [short-path (if (> (count file-path) 50)
                             (str "..." (subs file-path (- (count file-path) 47)))
                             file-path)]
            (println (format "  %5d  %s" cnt short-path))))))

    (when (pos? parse-total)
      (print-section "Parse Errors")
      (println (format "  Total parse errors: %5d" parse-total))
      (let [by-message (->> parse-events
                            (group-by :ex-message)
                            (map (fn [[k v]] [k (count v)]))
                            (sort-by second >)
                            (take 5))]
        (when (seq by-message)
          (println)
          (println "  Most common parse errors:")
          (doseq [[msg cnt] by-message]
            (let [short-msg (if (> (count msg) 60)
                              (str (subs msg 0 57) "...")
                              msg)]
              (println (format "    %3d  %s" cnt short-msg)))))))

    (println)))

(defn calculate-success-rate
  "Calculate delimiter fix success rate (hook-level events only)"
  [entries]
  (let [hook-events (filter :hook-event entries)
        by-type (count-by :event-type hook-events)
        errors (get by-type :delimiter-error 0)
        fixed (get by-type :delimiter-fixed 0)
        failed (get by-type :delimiter-fix-failed 0)
        ok (get by-type :delimiter-ok 0)]

    (print-section "Success Metrics")
    (println (format "  Clean Code (no errors):     %5d" ok))
    (println (format "  Errors Detected:            %5d" errors))
    (println (format "  Successfully Fixed:         %5d" fixed))
    (println (format "  Failed to Fix:              %5d" failed))
    (println)

    (when (pos? errors)
      (let [fix-rate (* 100.0 (/ fixed errors))]
        (println (format "  Fix Success Rate:          %5.1f%%" fix-rate))))

    (when (pos? (+ ok errors))
      (let [clean-rate (* 100.0 (/ ok (+ ok errors)))]
        (println (format "  Clean Code Rate:           %5.1f%%" clean-rate))))

    (println)))

(defn calculate-cljfmt-efficiency
  "Calculate cljfmt efficiency metrics"
  [entries]
  (let [cljfmt-events (filter #(str/starts-with? (name (:event-type %)) "cljfmt-") entries)
        by-type (count-by :event-type cljfmt-events)
        total-runs (get by-type :cljfmt-run 0)
        already-formatted (get by-type :cljfmt-already-formatted 0)
        needed-formatting (get by-type :cljfmt-needed-formatting 0)
        check-errors (get by-type :cljfmt-check-error 0)]

    (when (pos? total-runs)
      (print-section "Cljfmt Efficiency")
      (println (format "  Total cljfmt runs:          %5d" total-runs))
      (println (format "  Already formatted:          %5d" already-formatted))
      (println (format "  Needed formatting:          %5d" needed-formatting))
      (println (format "  Check errors:               %5d" check-errors))
      (println)

      (when (pos? (+ already-formatted needed-formatting))
        (let [efficiency-rate (* 100.0 (/ already-formatted (+ already-formatted needed-formatting)))
              waste-rate (* 100.0 (/ needed-formatting total-runs))]
          (println (format "  Files already formatted:   %5.1f%%" efficiency-rate))
          (println (format "  Files that needed fix:     %5.1f%%" waste-rate))))

      (println))))

(defn -main [& args]
  (let [file-path (or (first args) stats-file)
        entries (read-stats file-path)]
    (if (empty? entries)
      (do
        (println "No statistics found.")
        (println "Enable stats tracking with: clj-paren-repair-claude-hook --stats")
        (System/exit 1))
      (do
        (print-summary entries)
        (calculate-success-rate entries)
        (calculate-cljfmt-efficiency entries)
        (System/exit 0)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
