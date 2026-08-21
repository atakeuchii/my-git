(ns my-git.commit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.object :as obj]
            [my-git.tree :as tree]
            [my-git.commit :as commit]
            [my-git.test-util :refer [temp-git-dir spit-file cleanup-fixture]]))

(use-fixtures :each cleanup-fixture)

(def ident {:name "Aki" :email "aki@example.com" :timestamp 1700000000 :tz "+0900"})

(defn- git-env
  "現在の環境に author/committer と日付を上書きした env マップ。commit hash を固定する。"
  []
  (merge (into {} (System/getenv))
         {"GIT_AUTHOR_NAME" (:name ident)      "GIT_AUTHOR_EMAIL" (:email ident)
          "GIT_COMMITTER_NAME" (:name ident)   "GIT_COMMITTER_EMAIL" (:email ident)
          "GIT_AUTHOR_DATE"    "1700000000 +0900"
          "GIT_COMMITTER_DATE" "1700000000 +0900"}))

(deftest commit-hash-matches-git
  (testing "commit hash が本物 git の commit-tree と一致"
  (let [{:keys [root git-dir]} (temp-git-dir)]
    (spit-file root "a.txt" "hello\n")
    (sh/sh "git" "add" "-A" :dir root)
    (let [tree   (str/trim (:out (sh/sh "git" "write-tree" :dir root)))
          oracle (str/trim (:out (sh/sh "git" "commit-tree" tree "-m" "first commit"
                                        :dir root :env (git-env))))
          a    (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
          th   (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}])
          ours (commit/write-commit git-dir {:tree th :parents []
                                             :author ident :message "first commit"})]
      (is (= tree th) "tree hash が git と一致")
      (is (= oracle ours) "commit hash が git と一致")))))

(deftest git-can-read-our-commit
  (testing "自作 commit を本物 git が読める"
  (let [{:keys [root git-dir]} (temp-git-dir)
        a  (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
        th (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}])
        c1 (commit/write-commit git-dir {:tree th :parents []
                                         :author ident :message "first commit"})]
    (is (= "commit" (str/trim (:out (sh/sh "git" "cat-file" "-t" c1 :dir root)))))
    (is (str/includes? (:out (sh/sh "git" "cat-file" "-p" c1 :dir root)) "first commit")))))

(deftest read-commit-large-message
  (testing "長い複数行メッセージでも全部読み切れる"
  (let [{:keys [git-dir]} (temp-git-dir)
        msg (str/join "\n" (repeat 50 "commit message line with several words"))
        a   (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
        th  (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}])
        h   (commit/write-commit git-dir {:tree th :parents []
                                          :author ident :message msg})
        m   (commit/read-commit git-dir h)]
    (is (= th (:tree m))          ":tree が欠落しない")
    (is (= (str msg "\n") (:message m)) "メッセージ全体を読み切る")
    (is (= h (commit/write-commit git-dir m)) "read→write で hash が保存される"))))

(deftest parent-chain
  (testing "c2 の parent に c1 が入る"
    (let [{:keys [git-dir]} (temp-git-dir)
          a  (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
          b  (obj/write-object git-dir "blob" (.getBytes "world\n" "UTF-8"))
          t1 (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}])
          c1 (commit/write-commit git-dir {:tree t1 :parents [] :author ident :message "first"})
          t2 (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}
                                       {:mode "100644" :name "b.txt" :hash b}])
          c2 (commit/write-commit git-dir {:tree t2 :parents [c1] :author ident :message "second"})]
      (is (= [c1] (:parents (commit/read-commit git-dir c2)))))))
