(ns my-git.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.repo :as repo]
            [my-git.ref :as ref]
            [my-git.log :as glog]
            [my-git.test-util :refer [ident spit-file temp-dir git-env]]))

(defn- make-3-commit-repo
  "自作 init→add→commit で3コミットの直線履歴を作る。"
  []
  (let [dir (temp-dir)]
    (repo/init dir)
    (spit-file dir "a.txt" "hello\n")
    (repo/add dir ["a.txt"])
    (repo/commit dir {:author ident :message "first commit"})
    (spit-file dir "b.txt" "world\n")
    (repo/add dir ["b.txt"])
    (repo/commit dir {:author (assoc ident :timestamp 1700000100) :message "second commit"})
    (spit-file dir "a.txt" "hello again\n")
    (repo/add dir ["a.txt"])
    (repo/commit dir {:author (assoc ident :timestamp 1700000200) :message "third commit"})
    dir))

(deftest commit-seq-follows-parents
  (testing "commit-seq が parent 鎖を git rev-list HEAD と同じ順序・hash で辿る"
    (let [dir     (make-3-commit-repo)
          git-dir (io/file dir ".git")
          ours    (map :hash (glog/commit-seq git-dir (ref/resolve-ref git-dir "HEAD")))
          oracle  (remove str/blank?
                          (str/split-lines (:out (sh/sh "git" "rev-list" "HEAD" :dir dir))))]
      (is (= oracle ours)))))

(deftest commit-seq-take-limits
  (testing "遅延と件数制限が効く"
    (let [dir     (make-3-commit-repo)
          git-dir (io/file dir ".git")
          head    (ref/resolve-ref git-dir "HEAD")]
      (is (= 1 (count (take 1 (glog/commit-seq git-dir head)))))
      (is (= 3 (count (glog/commit-seq git-dir head)))))))

(deftest log-orders-newest-first
  (testing "log は hash とメッセージを新しい順に含む"
    (let [dir     (make-3-commit-repo)
          git-dir (io/file dir ".git")
          out     (glog/log git-dir (ref/resolve-ref git-dir "HEAD"))]
      (is (str/includes? out "third commit"))
      (is (str/includes? out "first commit"))
      (is (< (str/index-of out "third commit")
             (str/index-of out "second commit")
             (str/index-of out "first commit"))))))

(deftest diff-trees-matches-git
  (testing "diff-trees が git diff --name-status と同じ A/D/M を返す"
    (let [dir     (temp-dir)
          git-dir (io/file dir ".git")
          env     (git-env)]
      (sh/sh "git" "init" "-q" :dir dir)
      (spit-file dir "a.txt" "hello\n")
      (spit-file dir "b.txt" "world\n")
      (spit-file dir "src/core.clj" "(ns core)\n")
      (sh/sh "git" "add" "-A" :dir dir)
      (sh/sh "git" "commit" "-q" "-m" "c1" :dir dir :env env)
      (let [t1 (str/trim (:out (sh/sh "git" "rev-parse" "HEAD^{tree}" :dir dir)))]
        (spit-file dir "a.txt" "hello again\n")
        (spit-file dir "c.txt" "new\n")
        (.delete (io/file dir "b.txt"))
        (sh/sh "git" "add" "-A" :dir dir)
        (sh/sh "git" "commit" "-q" "-m" "c2" :dir dir :env env)
        (let [t2 (str/trim (:out (sh/sh "git" "rev-parse" "HEAD^{tree}" :dir dir)))
              d  (glog/diff-trees git-dir t1 t2)]
          (is (= ["c.txt"] (:added d)))
          (is (= ["b.txt"] (:deleted d)))
          (is (= ["a.txt"] (:modified d))))))))