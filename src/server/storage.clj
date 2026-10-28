(ns server.storage
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [server.config :as config])
  (:import (java.nio.file AtomicMoveNotSupportedException CopyOption
                          Files StandardCopyOption)
           (java.util UUID)))

(defn state-file [name]
  (io/file config/state-dir name))

(defn load-edn [file fallback]
  (try
    (edn/read-string (slurp file))
    (catch java.io.FileNotFoundException _ fallback)
    (catch Exception e
      (println (str "Warning: Could not load " file ": " (.getMessage e)))
      fallback)))

(def ^:private write-lock (Object.))

(defn- temp-file [^java.io.File target]
  (io/file (or (.getParentFile target) (io/file "."))
           (str "." (.getName target) "." (UUID/randomUUID) ".tmp")))

(defn- move-options [& opts]
  (into-array CopyOption opts))

(defn- atomic-move! [^java.io.File tmp ^java.io.File target]
  (try
    (Files/move (.toPath tmp) (.toPath target)
                (move-options StandardCopyOption/ATOMIC_MOVE
                              StandardCopyOption/REPLACE_EXISTING))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath tmp) (.toPath target)
                  (move-options StandardCopyOption/REPLACE_EXISTING)))))

(defn save-edn! [file data]
  (let [target (io/file file)
        tmp (temp-file target)]
    (locking write-lock
      (try
        (spit tmp (pr-str data))
        (atomic-move! tmp target)
        true
        (catch Exception e
          (println (str "Warning: Could not save " target ": " (.getMessage e)))
          false)
        (finally
          (when (.exists tmp)
            (try
              (Files/deleteIfExists (.toPath tmp))
              (catch Exception _))))))))

(defn save-text! [file text]
  (let [target (io/file file)]
    (locking write-lock
      (try
        (spit target text)
        true
        (catch Exception e
          (println (str "Warning: Could not save " target ": " (.getMessage e)))
          false)))))
