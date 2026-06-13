(ns clojure-mcp-light.apply-patch
  "Parsing for Codex apply_patch commands.

   Codex hooks share Claude Code's hook wire format, but Codex edits files
   through a single `apply_patch` tool whose input is a patch document.
   This namespace extracts the affected file operations from that document
   so the hook can back up, repair, and revert the right files."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:private directive-prefixes
  [["*** Update File: " :update]
   ["*** Move to: "     :move-to]
   ["*** Add File: "    :add]
   ["*** Delete File: " :delete]])

(defn- parse-line
  [line]
  (some (fn [[prefix kind]]
          (when (string/starts-with? line prefix)
            (let [path (string/trim (subs line (count prefix)))]
              (when-not (string/blank? path)
                {:directive kind :path path}))))
        directive-prefixes))

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
        ;; A :move-to not preceded by :update has no meaning on its own
        :move-to (recur (rest ds) ops))
      ops)))

(defn resolve-path
  "Resolve an apply_patch file path against the Codex session cwd.
   apply_patch paths are usually relative, unlike Claude's absolute file_path."
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
  (let [resolve* #(resolve-path cwd %)]
    (cond-> op
      (:path op) (update :path resolve*)
      (:from op) (update :from resolve*)
      (:to op)   (update :to resolve*))))

(defn ops
  "Parse a Codex apply_patch hook input into ops with paths resolved
   against the session cwd. Op shapes:
   {:op :update :path p}, {:op :add :path p}, {:op :delete :path p},
   {:op :move :from a :to b}"
  [{:keys [cwd tool_input]}]
  (->> (string/split-lines (or (:command tool_input) ""))
       (keep parse-line)
       parse-ops
       (mapv #(resolve-op-paths cwd %))))

(defn op-source
  "Pre-patch path that needs a backup to make the op revertible.
   nil for :add (revert is deletion) and :delete (nothing to repair)."
  [op]
  (case (:op op)
    :update (:path op)
    :move   (:from op)
    (:add :delete) nil))

(defn op-target
  "Post-patch path that should be repaired/formatted. nil for :delete."
  [op]
  (case (:op op)
    :update (:path op)
    :move   (:to op)
    :add    (:path op)
    :delete nil))
