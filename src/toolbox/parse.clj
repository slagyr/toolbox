(ns toolbox.parse
  (:require [clojure.string :as str]))

(def subsection->type
  {"Skills" "skills" "Commands" "commands" "Rules" "rules" "Modes" "modes" "Agents" "agents"})

(defn toolbox-section
  "Text of the ## Toolbox section (heading exclusive, up to the next ## heading),
   or nil when the text has no ## Toolbox heading."
  [text]
  (let [lines (str/split-lines text)
        start (first (keep-indexed (fn [i line] (when (re-matches #"##\s+Toolbox\s*" line) i)) lines))]
    (when start
      (let [tail (drop (inc start) lines)
            end  (first (keep-indexed (fn [i line] (when (re-matches #"##\s+.*" line) i)) tail))]
        (str/join "\n" (if end (take end tail) tail))))))

(defn declared-section
  "The declaration-bearing portion of a file: its ## Toolbox section when one
   exists, otherwise the whole text (headerless *.TOOLBOX.md files)."
  [text]
  (or (toolbox-section text) text))

(defn declarations
  "Ordered component declarations parsed from section text.
   Returns a vector of {:type \"skills\" :name n :url u}. Bullets outside a
   recognized ### subsection are ignored."
  [section-text]
  (loop [lines (str/split-lines section-text)
         current nil
         acc []]
    (if-let [[line & more] (seq lines)]
      (if-let [[_ heading] (re-matches #"###\s+(\S+)\s*" line)]
        (recur more (subsection->type heading) acc)
        (if-let [[_ n u] (and current (re-find #"^\s*[-*]\s+\[([^\]]+)\]\(([^)\s]+)\)" line))]
          (recur more current (conj acc {:type current :name n :url u}))
          (recur more current acc)))
      acc)))

(defn reference-paths
  "Relative markdown link targets in skill text: no scheme, no leading /,
   no anchors. Anchor suffixes are stripped."
  [text]
  (->> (re-seq #"\[[^\]]*\]\(([^)\s]+)\)" text)
       (map second)
       (remove #(or (str/includes? % "://")
                    (str/starts-with? % "#")
                    (str/starts-with? % "/")
                    (str/starts-with? % "mailto:")))
       (map #(first (str/split % #"#")))
       (remove str/blank?)
       distinct
       vec))
