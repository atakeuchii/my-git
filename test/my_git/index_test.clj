(ns my-git.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [my-git.index :as index]
            [my-git.test-util :refer [temp-git-dir spit-file]])
  (:import [java.nio.file Files]))

(def a-sha "ce013625030ba8dba906f756967f9e9ca394464a")   ; "hello\n"
(def b-sha "cc628ccd10742baea8241c5924df992b5c019f71")   ; "world\n"

(deftest git-can-read-our-index
  (testing "自作 index を本物 git が読める"
  (let [{:keys [root git-dir]} (temp-git-dir)]
    (index/write-index git-dir
                       [{:mode "100644" :sha a-sha :path "a.txt"}
                        {:mode "100644" :sha b-sha :path "bb.txt"}])
    (let [out (:out (sh/sh "git" "ls-files" "--stage" :dir root))]
      (is (str/includes? out (str "100644 " a-sha " 0\ta.txt")))
      (is (str/includes? out (str "100644 " b-sha " 0\tbb.txt")))))))

(deftest we-can-read-git-index
  (testing "本物 git が git add で作った index を自作が読める"
    (let [{:keys [root git-dir]} (temp-git-dir)]
      (spit-file root "a.txt"  "hello\n")
      (spit-file root "bb.txt" "world\n")
      (sh/sh "git" "add" "a.txt" "bb.txt" :dir root)
      (let [ours     (index/read-index git-dir)
            expected (->> (str/split-lines (:out (sh/sh "git" "ls-files" "--stage" :dir root)))
                          (remove str/blank?)
                          (mapv (fn [line]
                                  (let [[meta path]        (str/split line #"\t")
                                        [mode sha _stage]  (str/split meta #" ")]
                                    {:mode mode :sha sha :path path}))))]
        (is (= expected ours))))))

(deftest round-trip
  (testing "往復テスト"
    (let [{:keys [git-dir]} (temp-git-dir)
          entries [{:mode "100644" :sha a-sha :path "a.txt"}
                   {:mode "100644" :sha b-sha :path "bb.txt"}
                   {:mode "100644" :sha a-sha :path "0123456789"}]]
      (index/write-index git-dir entries)
      (is (= (sort-by :path entries) (index/read-index git-dir))))))

(deftest checksum-mismatch-throws
  (testing "チェックサムが壊れていたら read-indexが落ちる"
  (let [{:keys [git-dir]} (temp-git-dir)]
    (index/write-index git-dir [{:mode "100644" :sha a-sha :path "a.txt"}])
    (let [f   (io/file git-dir "index")
          raw (Files/readAllBytes (.toPath f))]
      (aset-byte raw 20 (unchecked-byte (bit-xor (aget raw 20) 0xff)))  ; body の1バイトを反転
      (with-open [o (io/output-stream f)] (.write o raw)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum"
                          (index/read-index git-dir))))))
