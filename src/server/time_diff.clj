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
      (< hours 96)      {:type :days-ago :amount (quot hours 24)}
      :else             {:type :absolute :instant past})))

(defn fuzzy-time-since [utc]
  (let [result (largest-utc-diff
                (java.time.Instant/ofEpochSecond
                 (Long/parseLong utc)))
        amount (:amount result)]
    (case (:type result)
      :just-now     "Just now"
      :minutes-ago  (str amount (if (> amount 1) " minutes ago" " minute ago"))
      :hours-ago    (str amount (if (> amount 1) " hours ago" " hour ago"))
      :days-ago     (str amount (if (> amount 1) " days ago" " day ago"))
      :absolute     (let [zdt (ZonedDateTime/ofInstant (:instant result) ZoneOffset/UTC)]
                      (.format zdt (DateTimeFormatter/ofPattern "d MMMM, yyyy"))))))
