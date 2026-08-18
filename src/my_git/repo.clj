(ns my-git.repo
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [my-git.commit :as commit]
            [my-git.index :as index]
            [my-git.object :as obj]
            [my-git.ref :as ref]
            [my-git.tree :as tree])
  (:import [java.nio.file Files]))

(defn init
  [dir]
  (let [git-dir (io/file dir ".git")]
    (doseq [d ["objects" "refs/heads" "refs/tags"]]
      (.mkdirs (io/file git-dir d)))
    (spit (io/file git-dir "config")
          "[core]\n\trepositoryformatversion = 0\n\tbare = false\n")
    (ref/set-symbolic-ref git-dir "HEAD" "refs/heads/main")
    git-dir))

(defn- file-mode
  "実行可能なら 100755、それ以外は 100644"
  [^java.io.File f]
  (if (.canExecute f) "100755" "100644"))

(defn add
  "作業ツリー dir の paths(相対) を blob 化して index に upsert する"
  [dir paths]
  (let [git-dir (io/file dir ".git")
        index-file (io/file git-dir "index")
        existing (if (.exists index-file)
                   (index/read-index git-dir)
                   [])
        by-path (reduce #(assoc %1 (:path %2) %2) {} existing)
        updated (reduce
                 (fn [m path]
                   (let [f (io/file dir path)
                         content (Files/readAllBytes (.toPath f))
                         sha (obj/write-object git-dir "blob" content)]
                     (assoc m path {:mode (file-mode f) :sha sha :path path})))
                 by-path
                 paths)]
    (index/write-index git-dir (vals updated))
    (index/read-index git-dir)))

(defn- build-tree
  "index エントリ列(相対パス)から階層 tree を構築し、ルート tree の hash を返す。
   git の write-tree(index→tree) 相当。"
  [git-dir entries]
  (->> entries
       (group-by (fn [e]
                   (let [p (:path e)
                         i (.indexOf ^String p "/")]
                     (when-not (neg? i) (subs p 0 i)))))
       (mapcat (fn [[dir es]]
                 (if (nil? dir)
                   (map (fn [e] {:mode (:mode e) :name (:path e) :hash (:sha e)}) es)
                   ;; サブディレクトリ: 先頭コンポーネントを剥がして再帰
                   (let [sub (map #(update % :path (fn [p] (subs p (inc (count dir))))) es)]
                     [{:mode "40000" :name dir :hash (build-tree git-dir sub)}]))))
       (tree/write-tree git-dir)))

(defn commit
  "index から階層 tree を作り、親=現HEADの commit を作って、HEADが指すブランチを進める。
   opts = {:author {:name :email :timestamp :tz}, :committer <省略時author>, :message \"..\"}"
  [dir {:keys [author committer message]}]
  (let [git-dir (io/file dir ".git")
        entries (index/read-index git-dir)
        tree (build-tree git-dir entries)
        parent (ref/resolve-ref git-dir "HEAD")
        chash (commit/write-commit git-dir
                                   {:tree tree
                                    :parents (if parent [parent] [])
                                    :author author
                                    :committer (or committer author)
                                    :message message})
        head (ref/read-ref git-dir "HEAD")]
    (if (and head (str/starts-with? head "ref: "))
      (ref/update-ref git-dir (subs head 5) chash)
      (ref/update-ref git-dir "HEAD" chash))
    chash))
