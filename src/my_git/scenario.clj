(ns my-git.scenario
  "REPL/テスト用リポジトリを組み立てる小さな部品と、その組み合わせ例。
   部品は ctx を受けて ctx を返すので -> で繋げる。"
  (:require [clojure.java.io :as io]
            [my-git.repo :as repo]
            [my-git.ref :as ref]
            [my-git.merge :as mg]))

(def base-ident {:name "Aki" :email "aki@example.com" :tz "+0900"})
(def their-branch "feature")

(defn- sf [dir path content]
  (let [f (io/file dir path)] (io/make-parents f) (spit f content)))

(defn- rm-rf [dir]
  (let [d (io/file dir)]
    (when (.exists d) (doseq [f (reverse (file-seq d))] (.delete f)))))

(defn fresh-repo
  "dir を作り直して init。ctx {:dir :git-dir :ts} を返す。"
  [dir]
  (rm-rf dir) (.mkdirs (io/file dir)) (repo/init dir)
  {:dir dir :git-dir (io/file dir ".git") :ts 1700000000})

(defn write!
  "files({path content}) を書いて add する。"
  [ctx files]
  (doseq [[p c] files] (sf (:dir ctx) p c))
  (repo/add (:dir ctx) (vec (keys files)))
  ctx)

(defn rm!
  "paths を作業ツリーから消して add（削除をステージ）。"
  [ctx & paths]
  (doseq [p paths] (.delete (io/file (:dir ctx) p)))
  (repo/add (:dir ctx) (vec paths))
  ctx)

(defn commit!
  "現 index を commit。ts を +100 して決定的な日時にし、:last に commit hash を入れる。"
  [ctx message]
  (let [ts (+ (:ts ctx) 100)
        h (repo/commit (:dir ctx) {:author (assoc base-ident :timestamp ts) :message message})]
    (assoc ctx :ts ts :last h)))

(defn branch!
  "現在の commit から名前付きブランチを作る（HEAD は動かさない）。"
  [ctx name]
  (repo/create-branch (:dir ctx) name) ctx)

(defn switch!
  "ブランチへ checkout（作業ツリーごと切替）。"
  [ctx name] 
  (repo/checkout (:dir ctx) name) ctx)

(defn result
  "組み立て後の結果 {:dir :git-dir :main :feature :base}。tip と merge-base を解決する。"
  [dir]
  (let [gd (io/file dir ".git")
        main (ref/resolve-ref gd "refs/heads/main")
        feature (ref/resolve-ref gd "refs/heads/feature")]
    {:dir dir :git-dir gd :main main :feature feature
     :base (when (and main feature) (mg/merge-base gd main feature))}))

(defn make-merge-scenario
  "共通祖先から main と feature が別ファイルを足して分岐（衝突なし）。"
  [dir]
  (-> (fresh-repo dir)
      (write! {"base.txt" "base\n"})
      (commit! "c1 base")
      (branch! their-branch)
      (write! {"m.txt" "main change\n"})
      (commit! "c2 main")
      (switch! their-branch)
      (write! {"f.txt" "feat change\n"})
      (commit! "c3 feature")
      (switch! "main"))
  (result dir))

(defn make-conflict-scenario
  "同じ a.txt を main と feature が別々に変える（衝突）。"
  [dir]
  (-> (fresh-repo dir)
      (write! {"a.txt" "line1\n"})
      (commit! "c1")
      (branch! their-branch)
      (write! {"a.txt" "MAIN edit\n"}) 
      (commit! "main edit")
      (switch! their-branch)
      (write! {"a.txt" "FEATURE edit\n"})
      (commit! "feat edit")
      (switch! "main"))
  (result dir))

(defn make-ff-scenario
  "main は c1 のまま feature だけ進める（fast-forward できる）。"
  [dir]
  (-> (fresh-repo dir)
      (write! {"a.txt" "a\n"})
      (commit! "c1")
      (branch! their-branch)
      (switch! their-branch)
      (write! {"f.txt" "feat\n"})
      (commit! "feat")
      (switch! "main"))
  (result dir))

(defn merge-scenario
  [dir]
  (repo/merge-branch dir their-branch {:author (assoc base-ident :timestamp 1700000300)}))
