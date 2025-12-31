(ns server.core
  (:require [compojure.core :refer [defroutes GET routes context]]
            [compojure.route :as route]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [redirect]]
            [hiccup2.core :as h]
            
            [server.layouts :as layouts]))

(defonce server (atom nil))

(defn port []
  (or (some-> (System/getenv "PORT")
              Integer/parseInt)
      45000))

(defn head [lang]
             [:head 
             (layouts/title (keyword lang))
             [:meta {:charset "UTF-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
             [:link {:href "/css/main.css" :rel "stylesheet"}]
             [:link {:href "/nf/nerd-fonts-generated.css" :rel "stylesheet"}]
             [:link 
                {:href "/img/favicon-64x64.png" :rel "icon" :type "image/png" :sizes "64x64"}]
             [:script {:src "/js/script.js"}]
             [:script "let FF_FOUC_FIX;"]])

(defn page [lang content]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str (h/html (h/raw "<!DOCTYPE html>")
                      [:html {:lang lang}
                             (head lang)
                             [:body content]]))})

(def valid-langs #{"en" "tok"})

(defn wrap-valid-lang [handler]
  (fn [request]
    (let [lang (get-in request [:params :lang])]
      (if (valid-langs lang)
        (handler request)
        nil))))

(defroutes app
  (GET "/" [] (redirect "/en/"))
  (GET "/index.html" [] (redirect "/en/"))
  
  (context "/:lang" [lang]
           (wrap-valid-lang
             (routes
               (GET "/" [] (page (keyword lang) (layouts/index lang)))
               (GET "/index.html" [] (page (keyword lang) (layouts/index lang))))))

  (route/resources "/")
  (route/not-found "404"))

(defn start-server []
  (println (str "Running server on port " (port)))
  (reset! server
          (run-jetty #'app {:port (port) :join? false})))

(defn stop-server []
  (when @server
    (.stop @server)
    (reset! server nil)))

(defn restart-server []
  (stop-server)
  (start-server))
