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

YAMLStar uses the `parser` API name.
The go-yaml adapter uses `yaml-parser`.

## go-yaml adapter

The `goyaml` package implements `yaml.ParserPlugin` using the compiled Go
parser in this module.
It requires no shared library, cgo, JVM, or runtime download.
It preserves TOML scalar types and comment metadata.

Use it directly:

```go
import (
    toml "github.com/yamlstar/yamlstar-plugin-parser-toml/goyaml"
    "go.yaml.in/yaml/v4"
)

var value any
err := yaml.Load([]byte("answer = 42\n"), &value,
    yaml.WithPlugin(toml.New()))
```

For configuration-based selection, call `toml.Register()` once during
application startup, then select `yaml-parser: toml` with `yaml.OptsYAML`.
Registration preserves the native default and rejects duplicate calls.
The adapter accepts only empty configuration.
`toml.Version` identifies the linked parser release.

Build the generic go-yaml CLI with the versioned package:

```bash
make cli \
  PLUGIN-PACKAGE=github.com/yamlstar/yamlstar-plugin-parser-toml/goyaml@v0.1.1
go-yaml --plugin=yaml-parser=toml -j < settings.toml
```

Run the build command from a go-yaml checkout with `PLUGIN-PACKAGE` support.
A packages-only build retains native defaults; the runtime flag selects TOML.
The package list can include other adapters, separated by commas.

Versioned selection is also supported:

```yaml
plugin:
  yaml-parser: toml@v0.1.1
```

## Development

```bash
make test
make test-yamlstar
make generate-check
make test-conformance
make test-goyaml test-race
make test-goyaml-cli
make build
```

The conformance target checks the complete TOML 1.1 decoder suite with
`toml-test` v2.2.0.
The build target produces
`lib/libyamlstar-plugin-parser-toml.so` on Linux and the corresponding
`.dylib` on macOS.
Set `YAMLSTAR_LIBRARY_PATH` to the `lib` directory to test it without
installing it.
This library belongs to the shared ABI integration; the go-yaml adapter calls
the Go parser directly.

The shared library implements YAMLStar plugin ABI v2.
It returns the same event stream as the generated Clojure and Go APIs,
including scalar tags and TOML comment metadata.

`make test-goyaml-cli` uses the sibling go-yaml checkout by default.
Set `GO_YAML_DIR` to another checkout when needed.
Set `GO_YAML_REF` to test a specific commit from that checkout, such as the
revision pinned in CI, without changing its branch or working files.
The test stages disposable sources and a local module proxy under `.cache/`,
builds through the generic package mechanism, and preserves the original CLI.

## Releases

Release 0.1.1 adds the go-yaml adapter.
The release helper checks the build, manifest, parser, and ABI test versions.
The shared-library manifest and adapter version derive from the parser.

```bash
make release v=0.1.1 d=1
make release v=0.1.1
```

Set `a=1` to release from a feature branch.
Each release uses an unprefixed tag for native archives and a `v`-prefixed tag
for Go module builds, both pointing to the same commit.
Published tags are immutable; changes after tagging require a new version.
No Python wheels or PyPI publishing are included.

## License

MIT License.
