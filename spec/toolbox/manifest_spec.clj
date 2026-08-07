(ns toolbox.manifest-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [toolbox.hash :as hash]
            [toolbox.manifest :as manifest])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root []
  (.toFile (Files/createTempDirectory "toolbox-manifest-spec" (make-array FileAttribute 0))))

(describe "manifest"
  (with root (temp-root))

  (it "round-trips through save! and load-manifest"
    (let [m {"skills" {"tdd" {"url" "https://x" "files" {"SKILL.md" "abc"}}}
             "supported_agents" ["claude-code"]
             "declaration_files" {"AGENTS.md" "def"}}]
      (manifest/save! @root m)
      (should= m (manifest/load-manifest @root))))

  (it "returns nil when no manifest exists"
    (should-be-nil (manifest/load-manifest @root)))

  (it "locates cache dirs per type"
    (should= (io/file @root ".toolbox" "skills" "tdd") (manifest/cache-dir @root "skills" "tdd"))
    (should= (io/file @root ".toolbox" "commands") (manifest/cache-dir @root "commands" "test")))

  (it "migrates a files list to a path->hash map from cache content"
    (let [cache (io/file @root ".toolbox" "commands" "test.md")]
      (io/make-parents cache)
      (spit cache "run the tests")
      (let [old {"commands" {"test" {"url" "https://x" "files" ["test.md"]}}}
            m   (manifest/migrate @root old)
            entry (get-in m ["commands" "test"])]
        (should= {"test.md" (hash/sha256 "run the tests")} (get entry "files"))
        (should= (hash/component-hash (get entry "files")) (get entry "sha256")))))

  (it "adds supported_agents and declaration_files, drops legacy fields"
    (let [m (manifest/migrate @root {"projections" {"claude-code" {}} "active_agent" "x"})]
      (should= [] (get m "supported_agents"))
      (should= {} (get m "declaration_files"))
      (should-be-nil (get m "projections"))
      (should-be-nil (get m "active_agent"))))

  (it "leaves an already-migrated files map untouched"
    (let [old {"commands" {"test" {"url" "https://x" "files" {"test.md" "abc"}}}}]
      (should= old (select-keys (manifest/migrate @root old) ["commands"])))))
