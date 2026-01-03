(ns server.layouts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [server.lastfm :as lastfm]
            [server.time-diff :refer [largest-utc-diff]]))

(def content
  (edn/read-string (slurp (io/resource "server/content.edn"))))

(defn t [lang & path]
  (let [path-vec (vec path)]
    (or (get-in content (conj path-vec lang))
        (when-let [en-content (get-in content (conj path-vec :en))]
          [:span {:lang "en"} en-content])
        [:span {:lang "en"} (str "missing content " (clojure.string/join " " path-vec))])))

;; MODULES

(defn dropdown [label elements]
  [:section {:class "dropdown"}
   [:button (str label) [:span {:class "nf nf-cod-triangle_down"}]]
   [:section (for [{:keys [href label]} elements]
               [:a {:href href} label])]])

(defn page-header [lang]
  (let [lang-kw (keyword lang)
        nav-sections [[:home-label
                       [["/index.html" :home-index]
                        ["/about.html" :home-about]
                        ["/updates.html" :home-updates]]]
                      [:spaces-label
                       [["/music.html" :spaces-music]
                        ["/nixos.html" :spaces-nixos]]]
                      [:other-label
                       [["/album-map.html" :other-album_map]
                        ["/chatroom.html" :other-chatroom]]]]]
    [:header {:id "header-main"}
     [:a {:href "/"} [:img {:src "/img/icon.png"}]]
     [:nav
      (for [[label-key items] nav-sections]
        (dropdown (t lang-kw :header label-key)
                  (for [[path key] items]
                    {:href (str "/" lang path)
                     :label (t lang-kw :header key)})))
      [:div {:id "pubkey"} 
       [:a {:href "/key.asc" :target "_blank" :lang "en" } "PUBKEY"]]]]))

(defn title [lang]
  [:title (t lang :title)])

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

(defn get-status [lang]
  [:div {:id "status"}
   (let [last-song (get-last-song)
         title (get last-song :name)
         album (get last-song :album)
         artist (get last-song :artist)
         image-url (get last-song :image-url)
         date-unix (get last-song :date-unix)]
     [:div {:class "last-song" :lang "en"}
      [:img {:src image-url}]
      [:p {:class "marquee"} 
       [:span
        [:span title]
        " from "
         [:span album]
         " by "
         [:span artist]]]
      [:script {:src "/js/marquee.js"}]
      [:p 
       (fuzzy-time-since date-unix)]])
   [:table [:tr
            [:td (t lang :status :mood)]
            [:td {:lang "tok"} "ilo [soweli anpa nasa ante] li pilin pona"]]]])

;; PAGES

(defn index [lang]
  (let [ft (partial t (keyword lang))]
    (list 
     (page-header lang)
     [:div {:id "index-main"}
           [:main [:article {:lang "en"}
                            [:h2 "DISCLAIMER"]
                            [:p "This website is still very much " [:b "under construction!"] " I appreciate your patience as things get finished. If you're seeing this, expect broken links, missing content, potential connection hiccups, and more. This is a live development environment for me, cut me some slack. Thank you!"]]
                  [:article [:h2 (ft :index :introduction/header)]
                            [:p (ft :index :introduction/body)]]
                  [:article [:h3 (ft :index :meta/header)]
                            [:p (ft :index :meta/body)]]
                  [:article [:h3 (ft :index :pgp/header)]
                            [:p (ft :index :pgp/body)]]]
           [:div [:article]
                  [:h3 (ft :sidebar :status-header)]
                  (get-status lang)
                 [:nav {:id "webrings"}
                  [:h3 (ft :sidebar :webring-header)]
                  [:table {:lang "en"}
                  (let [webrings {:bucket {:label "bucket ring"
                                           :url "https://webring.bucketfish.me/"
                                           :next "redirect.html?to=next&name=matty"
                                           :prev "redirect.html?to=prev&name=matty"}
                                  :geek {:label "geek ring"
                                         :url "https://geekring.net/"
                                         :next "site/381/next"
                                         :prev "site/381/prev"}
                                  :silly {:label "silly city"
                                         :url "https://silly.city/"
                                         :next "next?user=matty"
                                         :prev "prev?user=matty"}}]
                    (for [[ring {:keys [label url next prev]}] webrings]
                      [:tr {:id (str "ring-navigator-" (name ring))}
                           [:td {:class "prev"} [:a {:href (str url prev)} "←"]]
                           [:td {:class "home"} [:a {:href url} label]]
                           [:td {:class "next"} [:a {:href (str url next)} "→"]]]))]]
                 [:article
                  [:h3 (ft :sidebar :visit-counter)]
                  [:img {:src "https://count.getloli.com/@transatlanticism?name=transatlanticism&theme=green&padding=5&offset=0&align=top&scale=1&pixelated=1&darkmode=0" :style "margin:0 calc((100% - 225px) / 2)"}]]
                 [:article
                  [:h3 (ft :sidebar :contact)]
                  [:address {:lang "en"}
                   [:table
                            (let [at-symbol [:span {:class "addr-at"}]
                                  contacts {:email [:span "sana" at-symbol "doppel.moe"]
                                            :discord  [:span at-symbol "parchedocean"]
                                            :tumblr [:span at-symbol "doppelsana"]
                                            :steam [:span at-symbol "doppelsana"]}]
                              (for [[label content] contacts]
                                [:tr [:td {:class "addr-label"} (name label)]
                                     [:td {:class "addr-filler"} [:div]]
                                     [:td {:class "addr-content"}content]]))]]]]])))
