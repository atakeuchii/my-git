(ns my-git.ref
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

(defn- ref-file
  ^File [git-dir ref-name]
  (io/file git-dir ref-name))

(defn update-ref
  [git-dir ref-name ^String hash]
  (let [f (ref-file git-dir ref-name)]
    (io/make-parents f)
    (spit f (str hash "\n")))
  hash)

(defn read-ref
  [git-dir ref-name]
  (let [f (ref-file git-dir ref-name)]
    (when (.exists f)
      (str/trim (slurp f)))))

(defn set-symbolic-ref
  [git-dir ref-name target]
  (let [f (ref-file git-dir ref-name)]
    (io/make-parents f)
    (spit f (str "ref: " target "\n")))
  target)

(defn resolve-ref
  [git-dir ref-name]
  (when-let [v (read-ref git-dir ref-name)]
    (if (str/starts-with? v "ref: ")
      (recur git-dir (subs v 5))
      v)))
