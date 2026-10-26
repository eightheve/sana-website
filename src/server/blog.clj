(ns server.blog
  (:require [hiccup2.core :as h]
            [markdown.core :as md]
            [server.entries :as entries]
            [server.time-diff :as time-diff])
  (:import (java.util UUID)))

(defn add-post [title body]
  (entries/add-entry {:id       (str (UUID/randomUUID))
                      :type     :blog
                      :title    title
                      :body     body
                      :timestamp (int (/ (System/currentTimeMillis) 1000))}))

(defn get-posts []
  (entries/get-entries :blog))

(defn get-page []
  (for [post (get-posts)]
    [:article {:class "blog-post"}
     [:h2 (:title post)]
     [:h4 {:class "timestamp"} (time-diff/fuzzy-time-since (str (:timestamp post)))]
     [:div {:class "blog-post-body"} (h/raw (md/md-to-html-string (:body post)))]]))
