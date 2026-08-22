(ns my-git.merge-test
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.test-util :as test-util]
            [my-git.scenario :as scn]
            [my-git.merge :as mg]
            [my-git.repo :as repo]
            [my-git.commit :as commit]))

(use-fixtures :each test-util/cleanup-fixture)
(def ident {:name "Aki" :email "aki@example.com" :timestamp 1700000300 :tz "+0900"})

(defn- git-tree-paths [dir tree]
  (into (sorted-map)
        (for [line (str/split-lines (:out (sh/sh "git" "-C" dir "ls-tree" "-r" tree)))
              :when (not (str/blank? line))]
          (let [[meta path] (str/split line #"\t") [_ _ sha] (str/split meta #" ")] [path sha]))))

(defn- git-env []
  (merge (into {} (System/getenv))
         {"GIT_AUTHOR_NAME" "o" "GIT_AUTHOR_EMAIL" "o@e.com"
          "GIT_COMMITTER_NAME" "o" "GIT_COMMITTER_EMAIL" "o@e.com"}))

;; 本物 git で main に feature をマージした結果 tree（バージョン非依存オラクル）
(defn- git-merge-tree [dir]
  (sh/sh "git" "-C" dir "merge" "--no-edit" "feature" :env (git-env))
  (str/trim (:out (sh/sh "git" "-C" dir "rev-parse" "HEAD^{tree}"))))

(deftest merge-base-matches-git
  (testing "merge-base が git merge-base と一致"
    (let [r (scn/make-merge-scenario (str (test-util/temp-dir)))]
      (is (= (str/trim (:out (sh/sh "git" "-C" (:dir r) "merge-base" "main" "feature")))
             (mg/merge-base (:git-dir r) (:main r) (:feature r))))
      (is (= (:base r) (mg/merge-base (:git-dir r) (:main r) (:feature r)))))))

(deftest merge-trees-clean
  (testing "衝突なし: :merged が git merge-tree の結果と一致・conflicts 空"
    (let [r (scn/make-merge-scenario (str (test-util/temp-dir)))
          {:keys [merged conflicts]} (mg/merge-trees (:git-dir r) (:base r) (:main r) (:feature r))
          ours (into (sorted-map) (map (fn [[k v]] [k (:sha v)]) merged))
          ;; gt (str/trim (:out (sh/sh "git" "-C" (:dir r) "merge-tree" "--write-tree" "main" "feature")))
          ]
      (is (empty? conflicts))
      (is (= (git-tree-paths (:dir r) (git-merge-tree (:dir r))) ours)))))

(deftest merge-trees-conflict
  (testing "衝突検出"
    (let [r (scn/make-conflict-scenario (str (test-util/temp-dir)))]
      (is (= ["a.txt"] (:conflicts (mg/merge-trees (:git-dir r) (:base r) (:main r) (:feature r))))))))

(deftest merge-branch-3way
  (let [r (scn/make-merge-scenario (str (test-util/temp-dir)))
        res (repo/merge-branch (:dir r) "feature" {:author ident})
        ;; 自作 merge-branch は main を進めてしまうので、別の新規 scenario で git にオラクルを作らせる
        r2 (scn/make-merge-scenario (str (test-util/temp-dir)))
        oracle-tree (git-merge-tree (:dir r2))]
    (is (= :merged (:status res)))
    (is (= [(:main r) (:feature r)] (:parents (commit/read-commit (:git-dir r) (:commit res)))))
    (is (= oracle-tree (:tree (commit/read-commit (:git-dir r) (:commit res)))))
    (is (str/includes? (:out (sh/sh "git" "-C" (:dir r) "status")) "working tree clean"))))

(deftest merge-branch-ff
  (testing "fast-forward"
    (let [r (scn/make-ff-scenario (str (test-util/temp-dir)))
          res (repo/merge-branch (:dir r) "feature" {:author ident})]
      (is (= :fast-forward (:status res)))
      (is (= (:feature r) (:commit res)))
      (is (.exists (io/file (:dir r) "f.txt"))))))

(deftest merge-branch-conflict
  (testing "conflict"
    (let [r (scn/make-conflict-scenario (str (test-util/temp-dir)))]
      (is (= :conflict (:status (repo/merge-branch (:dir r) "feature" {:author ident})))))))
