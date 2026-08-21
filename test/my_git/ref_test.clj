(ns my-git.ref-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.object :as obj]
            [my-git.tree :as tree]
            [my-git.commit :as commit]
            [my-git.ref :as ref]
            [my-git.test-util :refer [temp-git-dir cleanup-fixture]]))

(use-fixtures :each cleanup-fixture)

(def ident {:name "Aki" :email "aki@example.com" :timestamp 1700000000 :tz "+0900"})

(deftest update-ref-and-resolve
  (testing "update-ref → resolve-ref で HEAD→ref→commit を辿れる"
  (let [{:keys [git-dir]} (temp-git-dir)
        h "d3f12a3d920f3ca3036f1aa7538c8e9b5f83f67a"]
    (ref/set-symbolic-ref git-dir "HEAD" "refs/heads/main")
    (ref/update-ref git-dir "refs/heads/main" h)
    (is (= "ref: refs/heads/main" (ref/read-ref git-dir "HEAD")))
    (is (= h (ref/resolve-ref git-dir "HEAD"))))))

(deftest unborn-returns-nil
  (testing "commit がまだ無いブランチ(unborn)は nil"
  (let [{:keys [git-dir]} (temp-git-dir)]
    (ref/set-symbolic-ref git-dir "HEAD" "refs/heads/main")
    (is (nil? (ref/resolve-ref git-dir "HEAD"))))))

(deftest detached-head
  (testing "HEAD が直接 hash を指す"
    (let [{:keys [git-dir]} (temp-git-dir)
          h "d3f12a3d920f3ca3036f1aa7538c8e9b5f83f67a"]
      (ref/update-ref git-dir "HEAD" h)
      (is (= h (ref/resolve-ref git-dir "HEAD"))))))

(deftest git-recognizes-branch
  (testing "自作で書いた ref を本物 git がブランチとして認識する"
    (let [{:keys [root git-dir]} (temp-git-dir)
          a  (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
          th (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}])
          c1 (commit/write-commit git-dir {:tree th :parents []
                                           :author ident :message "first commit"})]
      (ref/set-symbolic-ref git-dir "HEAD" "refs/heads/main")
      (ref/update-ref git-dir "refs/heads/main" c1)
      (is (= c1 (str/trim (:out (sh/sh "git" "rev-parse" "HEAD" :dir root)))))
      (is (str/includes? (:out (sh/sh "git" "branch" :dir root)) "main")))))
