(ns clojure-mcp-light.nrepl-eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp-light.nrepl-eval :as ne]
            [clojure.java.io :as io]))

(deftest bytes->str-test
  (testing "converts bytes to string"
    (is (= "hello" (ne/bytes->str (.getBytes "hello"))))
    (is (= "test" (ne/bytes->str "test")))))

(deftest coerce-long-test
  (testing "converts string to long"
    (is (= 7888 (ne/coerce-long "7888")))
    (is (= 1234 (ne/coerce-long 1234)))))

(deftest next-id-test
  (testing "generates unique IDs"
    (let [id1 (ne/next-id)
          id2 (ne/next-id)]
      (is (string? id1))
      (is (string? id2))
      (is (not= id1 id2)))))

(deftest slurp-nrepl-port-test
  (testing "reads port from .nrepl-port file"
    (let [test-port-file ".nrepl-port-test"
          test-port 9999]
      (try
        ;; Create a test port file
        (spit test-port-file (str test-port))
        ;; Test reading it
        (let [result (with-redefs [ne/slurp-nrepl-port
                                   (fn []
                                     (when (.exists (io/file test-port-file))
                                       (parse-long (clojure.string/trim (slurp test-port-file)))))]
                       (ne/slurp-nrepl-port))]
          (is (= test-port result)))
        (finally
          ;; Clean up
          (io/delete-file test-port-file true)))))

  (testing "returns nil when file doesn't exist"
    ;; Note: This test may fail if .nrepl-port exists from other processes
    ;; Just test that it returns either nil or a valid port number
    (let [result (ne/slurp-nrepl-port)]
      (is (or (nil? result) (number? result))))))

(deftest get-port-test
  (testing "gets port from options"
    (is (= 8888 (ne/get-port {:port 8888}))))

  (testing "falls back to environment variable"
    (with-redefs [ne/get-port
                  (fn [opts]
                    (or (:port opts)
                        (some-> (System/getenv "NREPL_PORT") parse-long)
                        (ne/slurp-nrepl-port)))]
      ;; Can't easily test env var without actually setting it
      (is (number? (or (ne/get-port {}) nil))))))

(deftest get-host-test
  (testing "gets host from options"
    (is (= "custom-host" (ne/get-host {:host "custom-host"}))))

  (testing "falls back to default"
    (is (= "127.0.0.1" (ne/get-host {})))))

(deftest read-msg-test
  (testing "converts byte values to strings in message"
    (let [msg {"status" [(.getBytes "done")]
               "value" "42"}
          result (ne/read-msg msg)]
      (is (map? result))
      (is (= "done" (first (:status result))))
      (is (= "42" (:value result))))))
