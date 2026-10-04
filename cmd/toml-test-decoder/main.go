// Copyright 2026 YAMLStar Authors
// MIT License

// Command toml-test-decoder adapts the parser to the toml-test protocol.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	"github.com/yamlstar/yamlstar-plugin-parser-toml/parser"
)

func main() {
	input, err := io.ReadAll(os.Stdin)
	if err == nil {
		var events []parser.Event
		events, err = parser.Parse(input)
		if err == nil {
			var value any
			value, err = decode(events)
			if err == nil {
				err = json.NewEncoder(os.Stdout).Encode(value)
			}
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func decode(events []parser.Event) (any, error) {
	if len(events) < 5 || events[0].Type != "stream_start" ||
		events[1].Type != "document_start" {
		return nil, fmt.Errorf("invalid parser event stream")
	}
	value, next, err := decodeNode(events, 2)
	if err != nil {
		return nil, err
	}
	if next+2 != len(events) || events[next].Type != "document_end" ||
		events[next+1].Type != "stream_end" {
		return nil, fmt.Errorf("invalid parser event boundaries")
	}
	return value, nil
}

func decodeNode(events []parser.Event, index int) (any, int, error) {
	if index >= len(events) {
		return nil, index, fmt.Errorf("unexpected end of parser events")
	}
	event := events[index]
	switch event.Type {
	case "scalar":
		if event.TOMLType == "" {
			return event.Value, index + 1, nil
		}
		return map[string]string{
			"type": event.TOMLType,
			"value": tomlValue(event),
		}, index + 1, nil
	case "sequence_start":
		items := []any{}
		index++
		for index < len(events) && events[index].Type != "sequence_end" {
			item, next, err := decodeNode(events, index)
			if err != nil {
				return nil, index, err
			}
			items = append(items, item)
			index = next
		}
		if index >= len(events) {
			return nil, index, fmt.Errorf("unclosed sequence events")
		}
		return items, index + 1, nil
	case "mapping_start":
		mapping := map[string]any{}
		index++
		for index < len(events) && events[index].Type != "mapping_end" {
			key := events[index]
			if key.Type != "scalar" {
				return nil, index, fmt.Errorf("mapping key is not scalar")
			}
			value, next, err := decodeNode(events, index+1)
			if err != nil {
				return nil, index, err
			}
			mapping[key.Value] = value
			index = next
		}
		if index >= len(events) {
			return nil, index, fmt.Errorf("unclosed mapping events")
		}
		return mapping, index + 1, nil
	default:
		return nil, index, fmt.Errorf("unexpected parser event %q", event.Type)
	}
}

func tomlValue(event parser.Event) string {
	if event.TOMLType != "float" {
		return event.Value
	}
	switch event.Value {
	case ".inf":
		return "inf"
	case "-.inf":
		return "-inf"
	case ".nan":
		return "nan"
	default:
		return event.Value
	}
}
