(ns server.entries
  (:require [server.storage :as storage]))

(defn entries-file [type]
  (storage/state-file (str (name type) ".edn")))

(defn load-type [type]
  (storage/load-edn (entries-file type) []))

(def state (atom {}))

(defn ensure-type-loaded [type]
  (swap! state (fn [s]
                 (if (contains? s type)
                   s
                   (assoc s type (load-type type))))))

(defn get-entries [type]
  (ensure-type-loaded type)
  (reverse (get @state type [])))

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
        (when (and (contains? old-state type) (> new-count old-count))
          (storage/save-edn! (entries-file type) entries))))))
