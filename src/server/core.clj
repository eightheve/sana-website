(ns server.core
  (:require [compojure.core :refer [defroutes GET POST routes context]]
            [compojure.route :as route]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [redirect]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [hiccup2.core :as h]

            [server.layouts :as layouts]
            [server.blog :as blog]
            [server.guestbook :as guestbook]
            [server.fiction :as fiction]))

(defonce server (atom nil))

(defn port []
  (or (some-> (System/getenv "PORT")
              Integer/parseInt)
      45000))

(defn head []
  [:head
   [:title "Sana's Homepage"]
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:link {:href "/css/main.css" :rel "stylesheet"}]
   [:link
    {:href "/img/favicon-64x64.png" :rel "icon" :type "image/png" :sizes "64x64"}]
   [:script "let FF_FOUC_FIX;"]])

(defn page [content-key request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-cache"}
   :body (str (h/html (h/raw "<!DOCTYPE html>")
                      (h/raw (str "<!--" (layouts/disclaimer) "-->"))
                      [:html {:lang "en"}
                       (head)
                       [:body (layouts/make-body content-key request)]]))})

(defn gen-token []
  (apply str (repeatedly 16 #(rand-nth "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))))

(defonce api-token
  (or (System/getenv "SANA_API_TOKEN")
      (let [token (gen-token)]
        (println "Generated API token:" token)
        token)))

(defn wrap-auth [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])]
      (if (= auth-header (str "Bearer " api-token))
        (handler request)
        {:status 401
         :headers {"Content-Type" "text/plain"}
         :body "Unauthorized"}))))

(defn wrap-cache-static [handler max-age]
  (fn [request]
    (let [response (handler request)]
      (if (and response (= (:status response) 200))
        (assoc-in response [:headers "Cache-Control"]
                  (str "public, max-age=" max-age))
        response))))

(defroutes auth-api-routes
  (POST "/blog" req
    (let [params (:form-params req)
          title  (get params "title")
          body   (get params "body")]
      (if (and title body)
        (do (blog/add-post title body)
            {:status 201
             :headers {"Content-Type" "text/plain"}
             :body "Created"})
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body "Missing title or body"}))))

(defroutes app
  (GET "/index.html" req (redirect "/"))
  (GET "/index" req (redirect "/"))

  (GET "/" req          (page :index req))
  (GET "/sana/" req     (page :sana req))
  (GET "/blog/" req     (page :blog req))
  (GET "/spaces/" req   (page :spaces req))

  (GET "/spaces/fiction/" req   (page :spaces/fiction req))
  (GET "/spaces/fiction/:project/" req
    (page :spaces/fiction-project req))
  (GET "/spaces/fiction/:project/chapters/:chapter" req
    (page :spaces/fiction-chapter req))
  (GET "/spaces/fiction/:project/chapters/:chapter.:format" req
    (let [project  (get-in req [:route-params :project])
          chapter  (get-in req [:route-params :chapter])
          format   (get-in req [:route-params :format])]
      (condp = format
        "txt"
        (let [result (fiction/chapter-txt project chapter)]
          (if result
            {:status  200
             :headers {"Content-Type" "text/plain; charset=utf-8"
                       "Content-Disposition" (str "attachment; filename=\""
                                                  project "-" (:slug result) ".txt\"")}
             :body    (:content result)}
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body    "Not found"}))
        "epub"
        (let [result (fiction/chapter-epub project chapter)]
          (if result
            {:status  200
             :headers {"Content-Type" "application/epub+zip"
                       "Content-Disposition" (str "attachment; filename=\""
                                                  (:filename result) "\"")}
             :body    (clojure.java.io/file (:file result))}
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body    "Not found"}))
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body    "Unknown format"})))
  (GET "/spaces/fiction/:project.:format" req
    (let [project (get-in req [:route-params :project])
          format   (get-in req [:route-params :format])]
      (condp = format
        "txt"
        (let [result (fiction/project-txt project)]
          (if result
            {:status  200
             :headers {"Content-Type" "text/plain; charset=utf-8"
                       "Content-Disposition" (str "attachment; filename=\""
                                                  project ".txt\"")}
             :body    (:content result)}
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body    "Not found"}))
        "epub"
        (let [result (fiction/project-epub project)]
          (if result
            {:status  200
             :headers {"Content-Type" "application/epub+zip"
                       "Content-Disposition" (str "attachment; filename=\""
                                                  (:filename result) "\"")}
             :body    (clojure.java.io/file (:file result))}
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body    "Not found"}))
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body    "Unknown format"})))
  (GET "/spaces/webrings/" req  (page :spaces/webrings req))
  (GET "/spaces/guestbook/" req (page :spaces/guestbook req))
  (POST "/spaces/guestbook/" req
    (let [content-length (some-> (get-in req [:headers "content-length"])
                                 Integer/parseInt)
          params  (:form-params req)
          username (get params "username")
          body    (get params "body")
          ip      (or (get-in req [:headers "x-real-ip"])
                      (get-in req [:headers "x-forwarded-for"])
                      (:remote-addr req))]
      (cond
        (and content-length (> content-length 4096))
        {:status 413
         :headers {"Content-Type" "text/plain"}
         :body "Payload too large"}
        (not (guestbook/can-post? ip))
        {:status 429
         :headers {"Content-Type" "text/plain"}
         :body "Too many requests"}
        (not (and username body))
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body "Missing username or body"}
        :else
        (do (guestbook/add-comment username body ip
                                   :email    (get params "email")
                                   :homepage (get params "homepage"))
            (redirect "/spaces/guestbook/")))))
  (GET "/spaces/updates/" req   (page :spaces/updates req))

  (routes (context "/api/auth" [] (wrap-auth auth-api-routes)))

  (wrap-cache-static (route/resources "/") 86400)
  (route/not-found (page :404 nil)))

(defn start-server []
  (reset! server
          (run-jetty (wrap-gzip (wrap-params #'app)) {:port (port) :join? false})))

(defn stop-server []
  (when @server
    (.stop @server)
    (reset! server nil)))

(defn restart-server []
  (stop-server)
  (start-server))

(defn -main []
  (restart-server)
  (println "Server started on port" (port)))
