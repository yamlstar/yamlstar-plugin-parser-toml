// Copyright 2026 YAMLStar Authors
// MIT License

package parser_test

import (
	"testing"

	"github.com/yamlstar/yamlstar-plugin-parser-toml/parser"
)

func TestParse(t *testing.T) {
	events, err := parser.Parse([]byte("# note\nanswer = 42\n"))
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 8 {
		t.Fatalf("got %d events, want 8", len(events))
	}
	if events[2].Type != "mapping_start" ||
		events[2].HeadComment != "# note" {
		t.Fatalf("unexpected root event: %#v", events[2])
	}
	if events[4].Value != "42" {
		t.Fatalf("got value %q, want 42", events[4].Value)
	}
}

func TestParseError(t *testing.T) {
	if _, err := parser.Parse([]byte("a = [")); err == nil {
		t.Fatal("expected invalid TOML to fail")
	}
}
