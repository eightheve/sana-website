(ns server.guestbook
  (:require [clojure.string :as str]
            [ring.util.response :refer [redirect]]
            [server.entries :as entries]
            [server.hit-counter :as hit-counter]
            [server.time-diff :as time-diff])
  (:import (java.util UUID)))

(def RATE-LIMIT-SECS 30)

(defn- text-response [status body]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    body})

(defn- homepage-link? [homepage]
  (boolean (and homepage
                (re-matches #"(?i)^https?://[^\s]+$" homepage))))

(defn- valid-homepage? [homepage]
  (or (str/blank? homepage)
      (homepage-link? homepage)))

(defn add-comment [username body ip & {:keys [email homepage]}]
  (entries/add-entry {:id        (str (UUID/randomUUID))
                      :type      :guestbook
                      :username  username
                      :email     (if (empty? email) nil email)
                      :homepage  (when-not (str/blank? homepage) homepage)
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

(defn handle-post [request]
  (let [params         (:form-params request)
        username       (get params "username")
        body           (get params "body")
        homepage       (get params "homepage")
        ip             (hit-counter/get-client-ip request)
        content-length (try
                         (some-> (get-in request [:headers "content-length"])
                                 Integer/parseInt)
                         (catch NumberFormatException _ nil))]
    (cond
      (and content-length (> content-length 4096))
      (text-response 413 "Payload too large")

      (not (can-post? ip))
      (text-response 429 "Too many requests")

      (not (and username body))
      (text-response 400 "Missing username or body")

      (not (valid-homepage? homepage))
      (text-response 400 "Invalid homepage")

      :else
      (do (add-comment username body ip
                       :email    (get params "email")
                       :homepage homepage)
          (redirect "/spaces/guestbook/")))))

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
   (for [post (get-posts)]
     [:article {:class "guestbook-post"}
      [:h3 (if (homepage-link? (:homepage post))
             [:a {:href (:homepage post)} (:username post)]
             (:username post))
       " "
       [:time {:class    "timestamp"
               :datetime (time-diff/iso-time (str (:timestamp post)))}
        (time-diff/fuzzy-time-since (str (:timestamp post)))]]
      [:p (:body post)]])))
