(ns server.fiction
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [markdown.core :as md])
  (:import [java.util UUID]))

(def fiction-dir
  (System/getenv "FICTION_DIR"))

;; ---- file helpers ----

(defn- slurp-if-exists [f]
  (let [file (io/file f)]
    (when (.exists file)
      (string/trim (slurp file)))))

(defn- render-md [text]
  (when text
    (md/md-to-html-string text)))

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
  (let [dir (io/file fiction-dir)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           (filter #(not (string/starts-with? (.getName %) ".")))
           (sort-by #(.getName %))
           (map read-project)))))

(defn get-project [slug]
  (let [dir (io/file fiction-dir slug)]
    (when (.exists dir)
      (read-project dir))))

(defn get-chapter [project-slug chapter-slug]
  (let [project (get-project project-slug)]
    (when-let [chapter (->> (:chapters project)
                            (filter #(= (:slug %) chapter-slug))
                            first)]
      (let [file (io/file fiction-dir project-slug "chapters" (:file chapter))]
        (assoc chapter :html (render-md (slurp-if-exists file)))))))

;; ---- downloads ----

(defn- pandoc->epub [markdown title output-path]
  (let [tmp-input (str "/tmp/fiction-pandoc-" (UUID/randomUUID) ".md")]
    (spit tmp-input markdown)
    (let [result (clojure.java.shell/sh
                   "pandoc" tmp-input
                   "-f" "markdown"
                   "-t" "epub"
                   "--metadata" (str "title=" title)
                   "-o" output-path)]
      (io/delete-file tmp-input true)
      (when-not (zero? (:exit result))
        (throw (ex-info (str "pandoc failed: " (:err result)) {}))))
    output-path))

(defn chapter-txt [project-slug chapter-slug]
  (let [project (get-project project-slug)]
    (when-let [chapter (->> (:chapters project)
                            (filter #(= (:slug %) chapter-slug))
                            first)]
      (let [file (io/file fiction-dir project-slug "chapters" (:file chapter))]
        {:title   (str (:title project) " - " (string/replace (:slug chapter) "-" " "))
         :slug    (:slug chapter)
         :content (slurp-if-exists file)}))))

(defn chapter-epub [project-slug chapter-slug]
  (let [project (get-project project-slug)]
    (when-let [chapter (->> (:chapters project)
                            (filter #(= (:slug %) chapter-slug))
                            first)]
      (let [file     (io/file fiction-dir project-slug "chapters" (:file chapter))
            markdown (slurp-if-exists file)
            epub     (str "/tmp/fiction-" project-slug "-" chapter-slug "-"
                          (UUID/randomUUID) ".epub")
            title    (str (:title project) " - "
                          (string/replace (:slug chapter) "-" " "))]
        (pandoc->epub markdown title epub)
        {:file     epub
         :filename (str project-slug "-" (:file chapter) ".epub")}))))

(defn project-txt [project-slug]
  (let [project (get-project project-slug)]
    (when project
      (let [chapter-texts (->> (:chapters project)
                               (sort-by :order)
                               (keep (fn [ch]
                                       (slurp-if-exists
                                        (io/file fiction-dir project-slug
                                                 "chapters" (:file ch))))))]
        {:title   (:title project)
         :content (string/join "\n\n---\n\n" chapter-texts)}))))

(defn project-epub [project-slug]
  (let [project (get-project project-slug)]
    (when project
      (let [chapter-files (->> (:chapters project)
                               (sort-by :order)
                               (map #(io/file fiction-dir project-slug
                                              "chapters" (:file %))))
            tmp-input  (str "/tmp/fiction-pandoc-" (UUID/randomUUID) ".md")
            combined   (->> chapter-files
                            (keep slurp-if-exists)
                            (string/join "\n\n---\n\n"))]
        (spit tmp-input combined)
        (let [epub   (str "/tmp/fiction-" project-slug "-"
                          (UUID/randomUUID) ".epub")
              result (clojure.java.shell/sh
                       "pandoc" tmp-input
                       "-f" "markdown"
                       "-t" "epub"
                       "--metadata" (str "title=" (:title project))
                       "-o" epub)]
          (io/delete-file tmp-input true)
          (when-not (zero? (:exit result))
            (throw (ex-info (str "pandoc failed: " (:err result)) {})))
          {:file     epub
           :filename (str project-slug ".epub")})))))
