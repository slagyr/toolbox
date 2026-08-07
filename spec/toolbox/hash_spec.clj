(ns toolbox.hash-spec
  (:require [speclj.core :refer :all]
            [toolbox.hash :as hash]))

(describe "sha256"
  (it "hashes a known string"
    (should= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             (hash/sha256 "hello")))

  (it "hashes bytes and strings identically"
    (should= (hash/sha256 "abc") (hash/sha256-bytes (.getBytes "abc" "UTF-8")))))

(describe "component-hash"
  (it "is independent of map ordering"
    (should= (hash/component-hash {"a.md" "h1" "b.md" "h2"})
             (hash/component-hash {"b.md" "h2" "a.md" "h1"})))

  (it "changes when a file's content hash changes"
    (should-not= (hash/component-hash {"a.md" "h1"})
                 (hash/component-hash {"a.md" "h2"})))

  (it "changes when a file is renamed, even with identical content hashes"
    (should-not= (hash/component-hash {"a.md" "h1"})
                 (hash/component-hash {"b.md" "h1"})))

  (it "changes when a file is added"
    (should-not= (hash/component-hash {"a.md" "h1"})
                 (hash/component-hash {"a.md" "h1" "b.md" "h2"}))))
