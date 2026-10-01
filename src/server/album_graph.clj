(ns server.album-graph
  (:require [clj-http.client :as http]))

(def upstream
  (or (System/getenv "TAGGER_URL") "http://10.100.0.2:9478"))

(def ^:private pass-headers
  ["content-type" "etag" "last-modified" "cache-control" "content-encoding"])

(defn- fetch-upstream [path request]
  (let [if-none-match    (get-in request [:headers "if-none-match"])
        if-modified-since (get-in request [:headers "if-modified-since"])
        conditional      (cond-> {}
                           if-none-match (assoc "If-None-Match" if-none-match)
                           if-modified-since (assoc "If-Modified-Since" if-modified-since))]
    (http/get (str upstream path)
              (cond-> {:as :byte-array
                       :throw-exceptions false
                       :decompress-body false
                       :conn-timeout 15000
                       :socket-timeout 15000}
                (seq conditional) (assoc :headers conditional)))))

(defn- respond [resp default-type]
  (let [headers (select-keys (:headers resp) pass-headers)
        body    (or (:body resp) "")]
    {:status (:status resp)
     :headers (cond-> headers
                (not (contains? headers "content-type")) (assoc "content-type" default-type)
                (bytes? body) (assoc "content-length" (str (alength body))))
     :body body}))

(defn- via-upstream [path default-type request]
  (try
    (respond (fetch-upstream path request) default-type)
    (catch Exception _
      {:status 502
       :headers {"content-type" "text/plain"}
       :body "album-graph upstream unavailable"})))

(defn blob [request]
  (via-upstream "/blob" "application/octet-stream" request))

(defn atlas [file request]
  (if (re-matches #"atlas-\d+\.webp" (str file))
    (via-upstream (str "/atlas/" file) "image/webp" request)
    {:status 404
     :headers {"content-type" "text/plain"}
     :body "not found"}))
