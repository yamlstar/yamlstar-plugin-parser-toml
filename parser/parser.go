// Copyright 2026 YAMLStar Authors
// MIT License

// Package parser exposes the TOML parser to Go callers.
package parser

import (
	"fmt"
	"sync"
	"unicode/utf8"

	ysruntime "github.com/gloathub/ys-v0-glj/runtime"
	"github.com/glojurelang/glojure/pkg/glj"
	"github.com/glojurelang/glojure/pkg/lang"
	_ "github.com/yamlstar/yamlstar-plugin-parser-toml/pkg/toml_parser/core"
)

// Version is the parser release version.
const Version = "0.1.0"

// Event is a portable YAML parser event.
type Event struct {
	Type        string
	Value       string
	Style       string
	Tag         string
	TOMLType    string
	HeadComment string
	LineComment string
	FootComment string
}

var runtimeLock sync.Mutex

// Parse converts a TOML 1.1 document into a complete YAML event stream.
func Parse(input []byte) (events []Event, err error) {
	if !utf8.Valid(input) {
		return nil, fmt.Errorf("toml parser: input is not valid UTF-8")
	}
	runtimeLock.Lock()
	defer runtimeLock.Unlock()
	defer func() {
		if value := recover(); value != nil {
			events = nil
			err = fmt.Errorf("toml parser: %v", value)
		}
	}()

	ysruntime.Load()
	require := glj.Var("clojure.core", "require")
	require.Invoke(lang.NewSymbol("toml-parser.core"))
	value := glj.Var("toml-parser.core", "parse").Invoke(string(input))
	for sequence := lang.Seq(value); sequence != nil; sequence = sequence.Next() {
		event, err := decodeEvent(sequence.First())
		if err != nil {
			return nil, err
		}
		events = append(events, event)
	}
	return events, nil
}

func decodeEvent(value any) (Event, error) {
	if _, ok := value.(lang.ILookup); !ok {
		return Event{}, fmt.Errorf(
			"toml parser: event has type %T, expected a map", value)
	}
	event := Event{
		Type:        mapString(value, "event"),
		Value:       mapString(value, "value"),
		Style:       mapString(value, "style"),
		Tag:         mapString(value, "tag"),
		TOMLType:    mapString(value, "toml-type"),
		HeadComment: mapString(value, "head"),
		LineComment: mapString(value, "line"),
		FootComment: mapString(value, "foot"),
	}
	if event.Type == "" {
		return Event{}, fmt.Errorf("toml parser: event type is empty")
	}
	return event, nil
}

func mapString(value any, key string) string {
	item := lang.Get(value, lang.NewKeyword(key))
	if item == nil {
		return ""
	}
	return item.(string)
}
