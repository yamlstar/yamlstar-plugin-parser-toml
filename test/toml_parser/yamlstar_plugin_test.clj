(ns toml-parser.yamlstar-plugin-test
  (:require [clojure.test :refer [deftest is]]
            [yamlstar.plugin :as plugin]
            [yamlstar.plugin.parser.toml]))

(deftest registers-with-yamlstar
  (let [parser (plugin/resolve-parser "toml")
        events ((:parse parser) "answer = 42\n" {})]
    (is (= "toml" (:name parser)))
    (is (= "42" (:value (nth events 4))))))
