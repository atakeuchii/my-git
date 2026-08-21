(ns my-git.test-util
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io File]))

(def ^:private created-dirs (atom []))
(def ident {:name "Aki" :email "aki@example.com" :timestamp 1700000000 :tz "+0900"})

(defn- delete-recursively [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (delete-recursively child)))
    (.delete f)))

(defn cleanup-fixture
  "各テスト後に、作られた temp-dir を全部再帰削除する。"
  [t]
  (try (t)
       (finally
         (doseq [d @created-dirs] (delete-recursively d))
         (reset! created-dirs []))))

(defn temp-dir []
  (let [d (File/createTempFile "mygit" "")]
    (.delete d) (.mkdir d)
    (swap! created-dirs conj d)
    d))

(defn temp-git-dir
  "一時ディレクトリに git init し、{:root :git-dir} を返す。"
  []
  (let [d (temp-dir)]
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
