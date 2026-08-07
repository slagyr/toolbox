(ns toolbox.hash
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.security MessageDigest)))

(defn sha256-bytes [^bytes bs]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) digest))))

(defn sha256 [^String s]
  (sha256-bytes (.getBytes s "UTF-8")))

(defn sha256-file [file]
  (sha256-bytes (Files/readAllBytes (.toPath (io/file file)))))

(defn component-hash
  "Component-level hash: SHA-256 of the lines \"path:file-hash\" sorted by
   path and joined with newlines. Changes when any file's content changes,
   a file is added or removed, or a file is renamed."
  [files]
  (->> (sort-by key files)
       (map (fn [[path file-hash]] (str path ":" file-hash)))
       (str/join "\n")
       sha256))
