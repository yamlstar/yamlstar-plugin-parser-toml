(ns yamlstar.plugin.parser.toml
  "Register the TOML 1.1 parser with YAMLStar."
  (:require [toml-parser.core :as toml]
            [yamlstar.plugin :as plugin]))

(def plugin
  (plugin/register-parser!
   {:name "toml"
    :parse toml/parse
    :default-config {}}))
