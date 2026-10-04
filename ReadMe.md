# YAMLStar TOML parser plugin

This repository implements a TOML 1.1 parser that produces YAMLStar parser
events.
The canonical parser source is YAMLScript.
It generates portable Clojure and Go code used by the YAMLStar and go-yaml
integrations.

```clojure
(require '[toml-parser.core :as toml])

(toml/parse "answer = 42\n")
```

The parser preserves TOML comments as YAML event metadata.
TOML date and time values are represented as strings so YAMLStar and go-yaml
construct the same JSON-compatible values.

Use the plugin with YAMLStar:

```bash
yaml --plugin=parser=toml document.toml
```

The plugin selector uses the common `parser` API name in YAMLStar and
go-yaml.

## Development

```bash
make test
make test-yamlstar
make generate-check
make test-conformance
make build
```

The conformance target checks the complete TOML 1.1 decoder suite with
`toml-test` v2.2.0.
The build target produces
`lib/libyamlstar-plugin-parser-toml.so` on Linux and the corresponding
`.dylib` on macOS.
Set `YAMLSTAR_LIBRARY_PATH` to the `lib` directory to test it without
installing it.

The shared library implements YAMLStar plugin ABI v2.
It returns the same event stream as the generated Clojure and Go APIs,
including scalar tags and TOML comment metadata.

## License

MIT License.
