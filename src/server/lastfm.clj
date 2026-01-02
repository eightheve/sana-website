(ns server.lastfm
  (:require [clojure.data.xml :as xml]
            [clj-http.client :as http]))

(def api-key (System/getenv "LASTFM_API_KEY"))
(def base-url "http://ws.audioscrobbler.com/2.0/")

(defn- xml->map
  "Converts XML element to nested Clojure maps"
  [element]
  (if (string? element)
    element
    (let [{:keys [tag attrs content]} element]
      (if (empty? content)
        {tag (or (:text attrs) attrs)}
        {tag (merge attrs
                    (if (every? string? content)
                      {:text (apply str content)}
                      (apply merge (map xml->map content))))}))))

(defn fetch
  "Generic Last.fm API fetcher. Accepts method and optional params map.
   Returns parsed response as Clojure data structure."
  [method & {:as params}]
  (let [query-params (merge {:method method
                             :api_key api-key
                             :format "xml"}
                            params)
        response (http/get base-url {:query-params query-params})
        xml-data (xml/parse-str (:body response))]
    (xml->map xml-data)))

(defn get-recent-tracks
  "Fetches recent tracks for a user"
  [user & {:keys [limit] :or {limit 10}}]
  (fetch "user.getRecentTracks" :user user :limit limit))
