(ns toolbox.sync-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [toolbox.hash :as hash]
            [toolbox.sync :as sync])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDate)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "toolbox-sync-spec" (make-array FileAttribute 0))))

(describe "guard-state / guarded-write! (Prime Directive)"
  (with root (temp-root))
  (with file (io/file @root "sub" "x.md"))

  (it "writes a missing file"
    (should= :wrote (sync/guarded-write! @file "v1" nil))
    (should= "v1" (slurp @file)))

  (it "overwrites a clean file (hash matches what Toolbox last wrote)"
    (sync/guarded-write! @file "v1" nil)
    (should= :wrote (sync/guarded-write! @file "v2" (hash/sha256 "v1")))
    (should= "v2" (slurp @file)))

  (it "reports unchanged when content already matches"
    (sync/guarded-write! @file "v1" nil)
    (should= :unchanged (sync/guarded-write! @file "v1" (hash/sha256 "v1"))))

  (it "never overwrites a locally modified file"
    (sync/guarded-write! @file "v1" nil)
    (spit @file "local edit")
    (should= :protected (sync/guarded-write! @file "v2" (hash/sha256 "v1")))
    (should= "local edit" (slurp @file)))

  (it "protects a pre-existing file at bootstrap (no expected hash) unless identical"
    (io/make-parents @file)
    (spit @file "someone else's work")
    (should= :protected (sync/guard-state @file "incoming" nil))
    (should= :identical (sync/guard-state @file "someone else's work" nil))))

(describe "backups and staging"
  (with root (temp-root))

  (it "backs up under a dated directory preserving the relative path"
    (let [f (io/file @root "src" "keep.md")]
      (io/make-parents f)
      (spit f "precious")
      (let [b (sync/backup! @root f)]
        (should= "precious" (slurp b))
        (should-contain (str (LocalDate/now)) (str b))
        (should-contain "keep.md" (str b)))))

  (it "stages and clears incoming per component"
    (let [staged (sync/stage-incoming! @root "skills" "tdd" "SKILL.md" "theirs")]
      (should= "theirs" (slurp staged))
      (sync/clear-incoming! @root "skills" "tdd")
      (should-not (.exists staged))))

  (it "prunes only dated backup directories older than the retention window"
    (let [old-dir (io/file @root ".toolbox" "backup" (str (.minusDays (LocalDate/now) 45)))
          new-dir (io/file @root ".toolbox" "backup" (str (LocalDate/now)))]
      (io/make-parents (io/file old-dir "x"))
      (spit (io/file old-dir "x") "old")
      (io/make-parents (io/file new-dir "y"))
      (spit (io/file new-dir "y") "new")
      (should= [(.getName old-dir)] (sync/prune-backups! @root 30))
      (should-not (.exists old-dir))
      (should (.exists new-dir)))))

(describe "projection layout and gitignore"
  (with root (temp-root))

  (it "maps skills to nested dirs and single-file types flat"
    (should= (io/file @root ".claude" "skills" "tdd" "SKILL.md")
             (sync/projected-file @root "claude-code" "skills" "tdd" "SKILL.md"))
    (should= (io/file @root ".codex" "commands" "test.md")
             (sync/projected-file @root "codex" "commands" "test" "test.md")))

  (it "detects existing agent roots"
    (.mkdirs (io/file @root ".claude"))
    (.mkdirs (io/file @root ".grok"))
    (should= ["claude-code" "grok"] (sync/existing-agent-roots @root)))

  (it "adds .toolbox/ to .gitignore once"
    (should (sync/ensure-gitignore! @root))
    (should-not (sync/ensure-gitignore! @root))
    (should-contain ".toolbox/" (slurp (io/file @root ".gitignore")))))
