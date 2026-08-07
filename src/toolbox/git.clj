(ns toolbox.git
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- sh [dir & args]
  (try
    (apply shell/sh (concat args [:dir (str dir)]))
    (catch Exception e
      {:exit -1 :out "" :err (.getMessage e)})))

(defn- ok [result]
  (when (zero? (:exit result)) (str/trim (:out result))))

(defn repo-root [dir]
  (ok (sh dir "git" "rev-parse" "--show-toplevel")))

(defn head-rev [dir]
  (ok (sh dir "git" "rev-parse" "HEAD")))

(defn fetch-with-timeout
  "Best-effort git fetch. Fetch only updates remote-tracking refs so it is
   safe unprompted, but it can hang on a credential prompt in unattended
   contexts — hence the timeout. Returns :ok, :failed, or :timeout."
  [dir ms]
  (let [f (future (sh dir "git" "fetch" "--quiet"))
        r (deref f ms ::timeout)]
    (cond
      (= r ::timeout) (do (future-cancel f) :timeout)
      (zero? (:exit r)) :ok
      :else :failed)))

(defn behind-count
  "Commits behind upstream: rev-list --count HEAD..@{u}.
   (@{u}..HEAD would count commits AHEAD — a different report.)"
  [dir]
  (some-> (ok (sh dir "git" "rev-list" "--count" "HEAD..@{u}")) parse-long))

(defn dirty-at?
  [dir path]
  (when-let [out (ok (sh dir "git" "status" "--porcelain" "--" (str path)))]
    (not (str/blank? out))))

(defn freshness
  "Freshness report for a file:// source. Never claims currency it cannot
   verify: no repo, no upstream, or a failed fetch all report unknown."
  [source-file]
  (let [dir (.getParentFile (io/file source-file))]
    (if-let [repo (repo-root dir)]
      (let [fetch  (fetch-with-timeout repo 10000)
            behind (when (= fetch :ok) (behind-count repo))
            dirty  (dirty-at? repo source-file)]
        (cond-> {:repo repo :dirty (boolean dirty)}
          (some? behind)       (assoc :behind behind
                                      :freshness (if (pos? behind) "behind" "matches-upstream"))
          (nil? behind)        (assoc :freshness "unknown")
          (not= fetch :ok)     (assoc :fetch (name fetch))))
      {:repo nil :freshness "unknown"})))
