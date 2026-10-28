(ns server.config)

(def state-dir
  (or (System/getenv "STATE_DIRECTORY") "."))

(def fiction-dir
  (System/getenv "FICTION_DIR"))

(def nd-url
  (or (System/getenv "ND_URL") "http://10.100.0.2:4533"))

(def tagger-url
  (or (System/getenv "TAGGER_URL") "http://10.100.0.2:9478"))

(def api-token
  (System/getenv "SANA_API_TOKEN"))

(defn port []
  (or (some-> (System/getenv "PORT")
              Integer/parseInt)
      45000))
