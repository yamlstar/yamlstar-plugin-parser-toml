// Copyright 2026 YAMLStar Authors
// MIT License

// Package goyaml adapts the TOML parser to go-yaml without a shared library.
package goyaml

import (
	"errors"

	"github.com/yamlstar/yamlstar-plugin-parser-toml/parser"
	"go.yaml.in/yaml/v4"
)

// Version is the linked TOML parser release version.
const Version = parser.Version

// Plugin parses TOML using the compiled Go parser.
type Plugin struct{}

var _ yaml.ParserPlugin = (*Plugin)(nil)

// New creates a TOML parser plugin.
func New() *Plugin { return &Plugin{} }

// Register enables yaml-parser=toml without changing native defaults.
// Call it during startup, before parsing configuration.
// Importing this package does not register it; duplicate calls are errors.
func Register() error {
	return yaml.RegisterPlugin(yaml.PluginRegistration{
		API: "yaml-parser", Name: "toml", Version: Version,
		Factory: func(cfg map[string]any) (any, error) {
			if len(cfg) != 0 {
				return nil, errors.New("TOML parser configuration must be empty")
			}
			return New(), nil
		},
	})
}

// Parse converts TOML to a complete YAML event stream.
func (p *Plugin) Parse(input []byte) ([]yaml.PluginEvent, error) {
	source, err := parser.Parse(input)
	if err != nil {
		return nil, err
	}
	events := make([]yaml.PluginEvent, len(source))
	for i, event := range source {
		events[i] = yaml.PluginEvent{
			Type: event.Type, Value: event.Value, Style: event.Style,
			Tag: event.Tag, HeadComment: event.HeadComment,
			LineComment: event.LineComment, FootComment: event.FootComment,
		}
	}
	return events, nil
}
