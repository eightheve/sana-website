(ns server.entries)

(def state-dir (or (System/getenv "STATE_DIRECTORY") "."))

(defn entries-file [type]
  (str state-dir "/" (name type) ".edn"))

(defn load-type [type]
  (try
    (clojure.edn/read-string (slurp (entries-file type)))
    (catch java.io.FileNotFoundException _ [])
    (catch Exception e
      (println (str "Warning: Could not load " type " entries: " (.getMessage e)))
      [])))

(def state (atom {}))

(defn ensure-type-loaded [type]
  (when-not (contains? @state type)
    (swap! state assoc type (load-type type))))

(defn get-entries [type]
  (ensure-type-loaded type)
  (get @state type []))

(def required-keys #{:id :body :timestamp :type})

(defn add-entry [entry]
  (let [entry-keys (set (keys entry))
        missing (filter (complement entry-keys) required-keys)]
    (when (seq missing)
      (throw (ex-info (str "Entry missing required keys: " (vec missing))
                      {:missing (vec missing)}))))
  (let [type (:type entry)]
    (ensure-type-loaded type)
    (swap! state update type conj entry)
    entry))

(add-watch state :persist-entries
  (fn [_ _ old-state new-state]
    (doseq [[type entries] new-state]
      (let [old-count (count (get old-state type []))
            new-count (count entries)]
        (when (> new-count old-count)
          (spit (entries-file type) (pr-str entries)))))))
