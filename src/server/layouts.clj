(ns server.layouts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [server.lastfm :as lastfm]
            [server.time-diff :refer [largest-utc-diff]]))

(def content
  (edn/read-string (slurp (io/resource "server/content.edn"))))

(defn t [lang path]
  ((get-in content path) lang))

(defn title [lang]
  [:title (t lang [:title])])

;; MODULES
(defn page-header [lang page-key]
  (let [header (get content :header)
        pages  (vals header)
        ;; derive parent key: :spaces/nixos -> :spaces, :index -> :index
        parent-key   (keyword (namespace page-key))
        current-main (if parent-key
                       (get header parent-key)
                       (get header page-key))
        subpages (:subpages current-main)]
    [:header
     [:section {:class "site-title"}]
     [:nav {:class "main-navigation navbar"}
      [:ul
       (for [[k page] header]
         (let [active? (or (= k page-key) (= k parent-key))]
           [:li [:a (cond-> {:href (str "/" (name lang) (:path page))}
                      active? (assoc :class "current"))
                 (get-in page [:label lang])]]))]]
     [:nav {:class "subpage-navigation navbar"}
      (when (seq subpages)
        [:ul
         (for [[k sub] subpages]
           [:li [:a (cond-> {:href (str "/" (name lang) (:path sub))}
                      (= k page-key) (assoc :class "current"))
                 (get-in sub [:label lang])]])])]]))

(defn make-body [lang page-key]
  (page-header lang page-key))

(defn get-last-song []
  (let [response (lastfm/get-last-song (System/getenv "LASTFM_USERNAME"))
        track (get-in response [:lfm :recenttracks :track])]
    {:name (get-in track [:name :text])
     :artist (get-in track [:artist :text])
     :album (get-in track [:album :text])
     :image-url (get-in track [:image :text])
     :url (get-in track [:url :text])
     :date-unix (get-in track [:date :uts])}))

(defn fuzzy-time-since [utc]
  (let [time-since (largest-utc-diff
                    (java.time.Instant/ofEpochSecond
                     (Long/parseLong utc)))
        amount (get time-since :amount)
        unit (get time-since :unit)]
    (if (or (= unit "second")
            (= unit "seconds")
            (and (< amount 8) (or (= unit "minute") (= unit "minutes"))))
      "Listening now"
      (string/join " " ["Listened" amount unit "ago"]))))
