(ns clojure-mcp-light.edit-validator-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.edit-validator :as ev]))

(deftest count-occurrences-test
  (testing "count-occurrences basic cases"
    (is (= 0 (ev/count-occurrences "hello world" "foo")))
    (is (= 1 (ev/count-occurrences "hello world" "hello")))
    (is (= 2 (ev/count-occurrences "hello hello world" "hello")))
    (is (= 3 (ev/count-occurrences "aaa" "a")))
    (is (= 0 (ev/count-occurrences "hello" "")))))

(deftest count-occurrences-edge-cases
  (testing "count-occurrences edge cases"
    (is (= 0 (ev/count-occurrences "" "foo")))
    (is (= 0 (ev/count-occurrences "" "")))
    (is (= 2 (ev/count-occurrences "line1\nline1\nline2" "line1")))
    (is (= 1 (ev/count-occurrences "foo(bar)" "(bar)")))))

(deftest apply-edit-in-memory-success-single-match
  (testing "apply-edit-in-memory with single match (default replace-all=false)"
    (let [content "def foo = 1\ndef bar = 2"
          result (ev/apply-edit-in-memory content "def foo" "let foo" false)]
      (is (:success result))
      (is (= "let foo = 1\ndef bar = 2" (:content result)))
      (is (= 1 (:match-count result))))))

(deftest apply-edit-in-memory-success-replace-all
  (testing "apply-edit-in-memory with multiple matches and replace-all=true"
    (let [content "foo bar foo baz"
          result (ev/apply-edit-in-memory content "foo" "qux" true)]
      (is (:success result))
      (is (= "qux bar qux baz" (:content result)))
      (is (= 2 (:match-count result))))))

(deftest apply-edit-in-memory-failure-not-found
  (testing "apply-edit-in-memory when old-string not found"
    (let [content "hello world"
          result (ev/apply-edit-in-memory content "goodbye" "farewell" false)]
      (is (not (:success result)))
      (is (= "old-string not found in file" (:reason result)))
      (is (= 0 (:match-count result))))))

(deftest apply-edit-in-memory-failure-ambiguous
  (testing "apply-edit-in-memory with multiple matches and replace-all=false"
    (let [content "foo bar foo baz"
          result (ev/apply-edit-in-memory content "foo" "qux" false)]
      (is (not (:success result)))
      (is (= "old-string is not unique (found multiple times)" (:reason result)))
      (is (= 2 (:match-count result))))))

(deftest apply-edit-in-memory-failure-empty-old-string
  (testing "apply-edit-in-memory with empty old-string"
    (let [content "hello world"
          result (ev/apply-edit-in-memory content "" "foo" false)]
      (is (not (:success result)))
      (is (= "old-string is empty" (:reason result)))
      (is (= 0 (:match-count result))))))

(deftest apply-edit-in-memory-multiline
  (testing "apply-edit-in-memory with multiline content"
    (let [content "(defn foo\n  [x]\n  (+ x 1))"
          result (ev/apply-edit-in-memory content "  [x]" "  [x y]" false)]
      (is (:success result))
      (is (= "(defn foo\n  [x y]\n  (+ x 1))" (:content result)))
      (is (= 1 (:match-count result))))))

(deftest apply-edit-in-memory-special-chars
  (testing "apply-edit-in-memory with special characters"
    (let [content "(defn foo [x] (+ x 1))"
          result (ev/apply-edit-in-memory content "[x]" "[x y]" false)]
      (is (:success result))
      (is (= "(defn foo [x y] (+ x 1))" (:content result)))
      (is (= 1 (:match-count result))))))

(deftest apply-edit-in-memory-replace-first
  (testing "apply-edit-in-memory replaces only first match when replace-all=false"
    (let [content "foo foo foo"
          result (ev/apply-edit-in-memory content "foo" "bar" false)]
      ;; This should fail because there are multiple matches
      (is (not (:success result)))
      (is (= "old-string is not unique (found multiple times)" (:reason result))))))

(deftest validate-edit-success
  (testing "validate-edit returns valid for successful edit"
    (let [content "hello world"
          result (ev/validate-edit content "hello" "goodbye" false)]
      (is (:valid? result))
      (is (nil? (:reason result)))
      (is (= 1 (:match-count result))))))

(deftest validate-edit-failure
  (testing "validate-edit returns invalid for failed edit"
    (let [content "hello world"
          result (ev/validate-edit content "foo" "bar" false)]
      (is (not (:valid? result)))
      (is (= "old-string not found in file" (:reason result)))
      (is (= 0 (:match-count result))))))

(deftest validate-edit-ambiguous
  (testing "validate-edit detects ambiguous edits"
    (let [content "foo bar foo"
          result (ev/validate-edit content "foo" "qux" false)]
      (is (not (:valid? result)))
      (is (= "old-string is not unique (found multiple times)" (:reason result)))
      (is (= 2 (:match-count result))))))

(deftest validate-edit-clojure-code
  (testing "validate-edit with realistic Clojure code"
    (let [content "(defn add [x y]\n  (+ x y))\n\n(defn sub [x y]\n  (- x y))"
          result (ev/validate-edit content "(+ x y)" "(apply + [x y])" false)]
      (is (:valid? result))
      (is (= 1 (:match-count result))))))

(deftest validate-edit-indentation
  (testing "validate-edit preserves indentation in validation"
    (let [content "(defn foo\n  [x]\n  (+ x 1))"
          result (ev/validate-edit content "  [x]" "  [x y]" false)]
      (is (:valid? result))
      (is (= 1 (:match-count result))))))

;; Sliding indentation tests

(deftest slide-indentation-forward
  (testing "slide-indentation adds spaces to each line"
    (is (= " hello\n world" (ev/slide-indentation "hello\nworld" 1)))
    (is (= "  hello\n  world" (ev/slide-indentation "hello\nworld" 2)))
    (is (= "   hello\n   world" (ev/slide-indentation "hello\nworld" 3)))))

(deftest slide-indentation-backward
  (testing "slide-indentation removes spaces from each line"
    (is (= "hello\nworld" (ev/slide-indentation " hello\n world" -1)))
    (is (= "hello\nworld" (ev/slide-indentation "  hello\n  world" -2)))
    (is (= "hello\nworld" (ev/slide-indentation "   hello\n   world" -3)))))

(deftest slide-indentation-backward-partial
  (testing "slide-indentation only removes available spaces"
    (is (= "hello\n world" (ev/slide-indentation " hello\n  world" -1)))
    (is (= "hello\nworld" (ev/slide-indentation "hello\n  world" -2)))))

(deftest slide-indentation-zero
  (testing "slide-indentation with offset 0 returns unchanged"
    (is (= "hello\nworld" (ev/slide-indentation "hello\nworld" 0)))))

(deftest validate-sliding-edit-exact-match
  (testing "validate-sliding-edit returns offset 0 for exact match"
    (let [content "(defn foo\n  [x]\n  (+ x 1))"
          result (ev/validate-sliding-edit content "  [x]" "  [x y]" false)]
      (is (:valid? result))
      (is (= 0 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (nil? (:adjusted-old-string result))))))

(deftest validate-sliding-edit-forward-1
  (testing "validate-sliding-edit finds match with +1 space"
    (let [content "    1\n    1\n    1\n"
          old-string "   1\n   1\n   1\n"  ; One space less than actual (3 instead of 4)
          result (ev/validate-sliding-edit content old-string "    2\n    2\n    2\n" false)]
      (is (:valid? result))
      (is (= 1 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (= "    1\n    1\n    1\n" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-forward-2
  (testing "validate-sliding-edit finds match with +2 spaces"
    (let [content "    1\n    1\n    1\n"
          old-string "  1\n  1\n  1\n"  ; Two spaces less than actual (2 instead of 4)
          result (ev/validate-sliding-edit content old-string "    2\n    2\n    2\n" false)]
      (is (:valid? result))
      (is (= 2 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (= "    1\n    1\n    1\n" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-backward-1
  (testing "validate-sliding-edit finds match with -1 space"
    (let [content "(defn foo\n [x]\n (+ x 1))"
          old-string "  [x]"  ; One space more than actual
          result (ev/validate-sliding-edit content old-string " [x y]" false)]
      (is (:valid? result))
      (is (= -1 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (= " [x]" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-backward-2
  (testing "validate-sliding-edit finds match with -2 spaces"
    (let [content "(defn foo\n[x]\n(+ x 1))"
          old-string "  [x]"  ; Two spaces more than actual
          result (ev/validate-sliding-edit content old-string "[x y]" false)]
      (is (:valid? result))
      (is (= -2 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (= "[x]" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-no-match
  (testing "validate-sliding-edit returns nil offset when no match found"
    (let [content "(defn foo\n  [x y]\n  (+ x y 1))"
          old-string "       [x y]"  ; Too many spaces (7), beyond sliding range (max +3 = 5 total)
          result (ev/validate-sliding-edit content old-string "  [x y z]" false)]
      (is (not (:valid? result)))
      (is (nil? (:indentation-offset result)))
      (is (= "old-string not found in file" (:reason result))))))

(deftest validate-sliding-edit-multiline-clojure
  (testing "validate-sliding-edit with realistic multiline Clojure code"
    (let [content "    a\n    b\n    c\n"
          ;; Old string has wrong indentation (2 spaces instead of 4)
          old-string "  a\n  b\n  c\n"
          result (ev/validate-sliding-edit content old-string "    x\n    y\n    z\n" false)]
      (is (:valid? result))
      (is (= 2 (:indentation-offset result)))
      (is (= 1 (:match-count result)))
      (is (= "    a\n    b\n    c\n" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-empty-lines
  (testing "validate-sliding-edit handles empty lines"
    (let [content "line1\n\nline3"
          old-string "line1\nline3"  ; Missing empty line
          result (ev/validate-sliding-edit content old-string "replacement" false)]
      ;; Should not match because structure is different
      (is (not (:valid? result))))))

;; Line ending normalization tests

(deftest normalize-line-endings-crlf
  (testing "normalize-line-endings converts CRLF to LF"
    (is (= "line1\nline2\nline3\n" (ev/normalize-line-endings "line1\r\nline2\r\nline3\r\n")))))

(deftest normalize-line-endings-cr
  (testing "normalize-line-endings converts CR to LF"
    (is (= "line1\nline2\nline3\n" (ev/normalize-line-endings "line1\rline2\rline3\r")))))

(deftest normalize-line-endings-mixed
  (testing "normalize-line-endings handles mixed line endings"
    (is (= "line1\nline2\nline3\n" (ev/normalize-line-endings "line1\r\nline2\rline3\n")))))

(deftest normalize-line-endings-already-lf
  (testing "normalize-line-endings preserves LF"
    (is (= "line1\nline2\n" (ev/normalize-line-endings "line1\nline2\n")))))

(deftest validate-sliding-edit-crlf-mismatch
  (testing "validate-sliding-edit matches after normalizing CRLF vs LF"
    (let [content "  line1\n  line2\n"  ; LF
          old-string "  line1\r\n  line2\r\n"  ; CRLF
          result (ev/validate-sliding-edit content old-string "  changed" false)]
      (is (:valid? result))
      (is (= 0 (:indentation-offset result)))
      (is (true? (:normalized? result))))))

(deftest validate-sliding-edit-cr-mismatch
  (testing "validate-sliding-edit matches after normalizing CR vs LF"
    (let [content "  line1\n  line2\n"  ; LF
          old-string "  line1\r  line2\r"  ; CR
          result (ev/validate-sliding-edit content old-string "  changed" false)]
      (is (:valid? result))
      (is (= 0 (:indentation-offset result)))
      (is (true? (:normalized? result))))))

(deftest validate-sliding-edit-normalized-and-slid
  (testing "validate-sliding-edit can normalize and slide together"
    (let [content "    a\n    b\n"  ; LF, 4 spaces
          old-string "  a\r\n  b\r\n"  ; CRLF, 2 spaces
          result (ev/validate-sliding-edit content old-string "    x\n    y\n" false)]
      (is (:valid? result))
      (is (= 2 (:indentation-offset result)))
      (is (true? (:normalized? result)))
      (is (= "    a\n    b\n" (:adjusted-old-string result))))))

(deftest validate-sliding-edit-exact-no-normalization
  (testing "validate-sliding-edit doesn't report normalization when not needed"
    (let [content "  line1\n  line2\n"
          old-string "  line1\n  line2\n"
          result (ev/validate-sliding-edit content old-string "  changed" false)]
      (is (:valid? result))
      (is (= 0 (:indentation-offset result)))
      (is (false? (:normalized? result))))))
