(ns my-git.test-util
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io File]))

(defn temp-git-dir
  "一時ディレクトリに git init し、{:root :git-dir} を返す。"
  []
  (let [d (File/createTempFile "mygit" "")]
    (.delete d) (.mkdir d)
    (sh/sh "git" "init" "-q" :dir d)
    {:root d :git-dir (io/file d ".git")}))

(defn spit-file [root path content]
  (let [f (io/file root path)]
    (io/make-parents f)
    (spit f content)))
