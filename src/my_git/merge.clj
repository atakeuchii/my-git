(ns my-git.merge
  (:require [my-git.commit :as commit]
            [my-git.tree :as tree])
  (:import [clojure.lang PersistentQueue]))

(defn- parents-of [git-dir h]
  (:parents (commit/read-commit git-dir h)))

(defn commit-ancestors
  [git-dir h]
  (loop [stack [h]
         seen #{}]
    (if (empty? stack)
      seen
      (let [c (peek stack)
            stack (pop stack)]
        (if (contains? seen c)
          (recur stack seen)
          (recur (into stack (parents-of git-dir c)) (conj seen c)))))))

(defn merge-base
  "a と b の共通祖先のうち b から最も近いものを返す"
  [git-dir a b]
  (let [anc-a (commit-ancestors git-dir a)]
    (loop [q (conj PersistentQueue/EMPTY b)
           seen #{}]
      (when (seq q)
        (let [c (peek q)
              q (pop q)]
          (cond 
            (contains? seen c) (recur q seen) 
            (contains? anc-a c) c
            :else (recur (into q (parents-of git-dir c)) (conj seen c))))))))

(defn- tree-entry-pairs
  [git-dir prefix h]
  (mapcat (fn [{:keys [mode name hash]}]
            (let [p (if (empty? prefix) name (str prefix "/" name))]
              (if (= mode "40000")
                (tree-entry-pairs git-dir p hash)
                [[p {:mode mode :sha hash}]])))
          (tree/read-tree git-dir h)))

(defn tree-path-map
  "tree hash -> {path {:mode :sha}}"
  [git-dir tree-hash]
  (if (nil? tree-hash)
    {}
    (into {} (tree-entry-pairs git-dir "" tree-hash))))

(defn- commit->tree
  [git-dir c]
  (when c (:tree (commit/read-commit git-dir c))))

(defn merge-trees
  [git-dir base ours theirs]
  (let [b (tree-path-map git-dir (commit->tree git-dir base))
        o (tree-path-map git-dir (commit->tree git-dir ours))
        t (tree-path-map git-dir (commit->tree git-dir theirs))
        all (sort (into #{} (concat (keys b) (keys o) (keys t))))]
    (reduce
     (fn [acc path]
       (let [bh (get b path)
             oh (get o path)
             th (get t path)]
         (cond
           (= oh th) (if oh (assoc-in acc [:merged path] oh) acc)
           (= oh bh) (if th (assoc-in acc [:merged path] th) acc)
           (= th bh) (if oh (assoc-in acc [:merged path] oh) acc)
           :else (update acc :conflicts conj path))))
     {:merged {} :conflicts []}
     all)))

