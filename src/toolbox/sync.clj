(ns toolbox.sync
  (:require [clojure.java.io :as io]
            [toolbox.hash :as hash])
  (:import (java.time LocalDate)))

(def agent-roots
  {"claude-code" ".claude"
   "opencode"    ".opencode"
   "grok"        ".grok"
   "cursor"      ".cursor"
   "codex"       ".codex"})

(defn existing-agent-roots
  "Known agent names whose root directory already exists in the project."
  [root]
  (->> agent-roots
       (filter (fn [[_ dir]] (.isDirectory (io/file root dir))))
       (map first)
       sort
       vec))

(defn projected-file
  "Projection target for one file of a component under an agent root."
  [root agent type name rel]
  (let [agent-dir (agent-roots agent)]
    (if (= type "skills")
      (io/file root agent-dir "skills" name rel)
      (io/file root agent-dir type rel))))

(defn file-state
  "Prime Directive check: :missing, :clean (hash matches what Toolbox last
   wrote), or :protected (locally modified — never overwrite silently)."
  [file expected-hash]
  (cond
    (not (.exists (io/file file))) :missing
    (= (hash/sha256-file file) expected-hash) :clean
    :else :protected))

(defn guard-state
  "Prime Directive state considering the incoming content too.
   :missing — nothing there; :clean — exactly what Toolbox last wrote;
   :identical — already equals the incoming content (nothing to do);
   :protected — locally modified, never overwrite silently.
   With no expected hash (bootstrap), an existing file is compared to the
   incoming content directly."
  [file content expected-hash]
  (cond
    (not (.exists (io/file file)))                             :missing
    (= (slurp file) content)                                   :identical
    (and expected-hash
         (= (hash/sha256-file file) expected-hash))            :clean
    :else                                                      :protected))

(defn guarded-write!
  "Write content only when the Prime Directive allows it.
   Returns :wrote, :unchanged, or :protected (file untouched)."
  [file content expected-hash]
  (case (guard-state file content expected-hash)
    :missing   (do (io/make-parents file) (spit file content) :wrote)
    :clean     (do (spit file content) :wrote)
    :identical :unchanged
    :protected :protected))

(defn- relative-to [root file]
  (-> (.toPath (io/file root))
      (.relativize (.toPath (io/file file)))
      str))

(defn backup!
  "Copy file to .toolbox/backup/<date>/<relative-path> before a destructive
   step. Returns the backup file."
  [root file]
  (let [dest (io/file root ".toolbox" "backup" (str (LocalDate/now)) (relative-to root file))]
    (io/make-parents dest)
    (io/copy (io/file file) dest)
    dest))

(defn stage-incoming!
  "Stage a conflicting upstream version to .toolbox/incoming/{type}/{name}/{rel}.
   Returns the staged file."
  [root type name rel content]
  (let [dest (io/file root ".toolbox" "incoming" type name rel)]
    (io/make-parents dest)
    (spit dest content)
    dest))

(defn clear-incoming!
  "Remove a component's staged incoming files, if any."
  [root type name]
  (let [dir (io/file root ".toolbox" "incoming" type name)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))] (io/delete-file f true)))))

(defn- delete-recursively! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn prune-backups!
  "Delete dated backup directories older than days. Returns the removed
   directory names — pruning is reported, never silent."
  [root days]
  (let [cutoff (.minusDays (LocalDate/now) days)
        bdir   (io/file root ".toolbox" "backup")]
    (->> (seq (.listFiles bdir))
         (filter #(.isDirectory ^java.io.File %))
         (filter #(try (.isBefore (LocalDate/parse (.getName ^java.io.File %)) cutoff)
                       (catch Exception _ false)))
         (mapv (fn [^java.io.File d]
                 (delete-recursively! d)
                 (.getName d))))))

(defn ensure-gitignore!
  "Ensure .toolbox/ is ignored. Returns true when the file was changed."
  [root]
  (let [f       (io/file root ".gitignore")
        content (if (.exists f) (slurp f) "")]
    (if (re-find #"(?m)^\.toolbox/?$" content)
      false
      (do (spit f (str content
                       (when-not (or (= content "") (.endsWith ^String content "\n")) "\n")
                       ".toolbox/\n"))
          true))))
