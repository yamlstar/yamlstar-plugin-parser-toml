(ns toml-parser.test-runner
  (:require [clojure.test :as test]
            [toml-parser.core-test]))

(defn -main
  [& _]
  (let [result (test/run-tests 'toml-parser.core-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
