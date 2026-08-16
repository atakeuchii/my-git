(ns my-git.commit
  (:require [clojure.string :as str]
            [my-git.object :as obj]))

(defn- ident-line
  [{:keys [name email timestamp tz]}]
  (str name " <" email "> " timestamp " " tz))

(defn- parse-ident
  [s]
  (let [[_ name email ts tz] (re-matches #"(.*) <(.*)> (\d+) (\S+)" s)]
    {:name name :email email :timestamp (Long/parseLong ts) :tz tz}))

(defn write-commit
  ^String [git-dir {:keys [tree parents author committer message]}]
  (let [committer (or committer author)
        sb (StringBuilder.)]
    (.append sb (str "tree " tree "\n"))
    (doseq [p parents]
      (.append sb (str "parent " p "\n")))
    (.append sb (str "author " (ident-line author) "\n"))
    (.append sb (str "committer " (ident-line committer) "\n"))
    (.append sb "\n")
    (.append sb message)
    (when-not (.endsWith ^String message "\n")
      (.append sb "\n"))
    (obj/write-object git-dir "commit" (.getBytes (.toString sb) "UTF-8"))))

(defn read-commit
  [git-dir ^String hash]
  (let [{:keys [type ^bytes content]} (obj/read-object git-dir hash)]
    (when-not (= type "commit")
      (throw (ex-info "not a commit object" {:type type :hash hash})))
    (let [s (String. content "UTF-8")
          idx (.indexOf s "\n\n")
          header (subs s 0 idx)
          message (subs s (+ idx 2))]
      (when (neg? idx)
        (throw (ex-info "commit body has no blank-line separator"
                        {:hash hash :body s})))
      (reduce (fn [m line]
                (let [sp (.indexOf ^String line " ")
                      k (subs line 0 sp)
                      v (subs line (inc sp))]
                  (case k
                    "tree" (assoc m :tree v)
                    "parent" (update m :parents conj v)
                    "author" (assoc m :author (parse-ident v))
                    "committer" (assoc m :committer (parse-ident v))
                    m)))
              {:parents [] :message message}
              (str/split header #"\n")))))

