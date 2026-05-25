(ns server.layouts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [hiccup2.core :as h]
            [server.hit-counter :as hit-counter]
            [server.blog :as blog]
            [server.guestbook :as guestbook]
            [server.time-diff :as time-diff]
            [server.fiction :as fiction]))

(def content
  (edn/read-string (slurp (io/resource "server/content.edn"))))

(defn t [path]
  (get-in content path))

(defn disclaimer []
  (t [:gpl-disclaimer]))

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
  (let [projects (fiction/list-projects)]
    (list
     [:p (t [:spaces/fiction :intro :body])]
     (for [project projects]
       [:article {:class "fiction-project"}
        [:h2 [:a {:href (str "/spaces/fiction/" (:slug project) "/")} (:title project)]]
        (when (:blurb project)
          [:p (h/raw (:blurb project))])]))))

(defn fiction-project [request]
  (let [slug (get-in request [:route-params :project])
        project (fiction/get-project slug)]
    (when project
      (list
       [:h2 (:title project)]
       (when (:intro project)
         [:div (h/raw (:intro project))])
       (when (seq (:chapters project))
         [:h3 "Chapters"]
         [:ul
          (for [ch (:chapters project)]
            [:li [:a {:href (str "/spaces/fiction/" slug "/chapters/" (:slug ch))}
                  (clojure.string/replace (:slug ch) "-" " ")]])])))))

(defn fiction-chapter [request]
  (let [project-slug (get-in request [:route-params :project])
        chapter-slug (get-in request [:route-params :chapter])
        project (fiction/get-project project-slug)
        chapter (fiction/get-chapter project-slug chapter-slug)]
    (when chapter
      (list
       [:h2 (clojure.string/replace (:slug chapter) "-" " ")]
       (when (:html chapter)
         (list [:ul (let [prev (->> (:chapters project)
                                    (filter #(= (:order %) (dec (:order chapter))))
                                    first)]
                      (if prev
                        [:a {:href (str "/spaces/fiction/" project-slug
                                        "/chapters/" (:slug prev))}
                         (clojure.string/replace (:slug prev) "-" " ")]
                        [:span]))
                    [:a {:href (str "/spaces/fiction/" project-slug "/")}
                        (:title project)]
                    (let [next (->> (:chapters project)
                                   (filter #(= (:order %) (inc (:order chapter))))
                                   first)]
                      (if next
                        [:a {:href (str "/spaces/fiction/" project-slug
                                        "/chapters/" (:slug next))}
                         (clojure.string/replace (:slug next) "-" " ")]
                        [:span]))]
               [:div (h/raw (:html chapter))]))))))

(defn not-found []
  (list [:h2 "404: Not Found"]
        [:p "The page you were looking for could not be found!"]))

(defn get-body [page-key request]
  [:div {:class "container" :id "content-root"}
   [:main
    (case page-key
     :index (index)
     :sana (sana)
     :blog (blog/get-page)

     :spaces/fiction (fiction)
     :spaces/fiction-project (fiction-project request)
     :spaces/fiction-chapter (fiction-chapter request)
     :spaces/guestbook (guestbook/get-page)

     :404 (not-found)
     [:p "Nothing to see here yet!"])]])

(defn make-body [page-key request]
  (list (page-header page-key request)
       (get-body page-key request)))
