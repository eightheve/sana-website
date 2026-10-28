(ns server.fiction
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [markdown.core :as md]
            [server.config :as config]
            [clojure.java.shell])
  (:import [java.util UUID]))

;; ---- file helpers ----

(defn- slurp-if-exists [f]
  (let [file (io/file f)]
    (when (.exists file)
      (string/trim (slurp file)))))

(defn- render-md [text]
  (when text
    (md/md-to-html-string text)))

(defn- safe-file [& parts]
  (when config/fiction-dir
    (let [root      (.getCanonicalFile (io/file config/fiction-dir))
          file      (.getCanonicalFile (apply io/file config/fiction-dir parts))
          root-path (.getPath root)]
      (when (string/starts-with? (.getPath file)
                                 (str root-path java.io.File/separator))
        file))))

;; ---- chapter filename parsing ----

(defn- parse-chapter-filename [filename]
  (when-let [[_ order slug] (re-matches #"(\d+)_(.+)\.md" filename)]
    {:order (Integer/parseInt order)
     :slug  slug
     :file  filename}))

(defn- list-chapters [project-dir]
  (let [chapters-dir (io/file project-dir "chapters")]
    (when (.exists chapters-dir)
      (->> (.listFiles chapters-dir)
           (keep (comp parse-chapter-filename #(.getName %)))
           (sort-by :order)))))

;; ---- project scanning ----

(defn- read-project [dir]
  (let [slug (.getName dir)]
    {:slug    slug
     :title   (or (slurp-if-exists (io/file dir "title.txt")) slug)
     :blurb   (render-md (slurp-if-exists (io/file dir "blurb.md")))
     :intro   (render-md (slurp-if-exists (io/file dir "intro.md")))
     :chapters (list-chapters dir)}))

(defn list-projects []
  (when config/fiction-dir
    (let [dir (io/file config/fiction-dir)]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isDirectory %))
             (filter #(not (string/starts-with? (.getName %) ".")))
             (sort-by #(.getName %))
             (map read-project))))))

(defn get-project [slug]
  (when-let [dir (safe-file slug)]
    (when (and (.exists dir) (.isDirectory dir))
      (read-project dir))))

(defn- find-chapter [project-slug chapter-slug]
  (when-let [project (get-project project-slug)]
    (when-let [chapter (->> (:chapters project)
                            (filter #(= (:slug %) chapter-slug))
                            first)]
      (when-let [file (safe-file project-slug "chapters" (:file chapter))]
        {:project project
         :chapter chapter
         :file    file}))))

(defn chapter-page-data [project-slug chapter-slug]
  (when-let [{:keys [project chapter file]}
             (find-chapter project-slug chapter-slug)]
    {:project project
     :chapter (assoc chapter :html (render-md (slurp-if-exists file)))}))

;; ---- downloads ----

(defn- pandoc->epub [markdown title output-path]
  (let [tmp-input (str "/tmp/fiction-pandoc-" (UUID/randomUUID) ".md")]
    (try
      (spit tmp-input markdown)
      (let [result (clojure.java.shell/sh
                     "pandoc" tmp-input
                     "-f" "markdown"
                     "-t" "epub"
                     "--metadata" (str "title=" title)
                     "-o" output-path)]
        (when-not (zero? (:exit result))
          (throw (ex-info (str "pandoc failed: " (:err result)) {}))))
      (catch Exception e
        (io/delete-file output-path true)
        (throw e))
      (finally
        (io/delete-file tmp-input true)))
    output-path))

(defn chapter-txt [project-slug chapter-slug]
  (when-let [{:keys [chapter file]} (find-chapter project-slug chapter-slug)]
    {:slug    (:slug chapter)
     :content (slurp-if-exists file)}))

(defn chapter-epub [project-slug chapter-slug]
  (when-let [{:keys [project chapter file]}
             (find-chapter project-slug chapter-slug)]
    (let [markdown (slurp-if-exists file)
          epub     (str "/tmp/fiction-" project-slug "-" chapter-slug "-"
                        (UUID/randomUUID) ".epub")
          title    (str (:title project) " - "
                        (string/replace (:slug chapter) "-" " "))]
      (pandoc->epub markdown title epub)
      {:file     epub
       :filename (str project-slug "-" (:file chapter) ".epub")})))

(defn project-txt [project-slug]
  (when-let [project (get-project project-slug)]
    (let [chapter-texts (->> (:chapters project)
                             (sort-by :order)
                             (keep (fn [ch]
                                     (when-let [file (safe-file project-slug
                                                                "chapters"
                                                                (:file ch))]
                                       (slurp-if-exists file)))))]
      {:content (string/join "\n\n---\n\n" chapter-texts)})))

(defn project-epub [project-slug]
  (when-let [project (get-project project-slug)]
    (let [chapter-texts (->> (:chapters project)
                             (sort-by :order)
                             (keep (fn [ch]
                                     (when-let [file (safe-file project-slug
                                                                "chapters"
                                                                (:file ch))]
                                       (slurp-if-exists file)))))
          epub          (str "/tmp/fiction-" project-slug "-"
                            (UUID/randomUUID) ".epub")]
      (pandoc->epub (string/join "\n\n---\n\n" chapter-texts)
                    (:title project)
                    epub)
      {:file     epub
       :filename (str project-slug ".epub")})))
