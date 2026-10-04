//go:build cgo

// Copyright 2026 YAMLStar Authors
// MIT License

package main

import (
	"strings"
	"testing"
)

func TestParseEvents(t *testing.T) {
	encoded, err := parseEvents([]byte("answer = 42 # value\n"), []byte("{}"))
	if err != nil {
		t.Fatal(err)
	}
	text := string(encoded)
	for _, expected := range []string{
		`:event "scalar"`, `:value "42"`, `:tag "!!int"`,
		`:line "# value"`,
	} {
		if !strings.Contains(text, expected) {
			t.Fatalf("event output lacks %q: %s", expected, text)
		}
	}
}

func TestParseEventsRejectsConfiguration(t *testing.T) {
	if _, err := parseEvents(nil, []byte("{:x true}")); err == nil {
		t.Fatal("expected configuration error")
	}
}
