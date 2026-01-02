(ns server.time-diff
  (:import
    (java.time Instant ZonedDateTime ZoneOffset)
    (java.time.temporal ChronoUnit)))

(def units
  [[ChronoUnit/YEARS  "year"]
   [ChronoUnit/MONTHS "month"]
   [ChronoUnit/DAYS   "day"]
   [ChronoUnit/HOURS  "hour"]
   [ChronoUnit/MINUTES "minute"]
   [ChronoUnit/SECONDS "second"]])

(defn largest-utc-diff
  [^Instant past]
  (let [now  (ZonedDateTime/ofInstant (Instant/now) ZoneOffset/UTC)
        then (ZonedDateTime/ofInstant past ZoneOffset/UTC)]
    (some (fn [[unit label]]
            (let [n (.between unit then now)]
              (when (>= n 1)
                {:amount n
                 :unit   (if (= n 1) label (str label "s"))})))
          units)))
