(ns toml-parser.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [toml-parser.core :as toml]))

(defn- body
  [text]
  (subvec (toml/parse text) 2 (- (count (toml/parse text)) 2)))

(deftest parses-basic-document
  (is (= [{:event "mapping_start"}
          {:event "scalar" :value "title" :style "double" :tag "!!str"}
          {:event "scalar" :value "TOML" :style "double"
           :tag "!!str" :toml-type "string"}
          {:event "scalar" :value "answer" :style "double" :tag "!!str"}
          {:event "scalar" :value "42" :tag "!!int"
           :toml-type "integer"}
          {:event "mapping_end"}]
         (body "title = \"TOML\"\nanswer = 42\n"))))

(deftest parses-tables-and-arrays
  (is (= "mapping_start" (:event (nth (toml/parse
                                        "[owner]\nname='Pat'\nports=[1,2]\n")
                                       4))))
  (is (= ["1" "2"]
         (->> (toml/parse "ports=[1,2]\n")
              (filter #(and (= "scalar" (:event %))
                            (contains? #{"1" "2"} (:value %))))
              (mapv :value)))))

(deftest normalizes-values
  (let [events (toml/parse
                (str "hex = 0xDEAD_BEEF\n"
                     "when = 2026-09-24T12:34Z\n"
                     "tiny = -2E-2\n"))]
    (is (some #(= "3735928559" (:value %)) events))
    (is (some #(= "2026-09-24T12:34:00Z" (:value %)) events))
    (is (some #(= "-2E-2" (:value %)) events))))

(deftest preserves-comments
  (let [events (toml/parse
                "# file\nkey = [\n  # item\n  1, # one\n  2\n] # values\n")]
    (is (= "# file" (:head (nth events 2))))
    (is (some #(= "# item" (:head %)) events))
    (is (some #(= "# one" (:line %)) events))
    (is (some #(= "# values" (:line %)) events))))

(deftest rejects-invalid-input
  (testing "duplicate keys"
    (is (thrown-with-msg? Exception #"duplicate key"
                          (toml/parse "a=1\na=2\n"))))
  (testing "invalid date"
    (is (thrown-with-msg? Exception #"invalid date"
                          (toml/parse "a=2025-02-29\n"))))
  (testing "configuration"
    (is (thrown-with-msg? Exception #"configuration must be empty"
                          (toml/parse "" {:x true})))))
