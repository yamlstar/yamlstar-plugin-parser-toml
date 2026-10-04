# Auto-install https://github.com/makeplus/makes.
MAKES ?= .cache/makes
$(shell test -d $(MAKES) || \
  git clone -q https://github.com/makeplus/makes $(MAKES))

include $(MAKES)/init.mk
include $(MAKES)/clojure.mk
GLOAT-VERSION := 0.1.90
include $(MAKES)/gloat.mk
include $(MAKES)/gh.mk
include $(MAKES)/go.mk
include $(MAKES)/perl.mk
include $(MAKES)/shellcheck.mk
YAMLSCRIPT-VERSION := 0.3.4
include $(MAKES)/yamlscript.mk
include $(MAKES)/clean.mk

SHELL-NAME := makes yamlstar-plugin-parser-toml
include $(MAKES)/shell.mk

CLJ-CONFIG := $(CURDIR)/.cache/clojure
VERSION := 0.1.0
PLUGIN := yamlstar-plugin-parser-toml
GENERATED-CLJ := generated/clj/toml_parser/core.clj
GLOAT-LINK := .cache/gloat-srcs/toml_parser/core.clj
GO-GENERATED-WORK := $(abspath .cache/go-generate)
GO-GENERATED-DIR := pkg/toml_parser/core
GO-MODULE := github.com/yamlstar/yamlstar-plugin-parser-toml
TOML-TEST-VERSION := v2.2.0
TOML-TEST-DIR ?= .cache/toml-test
TOML-TEST-STAMP := $(TOML-TEST-DIR)/.yamlstar-version
TOML-TEST := .cache/bin/toml-test
TOML-TEST-DECODER := .cache/bin/toml-test-decoder
SO ?= $(if $(filter macos,$(OS-NAME)),dylib,so)
SHARED-LIB := lib/libyamlstar-plugin-parser-toml.$(SO)
LIB-NAME := libyamlstar-plugin-parser-toml.$(SO)
RELEASE-ARCH := $(if $(IS-INTEL),x64,\
  $(if $(IS-LINUX),aarch64,arm64))
RELEASE-PLATFORM ?= $(OS-NAME)-$(RELEASE-ARCH)
RELEASE-NAME := $(PLUGIN)-$(VERSION)-$(RELEASE-PLATFORM)
RELEASE-DIR := dist/$(RELEASE-NAME)
RELEASE-LIB-DIR := .cache/release/lib
RELEASE-LIB := $(RELEASE-LIB-DIR)/$(LIB-NAME)
ARCHIVE := dist/$(RELEASE-NAME).tar.xz
SOURCE-DATE-EPOCH ?= $(shell git log -1 --format=%ct 2>/dev/null || date +%s)
TAR ?= $(if $(IS-MACOS),gtar,tar)
RELEASE-REPO := yamlstar/yamlstar-plugin-parser-toml
RELEASE-WORKFLOW := release.yaml
RELEASE-SCRIPT := util/release
RELEASE-CMD = \
  PERL=$(PERL) \
  GH=$(GH) \
  RELEASE_REPO=$(RELEASE-REPO) \
  RELEASE_WORKFLOW=$(RELEASE-WORKFLOW) \
  $(RELEASE-SCRIPT)

MAKES-CLEAN += dist .cache/release

$(GENERATED-CLJ): src/toml_parser/core.ys $(YS) $(PERL)
	mkdir -p $(dir $@)
	$(YS) --compile $< | \
	  $(PERL) -0777 -pe \
	  'BEGIN { print "(ns toml-parser.core (:require ys.v0))\n"; \
	  print "(ys.v0/init)\n" }' > $@.tmp
	mv $@.tmp $@

$(GLOAT-LINK): $(GENERATED-CLJ)
	mkdir -p $(dir $@)
	ln -sf $(abspath $<) $@

$(CLJ-CONFIG):
	mkdir -p $@

test: $(CLOJURE) $(GO) $(CLJ-CONFIG) generate $(SHARED-LIB) $(SHELLCHECK)
	CLJ_CONFIG=$(CLJ-CONFIG) $(CLOJURE) -M:test
	env -u GOROOT CGO_ENABLED=0 $(GO) test ./...
	env -u GOROOT CGO_ENABLED=1 $(GO) test ./shared
	$(call compile-abi-test,.cache/abi-test)
	YAMLSTAR_LIBRARY_PATH=$(abspath lib) .cache/abi-test
	$(SHELLCHECK) util/release util/test-archive

test-yamlstar: $(CLOJURE) $(CLJ-CONFIG)
	CLJ_CONFIG=$(CLJ-CONFIG) $(CLOJURE) -M:yamlstar-test

$(SHARED-LIB): generate $(GO)
	mkdir -p $(dir $@)
	env -u GOROOT CGO_ENABLED=1 $(GO) build -buildmode=c-shared \
	  -o $@ ./shared

build: $(SHARED-LIB)

$(RELEASE-LIB): generate $(GO)
	mkdir -p $(dir $@)
	env -u GOROOT CGO_ENABLED=1 $(GO) build -trimpath \
	  -ldflags='-s -w' -buildmode=c-shared -o $@ ./shared

release-check: $(PERL)
	version=$$($(PERL) -ne \
	  'print "$$1\n" if /^version:\s*(\S+)/' Meta); \
	  test "$$version" = "$(VERSION)"
	version=$$($(PERL) -ne \
	  'print "$$1\n" if /:version "([^"]+)"/' plugin.edn); \
	  test "$$version" = "$(VERSION)"
	case $(RELEASE-PLATFORM) in \
	  linux-x64|linux-aarch64|macos-x64|macos-arm64) ;; \
	  *) echo "Unsupported release platform: $(RELEASE-PLATFORM)" >&2; \
	     exit 1 ;; \
	esac

release-archive: $(ARCHIVE)

export OLD_VERSION := $o
export NEW_VERSION := $(or $v,$n)
ifdef d
export YS_RELEASE_DRYRUN := 1
endif
ifdef a
export YS_RELEASE_ALLOW_BRANCH := 1
endif

release: $(PERL) $(GH)
ifndef v
	$(error 'make release' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) release "$(o)" "$(v)"

release-list: $(PERL)
	$(RELEASE-CMD) list

release-sanity-check: $(PERL)
ifndef v
	$(error 'make release-sanity-check' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) sanity-check "$(o)" "$(v)"

release-version-bump: $(PERL)
ifndef v
	$(error 'make release-version-bump' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) version-bump "$(o)" "$(v)"

release-pull: $(PERL)
	$(RELEASE-CMD) pull

release-commit: $(PERL)
ifndef v
	$(error 'make release-commit' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) commit "$(v)"

release-tag: $(PERL)
ifndef v
	$(error 'make release-tag' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) tag "$(v)"

release-push: $(PERL)
ifndef v
	$(error 'make release-push' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) push "$(v)"

release-build-github: $(PERL) $(GH)
ifndef v
	$(error 'make release-build-github' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) build-github "$(v)"

release-retry: $(PERL) $(GH)
ifndef v
	$(error 'make release-retry' requires v=NEW_VERSION)
endif
	$(RELEASE-CMD) retry "$(v)"

$(ARCHIVE): release-check test $(RELEASE-LIB) plugin.edn \
  include/yamlstar_plugin.h License ReadMe.md util/test-archive
	rm -rf $(RELEASE-DIR) $@
	install -d \
	  $(RELEASE-DIR)/lib \
	  $(RELEASE-DIR)/include/yamlstar \
	  $(RELEASE-DIR)/share/yamlstar/plugins/parser-toml
	install -m 755 $(RELEASE-LIB) $(RELEASE-DIR)/lib/$(LIB-NAME)
	install -m 644 include/yamlstar_plugin.h \
	  $(RELEASE-DIR)/include/yamlstar/
	install -m 644 plugin.edn \
	  $(RELEASE-DIR)/share/yamlstar/plugins/parser-toml/
	install -m 644 License ReadMe.md $(RELEASE-DIR)/
	COPYFILE_DISABLE=1 $(TAR) \
	  --sort=name \
	  --mtime=@$(SOURCE-DATE-EPOCH) \
	  --owner=0 --group=0 --numeric-owner \
	  -C dist -cJf $@ $(RELEASE-NAME)
	$(MAKE) test-archive ARCHIVE=$@ \
	  RELEASE-PLATFORM=$(RELEASE-PLATFORM) VERSION=$(VERSION)

test-archive: $(SHELLCHECK)
	$(SHELLCHECK) util/test-archive
	util/test-archive $(ARCHIVE) $(RELEASE-PLATFORM) $(VERSION)

define compile-abi-test
	$(CC) -Wall -Wextra -Werror -Iinclude \
	  -DPLUGIN_EXTENSION='"$(SO)"' \
	  -DPLUGIN_VERSION='"$(VERSION)"' \
	  test/abi.c $(if $(IS-MACOS),,-ldl) -o $(1)
endef

install: $(SHARED-LIB)
	install -d $(PREFIX)/lib
	install -m 755 $(SHARED-LIB) $(PREFIX)/lib/

$(TOML-TEST-STAMP):
	git clone -q --depth=1 --branch $(TOML-TEST-VERSION) \
	  https://github.com/toml-lang/toml-test $(TOML-TEST-DIR)
	printf '%s\n' $(TOML-TEST-VERSION) > $@

$(TOML-TEST): $(GO) $(TOML-TEST-STAMP)
	mkdir -p $(dir $@)
	env -u GOROOT $(GO) -C $(TOML-TEST-DIR) build \
	  -o $(abspath $@) ./cmd/toml-test

$(TOML-TEST-DECODER): generate $(GO)
	mkdir -p $(dir $@)
	env -u GOROOT $(GO) build -o $@ ./cmd/toml-test-decoder

test-conformance: $(TOML-TEST) $(TOML-TEST-DECODER)
	$(TOML-TEST) test -toml=1.1 -decoder=$(abspath $(TOML-TEST-DECODER))

generate: $(GENERATED-CLJ) $(GLOAT-LINK) $(GLOAT)
	rm -fr $(GO-GENERATED-WORK)
	mkdir -p $(GO-GENERATED-WORK)
	env -u GOROOT GLOAT_GLJDEPS=$(abspath gljdeps.edn) \
	  $(GLOAT) --force \
	  --module $(GO-MODULE) \
	  -o $(GO-GENERATED-WORK)/ \
	  $(GLOAT-LINK)
	test -f $(GO-GENERATED-WORK)/pkg/toml_parser/core/loader.go
	rm -fr $(GO-GENERATED-DIR)
	mkdir -p $(dir $(GO-GENERATED-DIR))
	cp -R $(GO-GENERATED-WORK)/pkg/toml_parser/core \
	  $(GO-GENERATED-DIR)
	rm -fr $(GO-GENERATED-WORK)

generate-check: generate
	@git diff --exit-code -- $(GENERATED-CLJ) pkg/toml_parser/core

clean::
	rm -fr target lib .cpcache .cache/gloat-srcs .cache/go-generate
