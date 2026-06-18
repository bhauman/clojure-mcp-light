(ns clojure-mcp-light.antigravity-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.antigravity :as ag]
            [clojure-mcp-light.hook :as hook]
            [clojure-mcp-light.tmp :as tmp]
            [babashka.fs :as fs]))

(def ^:private deps
  "Repair functions hook.clj injects into the adapter at runtime."
  {:clojure-file? hook/clojure-file?
   :fix-and-format-file! hook/fix-and-format-file!
   :cljfmt? false})

(defn- with-temp-runtime*
  "Run f with the temp runtime base redirected to a throwaway dir, so the
  Pre->Post stash files don't touch the real $TMPDIR. Passes the base path."
  [f]
  (let [base (fs/create-temp-dir {:prefix "ag-test-"})]
    (try
      (with-redefs [tmp/runtime-base-dir (constantly (str base))]
        (f (str base)))
      (finally
        (fs/delete-tree base)))))

(deftest resolve-target-test
  (testing "relative paths resolve against the first workspace root"
    (is (= "/work/src/core.clj" (ag/resolve-target "src/core.clj" ["/work"]))))
  (testing "absolute paths pass through (normalized)"
    (is (= "/elsewhere/core.clj" (ag/resolve-target "/elsewhere/core.clj" ["/work"]))))
  (testing "blank or nil target yields nil"
    (is (nil? (ag/resolve-target nil ["/work"])))
    (is (nil? (ag/resolve-target "" ["/work"])))))

(deftest pre-post-roundtrip-repairs-test
  (testing "PreToolUse stashes the path; PostToolUse repairs the file on disk"
    (with-temp-runtime*
      (fn [_]
        (let [dir (str (fs/create-temp-dir {:prefix "ag-ws-"}))
              file (str (fs/path dir "core.clj"))]
          (try
            (spit file "(def x 1" :encoding "UTF-8")
            (is (= {:decision "allow"}
                   (ag/pre-tool-use deps {:toolCall {:name "replace_file_content"
                                                     :args {:TargetFile file}}
                                          :stepIdx 2 :conversationId "c1"
                                          :workspacePaths [dir]})))
            (is (= {} (ag/post-tool-use deps {:stepIdx 2 :conversationId "c1"})))
            (is (= "(def x 1)" (slurp file :encoding "UTF-8")))
            (finally (fs/delete-tree dir))))))))

(deftest post-tool-use-uses-toolcall-path-test
  (testing "PostToolUse repairs via toolCall.args.TargetFile directly, no Pre stash"
    (with-temp-runtime*
      (fn [_]
        (let [dir (str (fs/create-temp-dir {:prefix "ag-ws-"}))
              file (str (fs/path dir "core.clj"))]
          (try
            (spit file "(def x 1" :encoding "UTF-8")
            ;; No PreToolUse call => no stash; Post must use its own toolCall
            ;; (this is what real agy sends).
            (is (= {} (ag/post-tool-use deps {:toolCall {:name "replace_file_content"
                                                         :args {:TargetFile file}}
                                              :stepIdx 5 :conversationId "no-stash"
                                              :workspacePaths [dir]})))
            (is (= "(def x 1)" (slurp file :encoding "UTF-8")))
            (finally (fs/delete-tree dir))))))))

(deftest pre-tool-use-ignores-non-edits-test
  (testing "non-edit tools and non-Clojure files are allowed without stashing"
    (with-temp-runtime*
      (fn [_]
        (let [dir (str (fs/create-temp-dir {:prefix "ag-ws-"}))
              clj (str (fs/path dir "a.clj"))
              txt (str (fs/path dir "a.txt"))]
          (try
            (spit clj "(def x 1" :encoding "UTF-8")
            (spit txt "(def x 1" :encoding "UTF-8")
            ;; non-edit tool, even on a .clj file: allowed, nothing stashed
            (is (= {:decision "allow"}
                   (ag/pre-tool-use deps {:toolCall {:name "run_command" :args {:TargetFile clj}}
                                          :stepIdx 3 :conversationId "c2" :workspacePaths [dir]})))
            ;; edit tool on a non-Clojure file: allowed, nothing stashed
            (is (= {:decision "allow"}
                   (ag/pre-tool-use deps {:toolCall {:name "write_to_file" :args {:TargetFile txt}}
                                          :stepIdx 4 :conversationId "c2" :workspacePaths [dir]})))
            ;; nothing stashed => Post is a no-op, both files stay as written
            (is (= {} (ag/post-tool-use deps {:stepIdx 3 :conversationId "c2"})))
            (is (= {} (ag/post-tool-use deps {:stepIdx 4 :conversationId "c2"})))
            (is (= "(def x 1" (slurp clj :encoding "UTF-8")))
            (is (= "(def x 1" (slurp txt :encoding "UTF-8")))
            (finally (fs/delete-tree dir))))))))

(deftest post-tool-use-leaves-unfixable-untouched-test
  (testing "an unfixable file is left exactly as the model wrote it (no revert)"
    (with-temp-runtime*
      (fn [_]
        (let [dir (str (fs/create-temp-dir {:prefix "ag-ws-"}))
              file (str (fs/path dir "core.clj"))
              ;; inject a repair fn that always fails (and never writes)
              failing-deps (assoc deps :fix-and-format-file!
                                  (fn [_ _ _]
                                    {:success false :delimiter-fixed false
                                     :formatted false :message "stub failure"}))]
          (try
            (spit file "(def x 1" :encoding "UTF-8")
            (ag/pre-tool-use deps {:toolCall {:name "write_to_file" :args {:TargetFile file}}
                                   :stepIdx 9 :conversationId "c3" :workspacePaths [dir]})
            (is (= {} (ag/post-tool-use failing-deps {:stepIdx 9 :conversationId "c3"})))
            ;; left exactly as written - no backup, no revert
            (is (= "(def x 1" (slurp file :encoding "UTF-8")))
            (finally (fs/delete-tree dir))))))))

(deftest post-tool-use-without-stash-is-noop-test
  (testing "PostToolUse with no stashed path returns {} and does nothing"
    (with-temp-runtime*
      (fn [_]
        (is (= {} (ag/post-tool-use deps {:stepIdx 99 :conversationId "missing"})))))))

(deftest process-dispatch-test
  (testing "unhandled events return an empty response"
    (is (= {} (ag/process deps "PreInvocation" {})))
    (is (= {} (ag/process deps "PostInvocation" {})))
    (is (= {} (ag/process deps "Nonsense" {})))))

(deftest stop-sweeps-and-allows-test
  (testing "Stop reaps stale dirs and returns {} (lets the agent stop)"
    (with-temp-runtime*
      (fn [base]
        (let [stale (fs/create-dirs (fs/path base "clojure-mcp-light" "ag-stale-proj-x"))
              day-ms (* 24 60 60 1000)
              now (System/currentTimeMillis)]
          (spit (str (fs/path stale "f.edn")) "{}")
          (fs/set-last-modified-time (fs/path stale "f.edn") (- now (* 10 day-ms)))
          (fs/set-last-modified-time stale (- now (* 10 day-ms)))
          (is (= {} (ag/stop {:conversationId "c4"})))
          (is (not (fs/exists? stale))))))))
