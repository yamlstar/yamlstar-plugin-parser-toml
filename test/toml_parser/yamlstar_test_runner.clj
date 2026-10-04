(ns toml-parser.yamlstar-test-runner
  (:require [clojure.test :as test]
            [toml-parser.yamlstar-plugin-test]))

(defn -main
  [& _]
  (let [result (test/run-tests 'toml-parser.yamlstar-plugin-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
