(ns toolbox.declare-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [toolbox.declare :as decl])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "toolbox-declare-spec" (make-array FileAttribute 0))))

(defn- write! [root name text]
  (let [f (io/file root name)]
    (io/make-parents f)
    (spit f text)
    f))

(describe "declaration discovery and assembly"
  (with root (temp-root))

  (it "finds the boot file by candidate order"
    (write! @root "CLAUDE.md" "x")
    (should= "CLAUDE.md" (decl/boot-file @root))
    (write! @root "AGENTS.md" "x")
    (should= "AGENTS.md" (decl/boot-file @root)))

  (it "discovers only root-level TOOLBOX.md and *.TOOLBOX.md files, sorted"
    (write! @root "TOOLBOX.md" "")
    (write! @root "micah.TOOLBOX.md" "")
    (write! @root "ratchet.TOOLBOX.md" "")
    (write! @root "notes.md" "")
    (write! @root "sub/deep.TOOLBOX.md" "")
    (should= ["TOOLBOX.md" "micah.TOOLBOX.md" "ratchet.TOOLBOX.md"]
             (decl/toolbox-md-files @root)))

  (it "assembles boot + discovered files; later files win; overrides reported"
    (write! @root "AGENTS.md"
            "## Toolbox\n### Skills\n- [tdd](https://shared/tdd/SKILL.md)\n")
    (write! @root "micah.TOOLBOX.md"
            "### Skills\n- [tdd](https://micah/tdd/SKILL.md)\n### Commands\n- [go](https://micah/go.md)\n")
    (write! @root "ratchet.TOOLBOX.md"
            "### Skills\n- [tdd](https://ratchet/tdd/SKILL.md)\n")
    (let [{:keys [components declaration-files overrides]} (decl/assemble @root)]
      (should= "https://ratchet/tdd/SKILL.md" (get-in components ["skills" "tdd" :url]))
      (should= "ratchet.TOOLBOX.md" (get-in components ["skills" "tdd" :declared-in]))
      (should= "https://micah/go.md" (get-in components ["commands" "go" :url]))
      (should= #{"AGENTS.md" "micah.TOOLBOX.md" "ratchet.TOOLBOX.md"}
               (set (keys declaration-files)))
      (should= [{:type "skills" :name "tdd" :winner "micah.TOOLBOX.md" :loser "AGENTS.md"}
                {:type "skills" :name "tdd" :winner "ratchet.TOOLBOX.md" :loser "micah.TOOLBOX.md"}]
               overrides)))

  (it "skips a boot file without a ## Toolbox section but still reads discovered files"
    (write! @root "AGENTS.md" "# no toolbox here\n### Skills\n- [x](https://x/SKILL.md)\n")
    (write! @root "micah.TOOLBOX.md" "### Commands\n- [go](https://micah/go.md)\n")
    (let [{:keys [components declaration-files]} (decl/assemble @root)]
      (should-be-nil (get-in components ["skills" "x"]))
      (should= "https://micah/go.md" (get-in components ["commands" "go" :url]))
      (should= ["micah.TOOLBOX.md"] (vec (keys declaration-files))))))
