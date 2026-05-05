(ns server.layouts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [server.lastfm :as lastfm]
            [server.hit-counter :as hit-counter]
            [server.blog :as blog]
            [server.guestbook :as guestbook]
            [server.time-diff :as time-diff]))

(def content
  (edn/read-string (slurp (io/resource "server/content.edn"))))

(defn t [path]
  (get-in content path))

;; MODULES
(defn page-header [page-key request]
  (let [header (get content :header)
        pages  (vals header)
        ;; derive parent key: :spaces/nixos -> :spaces, :index -> :index
        parent-key   (keyword (namespace page-key))
        current-main (if parent-key
                       (get header parent-key)
                       (get header page-key))
        subpages (:subpages current-main)]
    [:header
     [:section {:class "site-title container"}
      [:section 
       [:h1 "sana.doppel.moe"]
       (let [digits (if request (hit-counter/process-hit request) "404")]
         [:div {:class "hit-counter"}
           (for [d digits]
             [:div {:style (str "background-position: " (* (Character/digit d 10) -45) "px 0;")}])])]]
     [:nav {:class "main-navigation navbar container"}
      [:ul
       (for [[k page] header]
         (let [active? (or (= k page-key) (= k parent-key))]
           [:li [:a (cond-> {:href (:path page)}
                      active? (assoc :class "current"))
                 (get-in page [:label])]]))]]
     [:nav {:class "subpage-navigation navbar container"}
      (when (seq subpages)
        [:ul
         (for [[k sub] subpages]
           [:li [:a (cond-> {:href (:path sub)}
                      (= k page-key) (assoc :class "current"))
                 (get-in sub [:label])]])])]]))

(defn index []
  (list [:p (t [:index :intro :body])]
        [:h2 (t [:index :meta :header])]
        [:p (t [:index :meta :body])]
        [:h2 (t [:index :pgp :header])]
        [:p (t [:index :pgp :body])]))

(defn sana []
  (list [:p (t [:about-me :intro :body])]
        [:h2 (t [:about-me :hobbies :header])]
        [:p (t [:about-me :hobbies :body])]
        [:h2 (t [:about-me :contact :header])]
        [:address
         [:table {:class "contacts"}
          (for [contact (:contacts content)]
           [:tr [:td {:class "label"} [:span (:label contact)]]
                [:td {:class "address"} (:id contact)]])]]))

(defn fiction []
  (list [:p (t [:spaces/fiction :intro :body])]
        [:h2 (t [:spaces/fiction :shallows :header])]
        [:p (t [:spaces/fiction :shallows :body])]))

(defn not-found []
  (list [:h2 "404: Not Found"]
        [:p "The page you were looking for could not be found!"]))

(defn get-body [page-key]
  [:div {:class "container" :id "content-root"}
   [:main
    (case page-key
     :index (index)
     :sana (sana)
     :blog (blog/get-page)
     
     :spaces/fiction (fiction)
     :spaces/guestbook (guestbook/get-page)

     :404 (not-found)
     [:p "Nothing to see here yet!"])]])

(defn make-body [page-key request]
  (list (page-header page-key request)
       (get-body page-key)))

(defn get-last-song []
  (let [response (lastfm/get-last-song (System/getenv "LASTFM_USERNAME"))
        track (get-in response [:lfm :recenttracks :track])]
    {:name (get-in track [:name :text])
     :artist (get-in track [:artist :text])
     :album (get-in track [:album :text])
     :image-url (get-in track [:image :text])
     :url (get-in track [:url :text])
     :date-unix (get-in track [:date :uts])}))
