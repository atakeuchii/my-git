(ns my-git.tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.object :as obj]
            [my-git.tree :as tree]
            [my-git.test-util :refer [temp-git-dir]]))

;; (defn- temp-repo []
;;   (let [d (java.io.File/createTempFile "mygit" "")]
;;     (.delete d) (.mkdir d)
;;     (sh/sh "git" "init" "-q" :dir d)
;;     {:root d :git-dir (io/file d ".git")}))

(defn- spit-file [root path content]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f content)))

(deftest root-hash-matches-git
  (testing "ネスト tree の hash が本物 git の write-tree と一致する"
    (let [{:keys [root git-dir]} (temp-git-dir)]
      (spit-file root "a.txt" "hello\n")
      (spit-file root "b.txt" "world\n")
      (spit-file root "sub/c.txt" "foo\n")
      (sh/sh "git" "add" "-A" :dir root)
      (let [oracle (str/trim (:out (sh/sh "git" "write-tree" :dir root)))
            a (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
            b (obj/write-object git-dir "blob" (.getBytes "world\n" "UTF-8"))
            c (obj/write-object git-dir "blob" (.getBytes "foo\n" "UTF-8"))
            subh (tree/write-tree git-dir [{:mode "100644" :name "c.txt" :hash c}])
            ours (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}
                                           {:mode "100644" :name "b.txt" :hash b}
                                           {:mode "40000"  :name "sub" :hash subh}])]
        (is (= oracle ours))))))

(deftest git-read-our-tree
  (testing "自作で書いたネスト tree を本物 git が展開できる"
    (let [{:keys [root git-dir]} (temp-git-dir)
          c (obj/write-object git-dir "blob" (.getBytes "foo\n" "UTF-8"))
          subh (tree/write-tree git-dir [{:mode "100644" :name "c.txt" :hash c}])
          root-hash (tree/write-tree git-dir [{:mode "40000" :name "sub" :hash subh}])
          out  (:out (sh/sh "git" "ls-tree" "-r" root-hash :dir root))]
      (is (str/includes? out "sub/c.txt"))
      (is (str/includes? out c)))))

(deftest read-git-tree
  (testing "本物 git が書いた tree を自作が再帰で読める"
    (let [{:keys [root git-dir]} (temp-git-dir)]
      (spit-file root "a.txt" "hello\n")
      (spit-file root "sub/c.txt" "foo\n")
      (sh/sh "git" "add" "-A" :dir root)
      (let [oracle (str/trim (:out (sh/sh "git" "write-tree" :dir root)))
            paths  (into {} (tree/read-tree-recursive git-dir oracle))]
        (is (= "hello\n"
               (String. ^bytes (:content (obj/read-object git-dir (paths "a.txt"))) "UTF-8")))
        (is (contains? paths "sub/c.txt"))))))

(deftest round-trip
  (testing "write-tree → read-tree でエントリが戻る"
    (let [{:keys [git-dir]} (temp-git-dir)
          a  (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))
          b  (obj/write-object git-dir "blob" (.getBytes "world\n" "UTF-8"))
          h  (tree/write-tree git-dir [{:mode "100644" :name "a.txt" :hash a}
                                       {:mode "100644" :name "b.txt" :hash b}])]
      (is (= [{:mode "100644" :name "a.txt" :hash a}
              {:mode "100644" :name "b.txt" :hash b}]
             (tree/read-tree git-dir h))))))
