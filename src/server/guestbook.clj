(ns server.guestbook
  (:require [server.entries :as entries]
            [server.time-diff :as time-diff])
  (:import (java.util UUID)))

(def RATE-LIMIT-SECS 30)

(defn add-comment [username body ip & {:keys [email homepage]}]
  (entries/add-entry {:id        (str (UUID/randomUUID))
                      :type      :guestbook
                      :username  username
                      :email     (if (empty? email) nil email)
                      :homepage  (if (empty? homepage) nil homepage )
                      :body      body
                      :ip        ip
                      :timestamp (int (/ (System/currentTimeMillis) 1000))}))

(defn can-post? [client-ip]
  (let [now (int (/ (System/currentTimeMillis) 1000))
        cutoff (- now RATE-LIMIT-SECS)]
    (not-any? (fn [{:keys [ip timestamp]}]
                (and (= ip client-ip)
                     (> timestamp cutoff)))
              (entries/get-entries :guestbook))))

(defn get-posts []
  (->> (entries/get-entries :guestbook)
       (map #(dissoc % :email :ip))))

(defn get-page []
  (list
   [:h2 "GUESTBOOK"]
   [:p "Please sign my guestbook! The only required fields are your name and your message. If included, your email will never be displayed publically, and will only be shown to me."]
   [:hr]
    [:form {:method "POST" :action "/spaces/guestbook/"}
     (let [fields [[[:label {:for "username"} "Name"]
                    [:input {:type "text" :name "username" :id "username" :required true}]]
                   [[:label {:for "email"} "Email"]
                    [:input {:type "email" :name "email" :id "email"}]]
                   [[:label {:for "homepage"} "Homepage"]
                    [:input {:type "url" :name "homepage" :id "homepage"}]]
                   [[:label {:for "body"} "Message"]
                    [:textarea {:name "body" :id "body" :required true}]]]]
       [:table (for [field fields]
                 [:tr
                  [:td (field 0)]
                  [:td (field 1)]])])
     [:button {:type "submit"} "Sign"]]
   [:hr]
   (for [post (reverse (get-posts))]
     [:article {:class "guestbook-post"}
      [:h3 (if (:homepage post)
             [:a {:href (:homepage post)} (:username post)]
             (:username post))
       " "
       [:span {:class "timestamp"} (time-diff/fuzzy-time-since (str (:timestamp post)))]]
      [:p (:body post)]])))
