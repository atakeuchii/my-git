(ns my-git.repo-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.repo :as repo]
            [my-git.commit :as commit]
            [my-git.test-util :refer [ident spit-file temp-dir git-env]]))

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