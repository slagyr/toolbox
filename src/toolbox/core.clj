(ns toolbox.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [toolbox.declare :as decl]
            [toolbox.fetch :as fetch]
            [toolbox.git :as git]
            [toolbox.hash :as hash]
            [toolbox.manifest :as manifest]
            [toolbox.sync :as sync])
  (:import (java.time Instant)))

(def ^:private empty-manifest
  {"skills" {} "commands" {} "rules" {} "modes" {} "agents" {}
   "supported_agents" [] "declaration_files" {}})

(defn- loaded-manifest [root]
  (if-let [m (manifest/load-manifest root)]
    (manifest/migrate root m)
    empty-manifest))

(defn- decl-components [decl]
  (vec (for [[type comps] (:components decl)
             [name {:keys [url declared-in]}] comps]
         {:type type :name name :url url :declared-in declared-in})))

(defn- rel-path [root file]
  (-> (.toPath (io/file root)) (.relativize (.toPath (io/file file))) str))

(defn- note! [report key item]
  (swap! report update key conj item))

(defn- sync-cache!
  "Fetch a component and bring its cache files to the fetched state under the
   Prime Directive. Returns the new manifest entry."
  [root {:keys [type name url declared-in] :as component} old-entry report]
  (let [{:keys [files source-file warnings]} (fetch/fetch-component component root root)
        _ (doseq [w warnings]
            (note! report :warnings {:component name :type type :error w}))
        old-files (get old-entry "files" {})
        dir       (manifest/cache-dir root type name)
        hashes
        (reduce
         (fn [hashes [rel content]]
           (let [f        (io/file dir rel)
                 expected (get old-files rel)
                 state    (sync/guard-state f content expected)]
             (case state
               :missing   (do (io/make-parents f) (spit f content)
                              (note! report :wrote (rel-path root f))
                              (assoc hashes rel (hash/sha256 content)))
               :clean     (do (spit f content)
                              (note! report :wrote (rel-path root f))
                              (assoc hashes rel (hash/sha256 content)))
               :identical (assoc hashes rel (hash/sha256 content))
               :protected
               ;; Locally modified: keep the local file, stage the incoming
               ;; version, flag for an agent merge (spec §5.1). The recorded
               ;; hash keeps its old baseline (or the incoming hash when there
               ;; was none) so the file stays flagged until resolved.
               (let [staged (sync/stage-incoming! root type name rel content)]
                 (note! report :attention
                        {:reason    "locally-modified"
                         :component name :type type
                         :file      (rel-path root f)
                         :incoming  (rel-path root staged)})
                 (assoc hashes rel (or expected (hash/sha256 content)))))))
         {}
         files)]
    (cond-> {"url"        url
             "fetched_at" (str (Instant/now))
             "sha256"     (hash/component-hash hashes)
             "declared_in" declared-in
             "files"      hashes}
      source-file
      (assoc "source_rev" (git/head-rev (.getParentFile source-file))))))

(defn- project!
  "Project every cached component into every agent root. The guard compares
   each destination against the OLD manifest baseline — what Toolbox last
   wrote there — not the just-updated hashes, or every fresh upstream change
   would make its own projections look locally modified."
  [root agents manifest-map old-manifest report]
  (doseq [agent agents
          type  manifest/types
          [name entry] (get manifest-map type)
          [rel _] (get entry "files")]
    (let [cache-f  (io/file (manifest/cache-dir root type name) rel)
          expected (get-in old-manifest [type name "files" rel])]
      (when (.exists cache-f)
        (let [content (slurp cache-f)
              dest    (sync/projected-file root agent type name rel)
              state   (sync/guard-state dest content expected)]
          (case state
            :missing   (do (io/make-parents dest) (spit dest content)
                           (note! report :wrote (rel-path root dest)))
            :clean     (do (spit dest content)
                           (note! report :wrote (rel-path root dest)))
            :identical nil
            :protected (note! report :attention
                              {:reason    "locally-modified"
                               :component name :type type
                               :file      (rel-path root dest)
                               :canonical (rel-path root cache-f)})))))))

(defn- reap-stale-files!
  "Delete cache and projection files that were part of a component but are
   absent from its freshly fetched file set. Protected files are backed up
   first — never silently destroyed."
  [root agents type name old-entry new-entry report]
  (let [old-files (get old-entry "files" {})
        new-files (get new-entry "files" {})
        dir       (manifest/cache-dir root type name)]
    (doseq [[rel expected] old-files
            :when (not (contains? new-files rel))
            f (cons (io/file dir rel)
                    (map #(sync/projected-file root % type name rel) agents))]
      (when (.exists f)
        (when (= :protected (sync/file-state f expected))
          (note! report :backed-up (rel-path root (sync/backup! root f))))
        (io/delete-file f true)
        (note! report :removed-files (rel-path root f))))))

(defn- remove-component!
  "Remove a no-longer-declared component: cache and managed projections.
   Protected files are backed up before removal — never silently destroyed."
  [root agents type name entry report]
  (let [dir (manifest/cache-dir root type name)]
    (doseq [[rel expected] (get entry "files")]
      (doseq [f (cons (io/file dir rel)
                      (map #(sync/projected-file root % type name rel) agents))]
        (when (.exists f)
          (when (= :protected (sync/file-state f expected))
            (note! report :backed-up (rel-path root (sync/backup! root f))))
          (io/delete-file f true)))))
  (sync/clear-incoming! root type name)
  (note! report :removed {:type type :name name}))

(defn- target-agents [root old-manifest enroll]
  (-> (set (get old-manifest "supported_agents"))
      (into (sync/existing-agent-roots root))
      (into (filter some? [enroll]))
      sort
      vec))

(defn update!
  "Bootstrap or update: assemble declarations, sync cache, remove undeclared,
   project into every supported agent root, write the manifest."
  [root {:keys [enroll]}]
  (let [decl (decl/assemble root)]
    (if (empty? (:declaration-files decl))
      {:ok false :error "no Toolbox declarations found (no boot-file ## Toolbox section and no *.TOOLBOX.md files)"}
      (let [old        (loaded-manifest root)
            report     (atom {:wrote [] :attention [] :warnings [] :removed [] :removed-files [] :backed-up []})
            components (decl-components decl)
            agents     (target-agents root old enroll)
            synced
            (reduce
             (fn [m {:keys [type name] :as c}]
               (let [old-entry (get-in old [type name])]
                 (try
                   (let [entry (sync-cache! root c old-entry report)]
                     (reap-stale-files! root agents type name old-entry entry report)
                     (assoc-in m [type name] entry))
                   (catch Exception e
                     (note! report :warnings {:component name :type type :error (.getMessage e)})
                     (if old-entry (assoc-in m [type name] old-entry) m)))))
             (assoc empty-manifest
                    "supported_agents" agents
                    "declaration_files" (:declaration-files decl))
             components)
            declared?  (set (map (juxt :type :name) components))
            removable? (empty? (:unreadable decl))]
        (doseq [type manifest/types
                [name entry] (get old type)
                :when (not (declared? [type name]))]
          (if removable?
            (remove-component! root agents type name entry report)
            (note! report :warnings {:component name :type type
                                     :error "not removed: a declaration file was unreadable this run"})))
        (project! root agents synced old report)
        (manifest/save! root synced)
        (sync/ensure-gitignore! root)
        (let [pruned (sync/prune-backups! root 30)]
          (merge {:ok true :op "update" :root (str root)
                  :declaration-files (vec (sort (keys (:declaration-files decl))))
                  :overrides (:overrides decl)
                  :unreadable (:unreadable decl)
                  :supported-agents agents
                  :pruned-backups pruned}
                 @report))))))

(defn- local-modifications [root agents type name entry]
  (let [dir (manifest/cache-dir root type name)]
    (vec
     (for [[rel expected] (get entry "files")
           f (cons (io/file dir rel)
                   (map #(sync/projected-file root % type name rel) agents))
           :when (and (.exists f) (= :protected (sync/file-state f expected)))]
       (rel-path root f)))))

(defn status
  "Report, per declared component: upstream change (remote hash vs manifest),
   local modifications (cache + projections), and — for file:// sources —
   honest freshness. Never claims \"up to date\" for a source it cannot verify."
  [root]
  (let [decl   (decl/assemble root)
        m      (loaded-manifest root)
        agents (vec (get m "supported_agents"))
        comps
        (vec
         (for [{:keys [type name url] :as c} (decl-components decl)]
           (let [entry  (get-in m [type name])
                 remote (try
                          (let [{:keys [files source-file]} (fetch/fetch-component c root root)]
                            {:hash (hash/component-hash
                                    (into {} (map (fn [[rel content]] [rel (hash/sha256 content)]) files)))
                             :source-file source-file})
                          (catch Exception e {:error (.getMessage e)}))
                 https? (boolean (re-find #"^https?://" url))]
             (cond-> {:type type :name name :url url
                      :cached (boolean entry)
                      :verified https?}
               (:error remote)
               (assoc :fetch-error (:error remote))

               (and entry (:hash remote))
               (assoc :changed-upstream (not= (:hash remote) (get entry "sha256")))

               entry
               (assoc :locally-modified (local-modifications root agents type name entry))

               (:source-file remote)
               (assoc :source-freshness (git/freshness (:source-file remote)))))))]
    {:ok true :op "status" :root (str root)
     :declaration-files (vec (sort (keys (:declaration-files decl))))
     :overrides (:overrides decl)
     :unreadable (:unreadable decl)
     :components comps}))

(defn enroll! [root agent]
  (if-not (contains? sync/agent-roots agent)
    {:ok false :error (str "unknown agent: " agent " (known: " (str/join ", " (sort (keys sync/agent-roots))) ")")}
    (let [m      (loaded-manifest root)
          agents (vec (sort (distinct (conj (get m "supported_agents") agent))))
          m      (assoc m "supported_agents" agents)
          report (atom {:wrote [] :attention []})]
      (project! root [agent] m m report)
      (manifest/save! root m)
      (merge {:ok true :op "enroll" :agent agent :supported-agents agents} @report))))

(defn unenroll! [root agent]
  (let [m      (loaded-manifest root)
        agents (vec (remove #{agent} (get m "supported_agents")))
        report (atom {:backed-up [] :removed []})]
    (doseq [type manifest/types
            [name entry] (get m type)
            [rel expected] (get entry "files")]
      (let [f (sync/projected-file root agent type name rel)]
        (when (.exists f)
          (when (= :protected (sync/file-state f expected))
            (note! report :backed-up (rel-path root (sync/backup! root f))))
          (io/delete-file f true)
          (note! report :removed (rel-path root f)))))
    (manifest/save! root (assoc m "supported_agents" agents))
    (merge {:ok true :op "unenroll" :agent agent :supported-agents agents} @report)))

(defn prune! [root]
  {:ok true :op "prune" :removed (sync/prune-backups! root 30)})
