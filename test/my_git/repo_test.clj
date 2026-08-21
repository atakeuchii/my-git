(ns my-git.repo-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.repo :as repo]
            [my-git.commit :as commit]
            [my-git.test-util :refer [ident spit-file temp-dir git-env cleanup-fixture]]))

(use-fixtures :each cleanup-fixture)

(defn- base-repo []
  (let [dir (temp-dir)]
    (repo/init dir)
    (spit-file dir "a.txt" "hello\n")
    (spit-file dir "b.txt" "world\n")
    (repo/add dir ["a.txt" "b.txt"])
    (repo/commit dir {:author ident :message "c1"})
    dir))

(defn- git-oracle-commit
  "別 temp repo で同じ内容を git add→write-tree→commit-tree し commit hash を返す（オラクル）。"
  [files]
  (let [d (temp-dir)]
    (sh/sh "git" "init" "-q" :dir d)
    (doseq [[p c] files] (spit-file d p c))
    (sh/sh "git" "add" "-A" :dir d)
    (let [tree (str/trim (:out (sh/sh "git" "write-tree" :dir d)))]
      (str/trim (:out (sh/sh "git" "commit-tree" tree "-m" "first commit"
                             :dir d :env (git-env)))))))

(deftest full-pipeline-matches-git
  (testing "init→add→commit の hash が本物 git と一致"
    (let [dir   (temp-dir)
          files {"a.txt" "hello\n" "bb.txt" "world\n" "src/core.clj" "(ns core)\n"}]
      (repo/init dir)
      (doseq [[p c] files] (spit-file dir p c))
      (repo/add dir (keys files))
      (let [ours   (repo/commit dir {:author ident :message "first commit"})
            oracle (git-oracle-commit files)]
        (is (= oracle ours))))))

(deftest git-reads-our-repo
  (testing "自作コマンドだけで作った repo を本物 git が読める"
    (let [dir (temp-dir)]
      (repo/init dir)
      (spit-file dir "a.txt" "hello\n")
      (repo/add dir ["a.txt"])
      (repo/commit dir {:author ident :message "first commit"})
      (is (str/includes? (:out (sh/sh "git" "log" "--oneline" :dir dir)) "first commit"))
      (is (str/includes? (:out (sh/sh "git" "status" :dir dir)) "working tree clean"))
      (is (= "hello\n" (:out (sh/sh "git" "cat-file" "-p" "HEAD:a.txt" :dir dir)))))))

(deftest second-commit-links-parent
  (testing "2回目の commit で親チェーンが伸びる"
    (let [dir (temp-dir)]
      (repo/init dir)
      (spit-file dir "a.txt" "hello\n")
      (repo/add dir ["a.txt"])
      (let [c1 (repo/commit dir {:author ident :message "first"})]
        (spit-file dir "a.txt" "hello again\n")
        (repo/add dir ["a.txt"])
        (let [c2 (repo/commit dir {:author (assoc ident :timestamp 1700000100) :message "second"})]
          (is (= [c1] (:parents (commit/read-commit (io/file dir ".git") c2))))
          (is (= 2 (count (str/split-lines (:out (sh/sh "git" "log" "--oneline" :dir dir)))))))))))

(deftest branch-recognized-by-git
  (testing "create-branch を本物 git が認識"
    (let [dir (base-repo)]
      (repo/create-branch dir "feature")
      (is (str/includes? (:out (sh/sh "git" "-C" (str dir) "branch")) "feature")))))

(deftest checkout-swaps-worktree
  (testing "checkout の往復で作業ツリーが入れ替わる"
    (let [dir (base-repo)]
      (repo/create-branch dir "feature")
      (repo/checkout dir "feature")
      (.delete (io/file dir "b.txt"))
      (spit-file dir "c.txt" "ccc\n")
      (repo/add dir ["b.txt" "c.txt"])
      (repo/commit dir {:author (assoc ident :timestamp 1700000100) :message "feat"})
      (repo/checkout dir "main")
      (is (.exists (io/file dir "b.txt")))
      (is (not (.exists (io/file dir "c.txt"))))
      (is (str/includes? (:out (sh/sh "git" "-C" (str dir) "status")) "working tree clean"))
      (repo/checkout dir "feature")
      (is (.exists (io/file dir "c.txt")))
      (is (not (.exists (io/file dir "b.txt")))))))

(deftest status-three-way
  (testing "status の三分類が git と一致"
    (let [dir (base-repo)]
      (spit-file dir "c.txt" "ccc\n") (repo/add dir ["c.txt"])   ; staged add
      (spit-file dir "a.txt" "changed\n")                        ; not-staged modified
      (spit-file dir "u.txt" "zzz\n")                            ; untracked
      (let [s (repo/status dir)]
        (is (= ["c.txt"] (:added (:staged s))))
        (is (= ["a.txt"] (:modified (:not-staged s))))
        (is (= ["u.txt"] (:untracked s)))))))

(deftest add-stages-deletion
  (testing "add が削除をステージ"
    (let [dir (base-repo)]
      (.delete (io/file dir "b.txt"))
      (repo/add dir ["b.txt"])
      (is (= ["b.txt"] (:deleted (:staged (repo/status dir))))))))

(deftest rm-cached-drops-index
  (testing "rm-cached で index から落ち、staged削除+untracked になる"
    (let [dir (base-repo)]
      (repo/rm-cached dir "b.txt")
      (let [s (repo/status dir)]
        (is (= ["b.txt"] (:deleted (:staged s))))
        (is (= ["b.txt"] (:untracked s)))))))

(deftest restore-staged-resets
  (testing "restore-staged: modified を unstage / 新規add を untracked に戻す"
    (let [dir (base-repo)]
      (spit-file dir "a.txt" "changed\n") (repo/add dir ["a.txt"])
      (is (= ["a.txt"] (:modified (:staged (repo/status dir)))))
      (repo/restore-staged dir "a.txt")
      (let [s (repo/status dir)]
        (is (= [] (:modified (:staged s))))
        (is (= ["a.txt"] (:modified (:not-staged s)))))
      (spit-file dir "n.txt" "new\n") (repo/add dir ["n.txt"])
      (repo/restore-staged dir "n.txt")
      (is (some #{"n.txt"} (:untracked (repo/status dir)))))))
