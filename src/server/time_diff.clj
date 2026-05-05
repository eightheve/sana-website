(ns server.time-diff
  (:import
    (java.time Instant ZonedDateTime ZoneOffset)
    (java.time.format DateTimeFormatter)
    (java.time.temporal ChronoUnit)))

(defn largest-utc-diff
  [^Instant past]
  (let [now  (ZonedDateTime/ofInstant (Instant/now) ZoneOffset/UTC)
        then (ZonedDateTime/ofInstant past ZoneOffset/UTC)
        minutes (.between ChronoUnit/MINUTES then now)
        hours   (.between ChronoUnit/HOURS then now)]
    (cond
      (< minutes 1)     {:type :just-now}
      (< minutes 60)    {:type :minutes-ago :amount minutes}
      (< hours 24)      {:type :hours-ago :amount hours}
      (< hours 96)      {:type :days-ago :amount hours}
      :else             {:type :absolute :instant past})))

(defn fuzzy-time-since [utc]
  (let [result (largest-utc-diff
                (java.time.Instant/ofEpochSecond
                 (Long/parseLong utc)))]
    (case (:type result)
      :just-now     "Just now"
      :minutes-ago  (str (:amount result) " minutes ago")
      :hours-ago    (str (:amount result) " hours ago")
      :days-ago     (str (:amount result) " days ago")
      :absolute     (let [zdt (ZonedDateTime/ofInstant (:instant result) ZoneOffset/UTC)]
                      (.format zdt (DateTimeFormatter/ofPattern "MMMM, yyyy"))))))
