(ns my-git.repo
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [my-git.commit :as commit]
            [my-git.index :as index]
            [my-git.object :as obj]
            [my-git.ref :as ref]
            [my-git.tree :as tree])
  (:import [java.nio.file Files]
           [java.io File]))

(def ref-head "refs/heads")

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
  [^File f]
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
                   (let [f (io/file dir path)]
                     (if (.exists f)
                       (let [content (Files/readAllBytes (.toPath f))
                             sha (obj/write-object git-dir "blob" content)]
                         (assoc m path {:mode (file-mode f) :sha sha :path path}))
                       (dissoc m path))))
                 by-path
                 paths)]
    (index/write-index git-dir (vals updated))
    (index/read-index git-dir)))

(defn rm-cached
  [dir path]
  (let [git-dir (io/file dir ".git")
        kept (remove #(= (:path %) path) (index/read-index git-dir))]
    (index/write-index git-dir kept)
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

(defn create-branch
  [dir name & [start-hash]]
  (let [git-dir (io/file dir ".git")
        h (or start-hash (ref/resolve-ref git-dir "HEAD"))]
    (when-not h
      (throw (ex-info "unborn HEAD" {:name name})))
    (ref/update-ref git-dir (str "refs/heads/" name) h)
    name))

(defn list-branches
  [dir]
  (let [git-dir (io/file dir ".git")
        heads (io/file git-dir ref-head)
        prefix (str (.getPath heads) "/")
        names (when (.exists heads)
                (->> (file-seq heads)
                     (filter #(.isFile ^File %))
                     (map #(subs (.getPath ^File %) (count prefix)))
                     sort
                     vec))
        head (ref/read-ref git-dir "HEAD")
        current (when (and head (str/starts-with? head "ref: refs/heads/"))
                  (subs head (count "ref: refs/heads/")))]
    {:branches (or names []) :current current}))

(defn delete-branch
  [dir name]
  (let [git-dir (io/file dir ".git")]
    (when (= name (:current (list-branches dir)))
      (throw (ex-info "This branch is checked out now" {:name name})))
    (.delete (io/file git-dir ref-head name))
    name))

(defn switch-head
  [dir name]
  (let [git-dir (io/file dir ".git")]
    (when-not (.exists (io/file git-dir ref-head name))
      (throw (ex-info "This branch does not exist" {:name name})))
    (ref/set-symbolic-ref git-dir "HEAD" (str ref-head "/" name))
    name))

(defn- tree->entries
  [git-dir tree-hash]
  (letfn [(walk [prefix h]
                (mapcat (fn [{:keys [mode name hash]}]
                          (let [p (if (empty? prefix) name (str prefix "/" name))]
                            (if (= mode "40000")
                              (walk p hash)
                              [{:mode mode :sha hash :path p}])))
                        (tree/read-tree git-dir h)))]
    (walk "" tree-hash)))

(defn restore-staged
  "index の path を HEAD の版に戻す（HEAD に無ければ index から削除）。作業ツリーは触らない。
   git restore --staged / git reset HEAD <path> 相当。"
  [dir path]
  (let [git-dir (io/file dir ".git")
        head-entries (if-let [c (ref/resolve-ref git-dir "HEAD")]
                       (tree->entries git-dir (:tree (commit/read-commit git-dir c)))
                       [])
        head-by-path (into {} (map (juxt :path identity) head-entries))
        without (remove #(= (:path %) path) (index/read-index git-dir))
        restored (if-let [e (head-by-path path)]
                   (conj (vec without) e)
                   (vec without))]
    (index/write-index git-dir restored)
    (index/read-index git-dir)))

(defn checkout
  [dir target]
  (let [git-dir (io/file dir ".git")
        branch? (.exists (io/file git-dir ref-head target))
        commit-hash (if branch?
                      (ref/resolve-ref git-dir (str ref-head "/" target))
                      target)
        tree (:tree (commit/read-commit git-dir commit-hash))
        entries (tree->entries git-dir tree)
        target-paths (set (map :path entries))
        current (when (.exists (io/file git-dir "index"))
                  (index/read-index git-dir))]
    (doseq [p (remove target-paths (map :path current))]
      (.delete (io/file dir p)))
    (doseq [{:keys [mode sha path]} entries]
      (let [content (:content (obj/read-object git-dir sha))
            f (io/file dir path)]
        (io/make-parents f)
        (with-open [o (io/output-stream f)]
          (.write o ^bytes content))
        (when (= mode "100755")
          (.setExecutable f true))))
    (index/write-index git-dir entries)
    (if branch?
      (ref/set-symbolic-ref git-dir "HEAD" (str ref-head "/" target))
      (ref/update-ref git-dir "HEAD" commit-hash))
    commit-hash))

(defn- blob-hash
  "ファイルの中身から blob hash を計算。"
  [^File f]
  (obj/sha1-hex (obj/object-bytes "blob" (Files/readAllBytes (.toPath f)))))

(defn- head-tree-map
  "HEAD の commit の tree を {path hash} に。unborn なら {}。"
  [git-dir]
  (if-let [c (ref/resolve-ref git-dir "HEAD")]
    (into {} (tree/read-tree-recursive git-dir (:tree (commit/read-commit git-dir c))))
    {}))

(defn- index-map
  [git-dir]
  (if (.exists (io/file git-dir "index"))
    (into {} (map (juxt :path :sha) (index/read-index git-dir)))
    {}))

(defn- worktree-map
  "作業ツリーの全ファイル(.git 除く)を {相対path → blob hash} に。"
  [dir]
  (let [root (io/file dir)
        rp (.toPath root)]
    (into {}
          (for [^File f (file-seq root)
                :when (.isFile f)
                :let [rel (str (.relativize rp (.toPath f)))]
                :when (not (str/starts-with? rel ".git/"))]
            [rel (blob-hash f)]))))

(defn status
  "HEAD tree / index / 作業ツリー の三者比較。git status 相当。"
  [dir]
  (let [git-dir (io/file dir ".git")
        head (head-tree-map git-dir)
        idx (index-map git-dir)
        wt (worktree-map dir)
        hp (set (keys head))
        ip (set (keys idx))
        wp (set (keys wt))]
    {:branch (:current (list-branches dir))
     :staged {:added (sort (set/difference ip hp))
              :deleted (sort (set/difference hp ip))
              :modified (sort (for [p (set/intersection hp ip)
                                    :when (not= (head p) (idx p))]
                                p))}
     :not-staged {:modified (sort (for [p (set/intersection ip wp)
                                        :when (not= (idx p) (wt p))] 
                                    p))
                  :deleted (sort (set/difference ip wp))}
     :untracked (sort (set/difference wp ip))}))

(defn format-status
  "git status --short 風の XY 表示。"
  [{:keys [staged not-staged untracked]}]
  (let [x (merge (zipmap (:added staged) (repeat "A"))
                 (zipmap (:modified staged) (repeat "M"))
                 (zipmap (:deleted staged) (repeat "D")))
        y (merge (zipmap (:modified not-staged) (repeat "M"))
                 (zipmap (:deleted not-staged) (repeat "D")))
        paths (sort (distinct (concat (keys x) (keys y) untracked)))]
    (str/join "\n"
              (for [p paths]
                (if (some #{p} untracked)
                  (str "?? " p)
                  (str (get x p " ") (get y p " ") " " p))))))
