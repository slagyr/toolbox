(ns toolbox.declare
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [toolbox.hash :as hash]
            [toolbox.parse :as parse]))

(def boot-candidates ["AGENTS.md" "CLAUDE.md"])

(defn boot-file
  "Filename of the project's boot file, or nil."
  [root]
  (first (filter #(.exists (io/file root %)) boot-candidates)))

(defn toolbox-md-files
  "Root-level TOOLBOX.md / *.TOOLBOX.md filenames, lexicographically sorted.
   Discovery is root-only — subdirectories are not searched."
  [root]
  (->> (seq (.listFiles (io/file root)))
       (filter #(.isFile ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (filter #(or (= % "TOOLBOX.md") (str/ends-with? % ".TOOLBOX.md")))
       sort
       vec))

(defn- parse-file [root fname boot?]
  (try
    (let [text    (slurp (io/file root fname))
          section (if boot? (parse/toolbox-section text) (parse/declared-section text))]
      (when section
        {:file fname :hash (hash/sha256 text) :decls (parse/declarations section)}))
    (catch Exception e
      {:file fname :unreadable (.getMessage e)})))

(defn assemble
  "Assemble the declaration set for a project root.
   Precedence: boot file first, then discovered *.TOOLBOX.md files in
   lexicographic order — later files win conflicts.
   Returns {:declaration-files {fname hash}
            :components {type {name {:url u :declared-in fname}}}
            :overrides [{:type t :name n :winner f :loser f}]
            :unreadable [fname ...]}"
  [root]
  (let [boot   (boot-file root)
        parsed (concat (when boot [(parse-file root boot true)])
                       (map #(parse-file root % false) (toolbox-md-files root)))
        parsed (remove nil? parsed)]
    (reduce
     (fn [acc {:keys [file hash decls unreadable]}]
       (if unreadable
         (update acc :unreadable conj file)
         (let [acc (assoc-in acc [:declaration-files file] hash)]
           (reduce
            (fn [acc {:keys [type name url]}]
              (let [prev (get-in acc [:components type name])
                    acc  (assoc-in acc [:components type name] {:url url :declared-in file})]
                (if (and prev (not= (:declared-in prev) file))
                  (update acc :overrides conj {:type type :name name :winner file :loser (:declared-in prev)})
                  acc)))
            acc
            decls))))
     {:declaration-files {} :components {} :overrides [] :unreadable []}
     parsed)))
