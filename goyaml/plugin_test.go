// Copyright 2026 YAMLStar Authors
// MIT License

package goyaml_test

import (
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"

	"github.com/yamlstar/yamlstar-plugin-parser-toml/goyaml"
	"go.yaml.in/yaml/v4"
	"go.yaml.in/yaml/v4/plugin/loaderlimits"
)

func TestFixtures(t *testing.T) {
	data, err := os.ReadFile("testdata/parse.yaml")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct{ Name, TOML, Want, Error string }
	if err := yaml.Load(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			var got, want any
			err := yaml.Load([]byte(tc.TOML), &got, yaml.WithPlugin(goyaml.New()))
			if tc.Error != "" {
				if err == nil || !strings.Contains(err.Error(), tc.Error) {
					t.Fatalf("got %v, want error containing %q", err, tc.Error)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if err := yaml.Load([]byte(tc.Want), &want); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(got, want) {
				t.Fatalf("got %#v, want %#v", got, want)
			}
		})
	}
}

func TestCommentsAndTags(t *testing.T) {
	input := "# file\nkey = [\n  # item\n  1, # one\n  2\n] # values\n"
	events, err := goyaml.New().Parse([]byte(input))
	if err != nil {
		t.Fatal(err)
	}
	if events[2].HeadComment != "# file" {
		t.Fatalf("root comment: %#v", events[2])
	}
	comments := map[string]bool{}
	for _, event := range events {
		for _, comment := range []string{event.HeadComment, event.LineComment} {
			comments[comment] = true
		}
		if event.Value == "1" && event.Tag != "!!int" &&
			event.Tag != "tag:yaml.org,2002:int" {
			t.Fatalf("integer tag lost: %#v", event)
		}
	}
	for _, comment := range []string{"# item", "# one", "# values"} {
		if !comments[comment] {
			t.Fatalf("lost comment %q", comment)
		}
	}
	var node yaml.Node
	if err := yaml.Load([]byte("value = '42'\n"), &node,
		yaml.WithPlugin(goyaml.New())); err != nil {
		t.Fatal(err)
	}
	root := &node
	if node.Kind == yaml.DocumentNode {
		root = node.Content[0]
	}
	value := root.Content[1]
	if value.Kind != yaml.ScalarNode || value.ShortTag() != "!!str" {
		t.Fatalf("string scalar: %#v", value)
	}
}

func TestRegistration(t *testing.T) {
	if os.Getenv("TOML_REGISTER_TEST") != "1" {
		cmd := exec.Command(os.Args[0], "-test.run=^TestRegistration$")
		cmd.Env = append(os.Environ(), "TOML_REGISTER_TEST=1")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("registration: %v\n%s", err, out)
		}
		return
	}
	if _, err := yaml.OptsYAML("plugin: {yaml-parser: toml}"); err == nil {
		t.Fatal("import registered the adapter implicitly")
	}
	if err := goyaml.Register(); err != nil {
		t.Fatal(err)
	}
	var native map[string]any
	if err := yaml.Load([]byte("native: true\n"), &native); err != nil ||
		native["native"] != true {
		t.Fatalf("native defaults changed: %#v, %v", native, err)
	}
	for _, config := range []string{
		"plugin: {yaml-parser: toml}",
		"plugin: {yaml-parser: toml@v" + goyaml.Version + "}",
		"plugin: {yaml-parser: {name: toml, version: " + goyaml.Version + "}}",
	} {
		opt, err := yaml.OptsYAML(config)
		if err != nil {
			t.Fatal(err)
		}
		var got map[string]any
		if err := yaml.Load([]byte("answer = 42\n"), &got, opt); err != nil ||
			got["answer"] != 42 {
			t.Fatalf("config %q: %#v, %v", config, got, err)
		}
	}
	for _, config := range []string{
		"plugin: {yaml-parser: toml@v9.8.7}",
		"plugin: {yaml-parser: {name: toml, install: true}}",
	} {
		if _, err := yaml.OptsYAML(config); err == nil {
			t.Fatalf("accepted invalid config %q", config)
		}
	}
	if err := goyaml.Register(); err == nil {
		t.Fatal("duplicate registration accepted")
	}
}

func TestConcurrentLoads(t *testing.T) {
	p := goyaml.New()
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var got map[string]any
			if err := yaml.Load([]byte("answer = 42\n"), &got,
				yaml.WithPlugin(p)); err != nil || got["answer"] != 42 {
				t.Errorf("concurrent parse: %#v, %v", got, err)
			}
		}()
	}
	wg.Wait()
}

func TestInvalidUTF8AndLimits(t *testing.T) {
	if _, err := goyaml.New().Parse([]byte{0xff}); err == nil ||
		!strings.Contains(err.Error(), "UTF-8") {
		t.Fatalf("invalid UTF-8: %v", err)
	}
	var value any
	err := yaml.Load([]byte("[a.b]\nvalue = 1\n"), &value,
		yaml.WithPlugin(goyaml.New(), loaderlimits.New(loaderlimits.DepthValue(1))))
	if err == nil || !strings.Contains(err.Error(), "depth") {
		t.Fatalf("loader limits: %v", err)
	}
}

func TestCLI(t *testing.T) {
	binary := os.Getenv("GO_YAML_CLI")
	if binary == "" {
		t.Skip("set GO_YAML_CLI to test the generic CLI build")
	}
	data, err := os.ReadFile("testdata/cli.yaml")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name, Input, Want, Error, Config string
		Flags                            []string
	}
	if err := yaml.Load(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			flags := append([]string(nil), tc.Flags...)
			if tc.Config != "" {
				config := filepath.Join(t.TempDir(), "options.yaml")
				if err := os.WriteFile(config, []byte(tc.Config), 0o600); err != nil {
					t.Fatal(err)
				}
				flags = append(flags, "-C", config)
			}
			cmd := exec.Command(binary, flags...)
			cmd.Stdin = strings.NewReader(tc.Input)
			out, err := cmd.CombinedOutput()
			if tc.Error != "" {
				if err == nil || !strings.Contains(string(out), tc.Error) {
					t.Fatalf("got %q, %v; want error %q", out, err, tc.Error)
				}
				return
			}
			if err != nil || strings.TrimSpace(string(out)) != strings.TrimSpace(tc.Want) {
				t.Fatalf("got %q, %v; want %s", out, err, tc.Want)
			}
		})
	}
}
