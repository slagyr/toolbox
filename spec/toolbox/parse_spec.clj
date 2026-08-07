(ns toolbox.parse-spec
  (:require [speclj.core :refer :all]
            [toolbox.parse :as parse]))

(def boot-text
  "# My Project

## Toolbox

Uses [toolbox](https://example.com/SKILL.md).

### Skills

- [tdd](https://example.com/skills/tdd/SKILL.md)
- [braids](file://./lib/braids/SKILL.md)

### Commands

- [test](https://example.com/commands/test.md)

## Another Section

- [not-a-component](https://example.com/nope.md)
")

(describe "toolbox-section"
  (it "extracts the ## Toolbox section up to the next ## heading"
    (let [section (parse/toolbox-section boot-text)]
      (should-contain "### Skills" section)
      (should-not-contain "Another Section" section)
      (should-not-contain "not-a-component" section)))

  (it "returns nil when there is no ## Toolbox heading"
    (should-be-nil (parse/toolbox-section "# Readme\n\n### Skills\n- [x](url)")))

  (it "declared-section falls back to the whole text for headerless files"
    (let [text "### Skills\n- [tdd](https://x/SKILL.md)"]
      (should= text (parse/declared-section text)))))

(describe "declarations"
  (it "parses names, urls, and types in order"
    (should= [{:type "skills" :name "tdd" :url "https://example.com/skills/tdd/SKILL.md"}
              {:type "skills" :name "braids" :url "file://./lib/braids/SKILL.md"}
              {:type "commands" :name "test" :url "https://example.com/commands/test.md"}]
             (parse/declarations (parse/toolbox-section boot-text))))

  (it "ignores bullets outside a recognized subsection"
    (should= [] (parse/declarations "- [x](https://example.com/x.md)")))

  (it "ignores unrecognized subsection headings"
    (should= [] (parse/declarations "### Gadgets\n- [x](https://example.com/x.md)"))))

(describe "reference-paths"
  (it "finds relative markdown links"
    (should= ["references/details.md" "examples/demo.md"]
             (parse/reference-paths
              "See [details](references/details.md) and [demo](examples/demo.md).")))

  (it "excludes absolute urls, anchors, absolute paths, and mailto"
    (should= []
             (parse/reference-paths
              (str "[a](https://x.com/a.md) [b](#section) "
                   "[c](/abs/path.md) [d](mailto:x@y.z) [e](file:///tmp/x.md)"))))

  (it "strips anchor suffixes and dedupes"
    (should= ["notes.md"]
             (parse/reference-paths "[a](notes.md#one) [b](notes.md#two) [c](notes.md)"))))
