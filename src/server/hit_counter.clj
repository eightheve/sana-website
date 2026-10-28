(ns server.hit-counter
  (:require [server.storage :as storage]))

(def IP-TTL-MS (* 24 60 60 1000))

(def COUNT-FILE (storage/state-file "hit-counter-count.edn"))

(defn load-count []
  (storage/load-edn COUNT-FILE 0))

(def state (atom {:count (load-count) :ips {}}))

(add-watch state :persist-count
  (fn [_ _ old-state new-state]
    (when (> (:count new-state) (:count old-state))
      (storage/save-edn! COUNT-FILE (:count new-state)))))

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
        now (System/currentTimeMillis)
        new-state (swap! state
                         (fn [{:keys [ips] :as s}]
                           (let [clean-ips (cleanup-old-ips ips)
                                 seen? (and ip (contains? clean-ips ip))]
                             (if seen?
                               (assoc s :ips clean-ips)
                               (-> s
                                   (update :count inc)
                                   (assoc :ips (assoc clean-ips ip now)))))))]
    (format "%05d" (:count new-state))))
