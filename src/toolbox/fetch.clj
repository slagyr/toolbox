(ns toolbox.fetch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [toolbox.parse :as parse])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- http-get [url]
  (let [client (HttpClient/newHttpClient)
        req    (-> (HttpRequest/newBuilder) (.uri (URI/create url)) (.GET) (.build))
        resp   (.send client req (HttpResponse$BodyHandlers/ofString))]
    (if (<= 200 (.statusCode resp) 299)
      (.body resp)
      (throw (ex-info (str "HTTP " (.statusCode resp) " for " url)
                      {:url url :status (.statusCode resp)})))))

(defn- canonical ^String [f]
  (.getCanonicalPath (io/file f)))

(defn resolve-file-url
  "Absolute java.io.File for a file:// url. Relative forms (file://./x,
   file://../x) resolve against declaring-dir and must stay inside root."
  [url declaring-dir root]
  (let [path (subs url (count "file://"))]
    (if (or (str/starts-with? path "./") (str/starts-with? path "../"))
      (let [f          (io/file (canonical (io/file declaring-dir path)))
            root-canon (canonical root)]
        (when-not (or (= (.getPath f) root-canon)
                      (str/starts-with? (.getPath f) (str root-canon java.io.File/separator)))
          (throw (ex-info (str "file:// path escapes project root: " url) {:url url})))
        f)
      (io/file path))))

(defn- strip-last-segment [url]
  (str/replace url #"[^/]+$" ""))

(defn fetcher
  "Access to a component's entry point and its relative reference files.
   Returns {:content s :fetch-rel (fn [rel] content) :source-file f-or-nil}."
  [url declaring-dir root]
  (cond
    (re-find #"^https?://" url)
    {:content     (http-get url)
     :fetch-rel   (fn [rel] (http-get (str (strip-last-segment url) rel)))
     :source-file nil}

    (str/starts-with? url "file://")
    (let [f (resolve-file-url url declaring-dir root)]
      (when-not (.exists f)
        (throw (ex-info (str "missing file source: " url) {:url url})))
      {:content     (slurp f)
       :fetch-rel   (fn [rel]
                      (let [rf (io/file (.getParentFile f) rel)]
                        (when-not (.exists rf)
                          (throw (ex-info (str "missing reference file: " rel) {:url url :ref rel})))
                        (slurp rf)))
       :source-file f})

    :else
    (throw (ex-info (str "unsupported url scheme: " url) {:url url}))))

(defn- source-dir-files
  "All files under a skill's source directory as relative paths, dotfile
   segments excluded. For file:// skills the directory IS the component."
  [dir]
  (let [base (.toPath (io/file dir))]
    (->> (file-seq (io/file dir))
         (filter #(.isFile ^java.io.File %))
         (map #(str (.relativize base (.toPath ^java.io.File %))))
         (remove (fn [rel] (some #(str/starts-with? % ".") (str/split rel #"/"))))
         sort
         vec)))

(defn fetch-component
  "Fetch a component's full file set.
   Returns {:files {relpath content} :source-file f-or-nil :warnings [msg]}.
   file:// skills cache their whole source directory; https:// skills get
   markdown-link reference discovery (fetch failures warn) plus inline-code
   asset discovery (fetch misses are skipped silently — they are candidates,
   not declarations). Other types are single files cached as {name}.md."
  [{:keys [type name url]} declaring-dir root]
  (let [{:keys [content fetch-rel source-file]} (fetcher url declaring-dir root)]
    (cond
      (not= type "skills")
      {:files {(str name ".md") content} :source-file source-file :warnings []}

      source-file
      (let [dir   (.getParentFile source-file)
            files (reduce (fn [m rel] (assoc m rel (slurp (io/file dir rel))))
                          {"SKILL.md" content}
                          (source-dir-files dir))]
        {:files files :source-file source-file :warnings []})

      :else
      (let [with-refs
            (reduce (fn [state rel]
                      (try
                        (update state :files assoc rel (fetch-rel rel))
                        (catch Exception e
                          (update state :warnings conj
                                  (str "reference " rel ": " (.getMessage e))))))
                    {:files {"SKILL.md" content} :warnings []}
                    (parse/reference-paths content))
            with-assets
            (reduce (fn [state rel]
                      (if (contains? (:files state) rel)
                        state
                        (try
                          (update state :files assoc rel (fetch-rel rel))
                          (catch Exception _ state))))
                    with-refs
                    (parse/asset-paths content))]
        (assoc with-assets :source-file nil)))))
