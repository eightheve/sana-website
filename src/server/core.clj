(ns server.core
  (:require [compojure.core :refer [defroutes GET POST routes context]]
            [compojure.route :as route]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [redirect]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [hiccup2.core :as h]
            [clojure.java.io :as io]

            [server.layouts :as layouts]
            [server.blog :as blog]
            [server.config :as config]
            [server.guestbook :as guestbook]
            [server.fiction :as fiction]
            [server.album-graph :as album-graph]
            [server.radio :as radio]))

(defonce server (atom nil))

(defn head [page-key]
  [:head
   [:title (layouts/t [:title])]
   [:meta {:charset "UTF-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:link {:href "/css/main.css" :rel "stylesheet"}]
   [:link
    {:href "/img/favicon-64x64.png" :rel "icon" :type "image/png" :sizes "64x64"}]
   [:script "let FF_FOUC_FIX;"]
   (when (= page-key :blog)
     [:script {:src "/js/blog.js" :defer true}])
   (when (= page-key :spaces/album-graph)
     (list
      [:script {:type "importmap"}
       (h/raw "{\"imports\":{\"three\":\"/js/album-graph/vendor/three.module.min.js\",\"three/addons/\":\"/js/album-graph/vendor/three/addons/\",\"d3-force-3d\":\"/js/album-graph/vendor/d3-force-3d.esm.js\"}}")]
      [:script {:type "module" :src "/js/album-graph/main.js"}]))])

(defn page
  ([content-key request]
   (page content-key request 200))
  ([content-key request status]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"
              "Cache-Control" "no-cache"}
    :body (str (h/html (h/raw "<!DOCTYPE html>")
                       (h/raw (str "<!--" (layouts/disclaimer) "-->"))
                       [:html {:lang "en"}
                        (head content-key)
                        [:body (layouts/make-body content-key request)]]))}))

(defn not-found-page []
  (page :404 nil 404))

(defn- text-response [status body]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    body})

(defn- text-download [result filename]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
   :body    (:content result)})

(defn- epub-download [result]
  (let [file (io/file (:file result))
        body (try
               (java.nio.file.Files/readAllBytes (.toPath file))
               (finally
                 (io/delete-file file true)))]
    {:status  200
     :headers {"Content-Type" "application/epub+zip"
               "Content-Disposition" (str "attachment; filename=\"" (:filename result) "\"")}
     :body    body}))

(defn gen-token []
  (apply str (repeatedly 16 #(rand-nth "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))))

(defonce api-token
  (or config/api-token
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
  (GET "/index.html" _ (redirect "/"))
  (GET "/index" _ (redirect "/"))

  (GET "/" req          (page :index req))
  (GET "/sana/" req     (page :sana req))
  (GET "/blog/" req
    (if-let [page-data (blog/page-data req)]
      (page :blog (assoc req :blog/page-data page-data))
      (not-found-page)))
  (GET "/spaces/" req   (page :spaces req))

  (GET "/spaces/fiction/" req   (page :spaces/fiction req))
  (GET "/spaces/fiction/:project/chapters/:chapter.:format" req
    (let [project (get-in req [:route-params :project])
          chapter (get-in req [:route-params :chapter])
          format  (get-in req [:route-params :format])]
      (condp = format
        "txt"
        (if-let [result (fiction/chapter-txt project chapter)]
          (text-download result (str project "-" (:slug result) ".txt"))
          (text-response 404 "Not found"))
        "epub"
        (if-let [result (fiction/chapter-epub project chapter)]
          (epub-download result)
          (text-response 404 "Not found"))
        (text-response 400 "Unknown format"))))
  (GET "/spaces/fiction/:project/chapters/:chapter" req
    (let [data (fiction/chapter-page-data (get-in req [:route-params :project])
                                          (get-in req [:route-params :chapter]))]
      (if data
        (page :spaces/fiction-chapter (assoc req :fiction/chapter data))
        (not-found-page))))
  (GET "/spaces/fiction/:project.:format" req
    (let [project (get-in req [:route-params :project])
          format  (get-in req [:route-params :format])]
      (condp = format
        "txt"
        (if-let [result (fiction/project-txt project)]
          (text-download result (str project ".txt"))
          (text-response 404 "Not found"))
        "epub"
        (if-let [result (fiction/project-epub project)]
          (epub-download result)
          (text-response 404 "Not found"))
        (text-response 400 "Unknown format"))))
  (GET "/spaces/fiction/:project/" req
    (let [project (fiction/get-project (get-in req [:route-params :project]))]
      (if project
        (page :spaces/fiction-project (assoc req :fiction/project project))
        (not-found-page))))
  (GET "/spaces/webrings/" req  (page :spaces/webrings req))
  (GET "/spaces/guestbook/" req (page :spaces/guestbook req))
  (POST "/spaces/guestbook/" req (guestbook/handle-post req))
  (GET "/spaces/updates/" req   (page :spaces/updates req))
  (GET "/spaces/album-graph/" req (page :spaces/album-graph req))

  (GET "/spaces/album-graph/blob" req (album-graph/blob req))
  (GET "/spaces/album-graph/atlas/:file" req
    (album-graph/atlas (get-in req [:route-params :file]) req))

  (POST "/spaces/album-graph/radio/login" req (radio/login req))
  (POST "/spaces/album-graph/radio/logout" req (radio/logout req))
  (GET "/spaces/album-graph/radio/status" req (radio/status req))
  (GET "/spaces/album-graph/radio/album" req (radio/album req))
  (GET "/spaces/album-graph/radio/stream" req (radio/stream req))
  (GET "/spaces/album-graph/radio/cover" req (radio/cover req))
  (POST "/spaces/album-graph/radio/scrobble" req (radio/scrobble req))

  (routes (context "/api/auth" [] (wrap-auth auth-api-routes)))

  (wrap-cache-static (route/resources "/") 86400)
  (route/not-found (not-found-page)))

(defn start-server []
  (reset! server
          (run-jetty (wrap-gzip (wrap-params #'app)) {:port (config/port) :join? false})))

(defn stop-server []
  (when @server
    (.stop @server)
    (reset! server nil)))

(defn restart-server []
  (stop-server)
  (start-server))

(defn -main []
  (restart-server)
  (println "Server started on port" (config/port)))
