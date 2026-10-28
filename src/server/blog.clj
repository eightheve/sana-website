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

(defn- timestamp-key [post]
  (try
    (Long/parseLong (str (:timestamp post)))
    (catch Exception _ 0)))

(defn- get-posts []
  (sort-by timestamp-key #(compare %2 %1) (entries/get-entries :blog)))

(defn- parse-page [request]
  (let [raw (get-in request [:query-params "page"])]
    (if (string? raw)
      (max 0 (or (parse-long raw) 0))
      0)))

(defn- last-page-index [post-count]
  (max 0 (dec (quot (+ post-count (dec posts-per-page)) posts-per-page))))

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

(defn page-data [request]
  (let [posts      (get-posts)
        page-num   (parse-page request)
        final-page (last-page-index (count posts))]
    (when (<= page-num final-page)
      {:page-num   page-num
       :final-page final-page
       :posts      (->> posts
                        (drop (* page-num posts-per-page))
                        (take posts-per-page))})))

(defn render-page [{:keys [page-num final-page posts] :as data}]
  (when data
    (list
     (for [post posts]
       [:article {:class "blog-post"}
        [:h2 (:title post)]
        [:time {:class "timestamp" :datetime (time-diff/iso-time (:timestamp post))}
         (time-diff/fuzzy-time-since (str (:timestamp post)))]
        [:div {:class "blog-post-body"} (h/raw (md/md-to-html-string (:body post)))]])
     (pagination page-num final-page))))
