(ns server.hit-counter)

(def IP-TTL-MS (* 24 60 60 1000))
(def state-dir (or (System/getenv "STATE_DIRECTORY") "."))
(def COUNT-FILE (str state-dir "/hit-counter-count.edn"))

(defn load-count []
  (try
    (clojure.edn/read-string (slurp COUNT-FILE))
    (catch java.io.FileNotFoundException _ 0)
    (catch Exception e
      (println "Warning: Could not load hit count:" (.getMessage e))
      0)))

(def state (atom {:count (load-count) :ips {}}))

(add-watch state :persist-count
  (fn [_ _ old-state new-state]
    (when (> (:count new-state) (:count old-state))
      (spit COUNT-FILE (pr-str (:count new-state))))))

(defn get-client-ip [request]
  (when request
    (or (get-in request [:headers "x-real-ip"])
        (get-in request [:headers "x-forwarded-for"])
        (:remote-addr request))))

(defn cleanup-old-ips [ips-map]
  (let [now (System/currentTimeMillis)]
    (into {} (filter (fn [[_ ts]] (< (- now ts) IP-TTL-MS)) ips-map))))

(defn process-hit [request]
  (let [ip (get-client-ip request)
        now (System/currentTimeMillis)]
    (let [new-state (swap! state (fn [{:keys [count ips] :as s}]
                                   (let [clean-ips (cleanup-old-ips ips)
                                         seen? (and ip
                                                    (contains? clean-ips ip)
                                                    (< (- now (get clean-ips ip)) IP-TTL-MS))]
                                     (if seen?
                                       (assoc s :ips clean-ips)
                                       (-> s
                                           (update :count inc)
                                           (assoc :ips (assoc clean-ips ip now)))))))]
      (format "%05d" (:count new-state)))))
