(ns clojure-mcp-light.edit-validator
  "Validates Edit tool operations by simulating them in memory.
  Used to track stats about edit match success/failure rates."
  (:require [clojure.string :as string]))

(defn count-occurrences
  "Count the number of non-overlapping occurrences of `s` in `content`."
  [content s]
  (if (empty? s)
    0
    (loop [remaining content
           cnt 0]
      (if-let [idx (string/index-of remaining s)]
        (recur (subs remaining (+ idx (count s)))
               (inc cnt))
        cnt))))

(defn apply-edit-in-memory
  "Simulates the Edit tool's string replacement logic in memory.

  Args:
    content - File content as string
    old-string - Text to be replaced
    new-string - Replacement text
    replace-all - Boolean flag (default false)

  Returns:
    Map with:
      :success - Boolean indicating if edit would succeed
      :content - Updated content (if successful)
      :reason - Explanation of failure (if unsuccessful)
      :match-count - Number of times old-string was found"
  [content old-string new-string replace-all]
  (let [match-count (count-occurrences content old-string)]
    (cond
      ;; Empty old-string is invalid
      (empty? old-string)
      {:success false
       :reason "old-string is empty"
       :match-count 0}

      ;; No matches found
      (zero? match-count)
      {:success false
       :reason "old-string not found in file"
       :match-count 0}

      ;; Multiple matches without replace-all flag
      (and (> match-count 1) (not replace-all))
      {:success false
       :reason "old-string is not unique (found multiple times)"
       :match-count match-count}

      ;; Success case: replace once or replace all
      :else
      (let [updated-content (if replace-all
                              (string/replace content old-string new-string)
                              (string/replace-first content old-string new-string))]
        {:success true
         :content updated-content
         :match-count match-count}))))

(defn validate-edit
  "Tests if an edit operation would succeed without applying it.

  Args:
    file-content - Current file content as string
    old-string - Text to be replaced
    new-string - Replacement text (not used for validation, but included for completeness)
    replace-all - Boolean flag (default false)

  Returns:
    Map with:
      :valid? - Boolean indicating if edit would succeed
      :reason - Explanation (if invalid)
      :match-count - Number of matches found"
  [file-content old-string new-string replace-all]
  (let [result (apply-edit-in-memory file-content old-string new-string replace-all)]
    {:valid? (:success result)
     :reason (:reason result)
     :match-count (:match-count result)}))

(defn normalize-line-endings
  "Normalize line endings to Unix-style LF (\\n).

  Args:
    s - String with potentially mixed line endings

  Returns:
    String with all line endings normalized to \\n"
  [s]
  (-> s
      (string/replace "\r\n" "\n")  ; CRLF -> LF
      (string/replace "\r" "\n")))  ; CR -> LF

(defn slide-indentation
  "Adjust indentation of a string by adding/removing spaces from the start of each line.

  Args:
    s - String content to adjust
    offset - Number of spaces to add (positive) or remove (negative)

  Returns:
    Adjusted string with indentation changed"
  [s offset]
  (if (zero? offset)
    s
    (let [has-trailing-newline? (string/ends-with? s "\n")
          lines (string/split-lines s)
          adjust-fn (if (pos? offset)
                      ;; Add spaces
                      (fn [line] (str (apply str (repeat offset " ")) line))
                      ;; Remove spaces
                      (fn [line]
                        (let [spaces-to-remove (- offset)
                              leading-spaces (count (take-while #(= % \space) line))]
                          (if (>= leading-spaces spaces-to-remove)
                            (subs line spaces-to-remove)
                            line))))
          adjusted-lines (map adjust-fn lines)
          result (string/join "\n" adjusted-lines)]
      (if has-trailing-newline?
        (str result "\n")
        result))))

(defn validate-sliding-edit
  "Tests if an edit would succeed, trying indentation adjustments if exact match fails.

  Strategy:
  1. Try exact match with original strings
  2. If fails, normalize line endings and try exact match again
  3. If still fails, slide normalized strings by ±1, ±2, ±3 spaces

  Args:
    file-content - Current file content as string
    old-string - Text to be replaced
    new-string - Replacement text
    replace-all - Boolean flag (default false)

  Returns:
    Map with:
      :valid? - Boolean indicating if edit would succeed
      :reason - Explanation (if invalid)
      :match-count - Number of matches found
      :indentation-offset - Spaces adjusted (0 if exact, nil if failed)
      :adjusted-old-string - The old-string after sliding (if adjusted)
      :normalized? - Boolean indicating if line endings were normalized"
  [file-content old-string new-string replace-all]
  ;; Try exact match first with original strings
  (let [exact-result (validate-edit file-content old-string new-string replace-all)]
    (if (:valid? exact-result)
      ;; Exact match succeeded
      (assoc exact-result :indentation-offset 0 :normalized? false)
      ;; Try with normalized line endings
      (let [norm-content (normalize-line-endings file-content)
            norm-old (normalize-line-endings old-string)
            norm-exact-result (validate-edit norm-content norm-old new-string replace-all)]
        (if (:valid? norm-exact-result)
          ;; Normalized exact match succeeded
          (assoc norm-exact-result :indentation-offset 0 :normalized? true)
          ;; Try sliding with normalized strings
          (let [offsets [1 2 3 -1 -2 -3]
                try-offset (fn [offset]
                             (let [adjusted-old (slide-indentation norm-old offset)
                                   result (validate-edit norm-content adjusted-old new-string replace-all)]
                               (when (:valid? result)
                                 (assoc result
                                        :indentation-offset offset
                                        :adjusted-old-string adjusted-old
                                        :normalized? true))))
                sliding-result (some try-offset offsets)]
            (or sliding-result
                ;; No match found after all attempts
                (assoc exact-result :indentation-offset nil :normalized? false))))))))
