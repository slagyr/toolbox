(ns toolbox.manifest
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [toolbox.hash :as hash]))

(def types ["skills" "commands" "rules" "modes" "agents"])

(defn manifest-file [root]
  (io/file root ".toolbox" "toolbox.json"))

(defn cache-dir
  "Cache directory for a component: .toolbox/skills/{name}/ for skills,
   .toolbox/{type}/ for single-file types."
  [root type name]
  (if (= type "skills")
    (io/file root ".toolbox" "skills" name)
    (io/file root ".toolbox" type)))

(defn load-manifest [root]
  (let [f (manifest-file root)]
    (when (.exists f)
      (json/parse-string (slurp f)))))

(defn save! [root m]
  (let [f (manifest-file root)]
    (io/make-parents f)
    (spit f (json/generate-string m {:pretty true}))))

(defn- migrate-files-entry
  "files list -> path->hash map, hashed from cache. Hand edits present in the
   cache become the recorded baseline — the safe direction."
  [root type name entry]
  (let [files (get entry "files")]
    (if (map? files)
      entry
      (let [dir (cache-dir root type name)
            m   (reduce (fn [m path]
                          (let [f (io/file dir path)]
                            (if (.exists f)
                              (assoc m path (hash/sha256-file f))
                              m)))
                        {}
                        files)]
        (assoc entry "files" m "sha256" (hash/component-hash m))))))

(defn migrate
  "Bring an older manifest up to the current shape: files lists become
   path->hash maps, and supported_agents / declaration_files exist."
  [root m]
  (let [m (reduce (fn [m type]
                    (if-let [components (get m type)]
                      (assoc m type
                             (reduce-kv (fn [cs name entry]
                                          (assoc cs name (migrate-files-entry root type name entry)))
                                        {}
                                        components))
                      m))
                  m
                  types)]
    (-> m
        (update "supported_agents" #(or % []))
        (update "declaration_files" #(or % {}))
        (dissoc "projections" "active_agent"))))
