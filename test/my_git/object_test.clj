(ns my-git.object-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.object :as obj])
  (:import [java.io File]))

(defn- temp-git-dir
  "一時ディレクトリに git init し、{:root :git-dir} を返す。"
  []
  (let [d (File/createTempFile "mygit" "")]
    (.delete d) (.mkdir d)
    (sh/sh "git" "init" "-q" :dir d)
    {:root d :git-dir (io/file d ".git")}))

(defn- git-hash-object
  "本物 git に content を hash-object させて hash を返す（参照オラクル、repo不要）。"
  [^bytes content]
  (let [f (File/createTempFile "oracle" "")]
    (.deleteOnExit f)
    (with-open [o (io/output-stream f)] (.write o content))
    (str/trim (:out (sh/sh "git" "hash-object" (.getPath f))))))

(deftest hash-matches-git
  (testing "hash が本物 git と一致する"
    (doseq [s ["hello\n" "こんにちは\n" "" "line1\nline2\n"]]
      (let [content (.getBytes s "UTF-8")]
        (is (= (git-hash-object content)
               (obj/object-hash "blob" content))
            (str "content=" (pr-str s)))))))

(deftest git-can-read-our-object
  (testing "自作が書いたオブジェクトを本物 git が読める"
    (let [{:keys [root git-dir]} (temp-git-dir)
          hash (obj/write-object git-dir "blob" (.getBytes "hello\n" "UTF-8"))]
      (is (= "blob"   (str/trim (:out (sh/sh "git" "cat-file" "-t" hash :dir root)))))
      (is (= "hello\n" (:out (sh/sh "git" "cat-file" "-p" hash :dir root)))))))

(deftest we-can-read-git-object
  (testing "本物 git が書いたオブジェクトを自作が読める"
  (let [{:keys [root git-dir]} (temp-git-dir)
        f    (java.io.File/createTempFile "src" "" root)
        _    (spit f "こんにちは\n")
        hash (str/trim (:out (sh/sh "git" "hash-object" "-w" (.getPath f) :dir root)))
        {:keys [type content]} (obj/read-object git-dir hash)]
    (is (= "blob" type))
    (is (= "こんにちは\n" (String. content "UTF-8"))))))

(deftest round-trip
  (testing "往復（バイナリ・空・埋め込みヌルも保持される）"
  (let [{:keys [git-dir]} (temp-git-dir)]
    (doseq [s ["hello\n" "こんにちは\n" "" "binary\u0000data"]]
      (let [content (.getBytes s "UTF-8")
            hash    (obj/write-object git-dir "blob" content)
            {c :content} (obj/read-object git-dir hash)]
        (is (= (seq content) (seq c)) (str "s=" (pr-str s))))))))

;; プロパティ：ランダムな文字列で hash が常に本物 git と一致する
(defspec hash-equivalent-to-git 50
  (prop/for-all [s gen/string]
                (let [content (.getBytes s "UTF-8")]
                  (= (git-hash-object content)
                     (obj/object-hash "blob" content)))))
