(ns toolbox.cli
  (:require [cheshire.core :as json]
            [toolbox.core :as core])
  (:gen-class))

(def ^:private usage
  "usage: toolbox <op> [args] [--root PATH]
  bootstrap          fetch, cache, and project everything declared
  update             re-fetch, apply clean changes, flag protected files
  status             upstream changes, local modifications, source freshness
  enroll <agent>     add an agent (claude-code|opencode|grok|cursor|codex)
  unenroll <agent>   remove an agent's managed projections
  prune              prune backups older than 30 days
Output is JSON; anything needing judgment is listed under \"attention\".")

(defn- parse-opts [args]
  (loop [args args opts {:positional []}]
    (if-let [[a & more] (seq args)]
      (if (= a "--root")
        (recur (rest more) (assoc opts :root (first more)))
        (recur more (update opts :positional conj a)))
      opts)))

(defn -main [& args]
  (let [[op & more] args
        {:keys [root positional]} (parse-opts more)
        root   (or root (System/getProperty "user.dir"))
        agent  (first positional)
        result (case op
                 ("bootstrap" "update") (core/update! root {})
                 "status"   (core/status root)
                 "enroll"   (if agent (core/enroll! root agent) {:ok false :error "enroll requires an agent name"})
                 "unenroll" (if agent (core/unenroll! root agent) {:ok false :error "unenroll requires an agent name"})
                 "prune"    (core/prune! root)
                 {:ok false :error (str "unknown op: " (pr-str op)) :usage usage})]
    (println (json/generate-string result {:pretty true}))
    (when-not (:ok result)
      (System/exit 1))))
