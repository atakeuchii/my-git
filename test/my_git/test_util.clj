(ns my-git.test-util
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io File]))

(def ident {:name "Aki" :email "aki@example.com" :timestamp 1700000000 :tz "+0900"})

(defn temp-dir []
  (let [d (java.io.File/createTempFile "mygit" "")]
    (.delete d) (.mkdir d) d))

(defn temp-git-dir
  "一時ディレクトリに git init し、{:root :git-dir} を返す。"
  []
  (let [d (File/createTempFile "mygit" "")]
    (.delete d) (.mkdir d)
    (sh/sh "git" "init" "-q" :dir d)
    {:root d :git-dir (io/file d ".git")}))

(defn git-env []
  (merge (into {} (System/getenv))
         {"GIT_AUTHOR_NAME" (:name ident)    "GIT_AUTHOR_EMAIL" (:email ident)
          "GIT_COMMITTER_NAME" (:name ident) "GIT_COMMITTER_EMAIL" (:email ident)
          "GIT_AUTHOR_DATE"    "1700000000 +0900"
          "GIT_COMMITTER_DATE" "1700000000 +0900"}))

(defn spit-file [root path content]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f content)))
