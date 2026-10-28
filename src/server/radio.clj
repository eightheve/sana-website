(ns server.radio
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [server.config :as config]
            [server.storage :as storage])
  (:import (java.util Base64)))

(defn- ids-file []
  (storage/state-file "radio-ids.edn"))

(defn- mbid-file []
  (storage/state-file "radio-map.json"))

(defn- load-json [f fallback]
  (try
    (with-open [r (io/reader f)]
      (json/read r))
    (catch Exception _ fallback)))

(def ^:private mbid-map
  "id_hash -> MusicBrainz release id. Sourced from the tagger's /radio-map
  artifact (regenerated with every blob build); mirrored to the state dir so
  restarts and tagger outages degrade gracefully. Refreshed from upstream on
  cache miss." 
  (atom (load-json (mbid-file) {})))

(def ^:private nd-ids
  "id_hash -> navidrome album id, persisted across restarts." 
  (atom (storage/load-edn (ids-file) {})))

(add-watch nd-ids :persist
           (fn [_ _ _ new]
             (storage/save-edn! (ids-file) new)))

(defn- body-str [resp]
  (let [b (:body resp)]
    (if (string? b) b (slurp b))))

(defn- refresh-mbid-map! []
  (try
    (let [resp (http/get (str config/tagger-url "/radio-map")
                         {:as :string :throw-exceptions false
                          :socket-timeout 15000 :conn-timeout 15000})]
      (when (= 200 (:status resp))
        (let [m (json/read-str (body-str resp))]
          (when (seq m)
            (reset! mbid-map m)
            (storage/save-text! (mbid-file) (body-str resp))))
        true))
    (catch Exception _ false)))

(def cookie-name "ag_radio")

(defn- b64-encode [s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- b64-decode [s]
  (String. (.decode (Base64/getDecoder) s) "UTF-8"))

(defn- cookies [req]
  (let [raw (get-in req [:headers "cookie"] "")]
    (into {}
          (for [pair (string/split raw #";\s*")
                :when (string/includes? pair "=")]
            (let [[k v] (string/split pair #"=" 2)] [k v])))))

(defn- session [req]
  (try
    (let [raw (get (cookies req) cookie-name)]
      (when raw
        (json/read-str (b64-decode raw)
                                         :key-fn keyword)))
    (catch Exception _)))

(defn- session-cookie [sess]
  (str cookie-name "=" (b64-encode (json/write-str sess))
       "; Path=/spaces/album-graph; HttpOnly; SameSite=Lax; Max-Age=604800"))

(defn- clear-cookie []
  (str cookie-name "=; Path=/spaces/album-graph; HttpOnly; SameSite=Lax; Max-Age=0"))

(defn- json-resp [status body & [cookies]]
  {:status status
   :headers (cond-> {"Content-Type" "application/json"}
              cookies (assoc "Set-Cookie" cookies))
   :body (json/write-str body)})

(defn- ss-params [sess]
  {:u (:user sess) :t (:sub-token sess) :s (:sub-salt sess)
   :v "1.16.1" :c "sana-radio" :f "json"})

(defn- ss-get [sess endpoint params]
  (let [resp (http/get (str config/nd-url "/rest/" endpoint ".view")
                       {:query-params (merge (ss-params sess) params)
                        :as :string :socket-timeout 15000 :conn-timeout 15000})]
    (json/read-str (body-str resp) :key-fn keyword)))

(defn- nd-post [path body]
  (let [resp (http/post (str config/nd-url path)
                        {:body (json/write-str body)
                         :headers {"Content-Type" "application/json"}
                         :as :string :socket-timeout 15000 :conn-timeout 15000})]
    (json/read-str (body-str resp) :key-fn keyword)))

(defn- find-album-id [sess mbid artist album]
  (let [clean (and album (string/replace album #"^\d{4}\s*-\s*" ""))
        query (or mbid (when (and artist clean) (str artist " " clean)))]
    (when query
      (let [resp (ss-get sess "search3" {:query query :albumCount 5
                                         :artistCount 0 :songCount 0})
            hits (get-in resp [:subsonic-response :searchResult3 :album])
            exact (when mbid (some #(when (= (:musicBrainzId %) mbid) %) hits))
            hit (or exact (first hits))]
        (:id hit)))))

(defn- resolve-album [sess idh artist album]
  (or (get @nd-ids idh)
      (let [_ (when-not (contains? @mbid-map idh) (refresh-mbid-map!))
            ndid (find-album-id sess (get @mbid-map idh) artist album)]
        (when ndid
          (swap! nd-ids assoc idh ndid)
          ndid))))

(defn login [req]
  (let [params (:form-params req)
        user (get params "username")
        pass (get params "password")]
    (if (and user pass (not= user "api"))
      (try
        (let [body (nd-post "/auth/login" {:username user :password pass})]
          (json-resp 200 {:user (:name body)}
                      (session-cookie {:user (:name body)
                                       :nd-token (:token body)
                                       :sub-token (:subsonicToken body)
                                       :sub-salt (:subsonicSalt body)})))
        (catch Exception _
          (json-resp 401 {:error "invalid credentials"})))
      (json-resp 401 {:error "invalid credentials"}))))

(defn logout [_req]
  (json-resp 200 {:ok true} (clear-cookie)))

(defn status [req]
  (if-let [sess (session req)]
    (json-resp 200 {:user (:user sess)})
    (json-resp 401 {:error "not logged in"})))

(defn album [req]
  (if-let [sess (session req)]
    (try
      (let [params (:params req)
            idh (get params "id_hash")
            artist (get params "artist")
            album (get params "album")]
        (if-let [r (resolve-album sess idh artist album)]
          (let [resp (ss-get sess "getAlbum" {:id r})
                al (get-in resp [:subsonic-response :album])]
            (if al
              (json-resp 200 {:id_hash idh
                              :ndid (:id al)
                              :name (:name al)
                              :artist (:artist al)
                              :coverArt (:coverArt al)
                              :songs (mapv (fn [s] {:id (:id s)
                                                    :title (:title s)
                                                    :track (:track s)
                                                    :duration (:duration s)
                                                    :suffix (:suffix s)})
                                           (:song al))})
              (json-resp 404 {:error "album not found in navidrome"})))
          (json-resp 404 {:error "no navidrome match for album"})))
      (catch Exception e
        (json-resp 502 {:error (str "navidrome error: " (.getMessage e))})))
    (json-resp 401 {:error "not logged in"})))

(defn- upstream-headers [resp]
  (select-keys (:headers resp)
               ["content-type" "content-length" "content-range" "accept-ranges"
                "etag" "last-modified"]))

(defn stream [req]
  (if-let [sess (session req)]
    (try
      (let [id (get (:params req) "id")
            range (get-in req [:headers "range"])
            resp (http/get (str config/nd-url "/rest/stream.view")
                           {:query-params (assoc (ss-params sess) :id id)
                            :as :stream :decompress-body false
                            :throw-exceptions false
                            :headers (cond-> {} range (assoc "Range" range))
                            :socket-timeout 30000 :conn-timeout 15000})]
        (if (#{200 206} (:status resp))
          {:status (:status resp)
           :headers (assoc (upstream-headers resp)
                           "Content-Encoding" "identity")
           :body (:body resp)}
          (json-resp 502 {:error "stream failed"})))
      (catch Exception _
        (json-resp 502 {:error "navidrome unreachable"})))
    (json-resp 401 {:error "not logged in"})))

(defn cover [req]
  (if-let [sess (session req)]
    (try
      (let [id (get (:params req) "id")
            resp (http/get (str config/nd-url "/rest/getCoverArt.view")
                           {:query-params (merge (ss-params sess)
                                                 {:id id :size 800})
                            :as :byte-array :decompress-body false
                            :throw-exceptions false
                            :socket-timeout 15000 :conn-timeout 15000})]
        (if (= 200 (:status resp))
          {:status 200
           :headers (assoc (select-keys (:headers resp) ["content-type"])
                           "content-length" (str (alength (:body resp)))
                           "Cache-Control" "public, max-age=86400")
           :body (:body resp)}
          (json-resp 404 {:error "no cover"})))
      (catch Exception _
        (json-resp 502 {:error "navidrome unreachable"})))
    (json-resp 401 {:error "not logged in"})))

(defn scrobble [req]
  (if-let [sess (session req)]
    (try
      (let [id (get (:params req) "id")
            resp (ss-get sess "scrobble" {:id id :submission true})]
        (if (= :ok (get-in resp [:subsonic-response :status]))
          (json-resp 200 {:ok true})
          (json-resp 502 {:error "scrobble rejected"})))
      (catch Exception _
        (json-resp 502 {:error "navidrome unreachable"})))
    (json-resp 401 {:error "not logged in"})))
