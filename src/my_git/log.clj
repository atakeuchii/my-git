(ns my-git.log
  (:require [my-git.commit :as commit]
            [my-git.ref :as ref]
            [clojure.set :as set]
            [clojure.string :as str]
            [my-git.tree :as tree])
  (:import [java.time Instant ZoneOffset OffsetDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^:private date-fmt
  (DateTimeFormatter/ofPattern "EEE MMM d HH:mm:ss yyyy Z" Locale/ENGLISH))

(defn- format-date
  [timestamp tz]
  (-> (Instant/ofEpochSecond timestamp)
      (OffsetDateTime/ofInstant (ZoneOffset/of tz))
      (.format date-fmt)))

(defn format-commit
  [{:keys [hash commit]}]
  (let [{:keys [author message]} commit
        {:keys [name email timestamp tz]} author
        body (->> (str/split-lines (str/trimr message))
                  (map #(if (str/blank? %) "" (str "    " %)))
                  (str/join "\n"))]
    (str "commit " hash "\n"
         "Author: " name " <" email ">\n"
         "Date:   " (format-date timestamp tz) "\n"
         "\n"
         body "\n")))

(defn commit-seq
  [git-dir start-hash]
  (when start-hash
    (lazy-seq
     (let [c (commit/read-commit git-dir start-hash)]
       (cons {:hash start-hash :commit c}
             (commit-seq git-dir (first (:parents c))))))))

(defn log
  ([git-dir start-hash] (log git-dir start-hash nil))
  ([git-dir start-hash n]
   (->> (cond->> (commit-seq git-dir start-hash)
          n (take n))
        (map format-commit)
        (str/join "\n"))))

(defn- tree->map
  [git-dir tree-hash]
  (into {} (tree/read-tree-recursive git-dir tree-hash)))

(defn diff-trees
  [git-dir old-tree new-tree]
  (let [o (tree->map git-dir old-tree)
        n (tree->map git-dir new-tree)
        op (set (keys o))
        np (set (keys n))]
    {:added (sort (set/difference np op))
     :deleted (sort (set/difference op np))
     :modified (sort (for [p (set/intersection op np)
                           :when (not= (o p) (n p))]
                       p))}))

(defn diff-commits
  [git-dir old-commit new-commit]
  (diff-trees git-dir
              (:tree (commit/read-commit git-dir old-commit))
              (:tree (commit/read-commit git-dir new-commit))))

(defn format-diff
  [{:keys [added deleted modified]}]
  (str/join "\n"
            (concat (map #(str "A\t" %) added)
                    (map #(str "D\t" %) deleted)
                    (map #(str "M\t" %) modified))))
