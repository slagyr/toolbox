(ns toolbox.core-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [toolbox.core :as core]
            [toolbox.manifest :as manifest])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "toolbox-core-spec" (make-array FileAttribute 0))))

(defn- write! [root path text]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f text)
    f))

(defn- fixture-project
  "A project whose components come from a local source dir via relative
   file:// urls — end-to-end without network."
  [root]
  (write! root "lib/skills/tdd/SKILL.md"
          "# TDD\nSee [details](references/details.md).")
  (write! root "lib/skills/tdd/references/details.md" "the details")
  (write! root "lib/commands/test.md" "run bb spec")
  (write! root "AGENTS.md"
          (str "## Toolbox\n"
               "### Skills\n- [tdd](file://./lib/skills/tdd/SKILL.md)\n"
               "### Commands\n- [test](file://./lib/commands/test.md)\n"))
  (.mkdirs (io/file root ".claude")))

(describe "file:// skills cache their whole source directory"
  (with root (temp-root))

  (it "records and projects assets never mentioned in markdown links"
    (write! @root "lib/skills/brand/SKILL.md"
            "# Brand\nMarque: `assets/marque.svg` | table cell |")
    (write! @root "lib/skills/brand/assets/marque.svg" "<svg>marque</svg>")
    (write! @root "lib/skills/brand/assets/lockup.svg" "<svg>lockup</svg>")
    (write! @root "AGENTS.md"
            "## Toolbox\n### Skills\n- [brand](file://./lib/skills/brand/SKILL.md)\n")
    (.mkdirs (io/file @root ".claude"))
    (let [result (core/update! @root {})
          files  (get-in (manifest/load-manifest @root) ["skills" "brand" "files"])]
      (should (:ok result))
      (should= #{"SKILL.md" "assets/marque.svg" "assets/lockup.svg"} (set (keys files)))
      (should= "<svg>lockup</svg>"
               (slurp (io/file @root ".claude" "skills" "brand" "assets" "lockup.svg")))
      (should= [] (:warnings result))))

  (it "reaps files dropped from a component's set, backing up local edits"
    (write! @root "lib/skills/brand/SKILL.md" "# Brand")
    (write! @root "lib/skills/brand/assets/marque.svg" "<svg>marque</svg>")
    (write! @root "lib/skills/brand/assets/lockup.svg" "<svg>lockup</svg>")
    (write! @root "AGENTS.md"
            "## Toolbox\n### Skills\n- [brand](file://./lib/skills/brand/SKILL.md)\n")
    (.mkdirs (io/file @root ".claude"))
    (core/update! @root {})
    (spit (io/file @root ".toolbox" "skills" "brand" "assets" "lockup.svg") "local tweak")
    (io/delete-file (io/file @root "lib/skills/brand/assets/lockup.svg"))
    (let [result (core/update! @root {})
          files  (get-in (manifest/load-manifest @root) ["skills" "brand" "files"])]
      (should (:ok result))
      (should= #{"SKILL.md" "assets/marque.svg"} (set (keys files)))
      (should-not (.exists (io/file @root ".toolbox" "skills" "brand" "assets" "lockup.svg")))
      (should-not (.exists (io/file @root ".claude" "skills" "brand" "assets" "lockup.svg")))
      (should= 1 (count (:backed-up result)))
      (should= "local tweak" (slurp (io/file @root (first (:backed-up result)))))
      (should= 2 (count (:removed-files result))))))

(describe "update! end to end (file:// fixtures)"
  (with root (temp-root))
  (before (fixture-project @root))

  (it "bootstraps: cache, references, manifest, projection, gitignore"
    (let [result (core/update! @root {})]
      (should (:ok result))
      (should= "run bb spec" (slurp (io/file @root ".toolbox" "commands" "test.md")))
      (should= "the details" (slurp (io/file @root ".toolbox" "skills" "tdd" "references" "details.md")))
      (should= "run bb spec" (slurp (io/file @root ".claude" "commands" "test.md")))
      (should= ["claude-code"] (:supported-agents result))
      (should-contain ".toolbox/" (slurp (io/file @root ".gitignore")))
      (let [m (manifest/load-manifest @root)]
        (should= "AGENTS.md" (get-in m ["commands" "test" "declared_in"]))
        (should= 2 (count (get-in m ["skills" "tdd" "files"]))))))

  (it "applies a clean upstream change on update"
    (core/update! @root {})
    (write! @root "lib/commands/test.md" "run bb spec --verbose")
    (let [result (core/update! @root {})]
      (should (:ok result))
      (should= [] (:attention result))
      (should= "run bb spec --verbose" (slurp (io/file @root ".claude" "commands" "test.md")))))

  (it "keeps a local edit, stages incoming, and flags attention"
    (core/update! @root {})
    (spit (io/file @root ".toolbox" "commands" "test.md") "locally improved")
    (write! @root "lib/commands/test.md" "upstream moved")
    (let [result (core/update! @root {})]
      (should (:ok result))
      (should= "locally improved" (slurp (io/file @root ".toolbox" "commands" "test.md")))
      (should= "upstream moved" (slurp (io/file @root ".toolbox" "incoming" "commands" "test" "test.md")))
      (should= ["locally-modified"] (distinct (map :reason (:attention result))))))

  (it "protects a locally edited projection without touching it"
    (core/update! @root {})
    (spit (io/file @root ".claude" "commands" "test.md") "my projection tweak")
    (let [result (core/update! @root {})]
      (should= "my projection tweak" (slurp (io/file @root ".claude" "commands" "test.md")))
      (should-contain ".claude/commands/test.md" (map :file (:attention result)))))

  (it "removes an undeclared component, backing up local modifications first"
    (core/update! @root {})
    (spit (io/file @root ".toolbox" "commands" "test.md") "precious local work")
    (write! @root "AGENTS.md"
            "## Toolbox\n### Skills\n- [tdd](file://./lib/skills/tdd/SKILL.md)\n")
    (let [result (core/update! @root {})]
      (should= [{:type "commands" :name "test"}] (:removed result))
      (should-not (.exists (io/file @root ".toolbox" "commands" "test.md")))
      (should-not (.exists (io/file @root ".claude" "commands" "test.md")))
      (should= 1 (count (:backed-up result)))
      (should= "precious local work" (slurp (io/file @root (first (:backed-up result)))))
      (should-be-nil (get-in (manifest/load-manifest @root) ["commands" "test"]))))

  (it "honors a *.TOOLBOX.md override of the boot file"
    (write! @root "micah.TOOLBOX.md"
            "### Commands\n- [test](file://./lib/commands/test2.md)\n")
    (write! @root "lib/commands/test2.md" "micah's version")
    (let [result (core/update! @root {})]
      (should (:ok result))
      (should= [{:type "commands" :name "test" :winner "micah.TOOLBOX.md" :loser "AGENTS.md"}]
               (:overrides result))
      (should= "micah's version" (slurp (io/file @root ".claude" "commands" "test.md")))))

  (it "reports status: upstream change and local drift, never verified for file://"
    (core/update! @root {})
    (write! @root "lib/commands/test.md" "upstream moved")
    (spit (io/file @root ".claude" "skills" "tdd" "SKILL.md") "drifted")
    (let [status (core/status @root)
          by-name (into {} (map (juxt :name identity) (:components status)))]
      (should (get-in by-name ["test" :changed-upstream]))
      (should-not (get-in by-name ["test" :verified]))
      (should-contain ".claude/skills/tdd/SKILL.md"
                      (get-in by-name ["tdd" :locally-modified])))))

(describe "enroll and unenroll"
  (with root (temp-root))
  (before (fixture-project @root))

  (it "enroll projects into the new agent root; unenroll removes managed files only"
    (core/update! @root {})
    (let [result (core/enroll! @root "codex")]
      (should (:ok result))
      (should= "run bb spec" (slurp (io/file @root ".codex" "commands" "test.md"))))
    (write! @root ".codex/notes.md" "unmanaged")
    (let [result (core/unenroll! @root "codex")]
      (should (:ok result))
      (should-not (.exists (io/file @root ".codex" "commands" "test.md")))
      (should (.exists (io/file @root ".codex" "notes.md")))
      (should= ["claude-code"] (:supported-agents result))))

  (it "rejects unknown agents"
    (should-not (:ok (core/enroll! @root "emacs")))))
