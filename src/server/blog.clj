(ns server.blog
  (:require [hiccup2.core :as h]
            [markdown.core :as md]
            [server.entries :as entries]
            [server.time-diff :as time-diff])
  (:import (java.util UUID)))

(def posts-per-page 8)

(defn add-post [title body]
  (entries/add-entry {:id       (str (UUID/randomUUID))
                      :type     :blog
                      :title    title
                      :body     body
                      :timestamp (int (/ (System/currentTimeMillis) 1000))}))

(defn get-posts []
  (sort-by :timestamp #(compare %2 %1) (entries/get-entries :blog)))

(defn parse-page [request]
  (try
    (max 0 (Long/parseLong (get-in request [:query-params "page"])))
    (catch Exception _ 0)))

(defn last-page []
  (let [post-count (count (get-posts))]
    (max 0 (dec (quot (+ post-count (dec posts-per-page)) posts-per-page)))))

(defn- page-url [page-num]
  (if (zero? page-num)
    "/blog/"
    (str "/blog/?page=" page-num)))

(defn- pagination [page-num final-page]
  (when (pos? final-page)
    [:nav {:class "blog-pagination" :aria-label "Blog pagination"}
     (if (pos? page-num)
       [:a {:href (page-url (dec page-num)) :rel "prev"} "← Newer posts"]
       [:span])
     (if (< page-num final-page)
       [:a {:href (page-url (inc page-num)) :rel "next"} "Older posts →"]
       [:span])]))

(defn get-page [page-num]
  (let [page-num (or page-num 0)
        final-page (last-page)
        posts (->> (get-posts)
                   (drop (* page-num posts-per-page))
                   (take posts-per-page))]
    (list
     (for [post posts]
       [:article {:class "blog-post"}
        [:h2 (:title post)]
        [:h4 {:class "timestamp"} (time-diff/fuzzy-time-since (str (:timestamp post)))]
        [:div {:class "blog-post-body"} (h/raw (md/md-to-html-string (:body post)))]])
     (pagination page-num final-page))))
