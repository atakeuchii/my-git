(ns my-git.tree
  (:require [my-git.object :as obj])
  (:import [java.io ByteArrayOutputStream]))

(defn- hex->bytes
  "40文字の16進文字列を 20バイトの byte 配列に変換する。"
  ^bytes [^String hex]
  (let [n (quot (count hex) 2)
        out (byte-array n)]
    (dotimes [i n]
      (let [b (Integer/parseInt (subs hex (* 2 i) (+ 2 (* 2 i))) 16)]
        (aset out i (unchecked-byte b))))
    out))

(defn- bytes->hex
  ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- index-of-byte
  ^long [^bytes bs ^long from ^long target]
  (loop [i from]
    (cond
      (>= i (alength bs)) -1
      (= (bit-and (aget bs i) 0xff) target) i
      :else (recur (inc i)))))

(defn- tree-sort-key
  "git のツリー整列キー。ディレクトリ(mode 40000)は名前末尾に / を足して比較する。"
  [{:keys [mode name]}]
  (if (= mode "40000") (str name "/") name))

(defn write-tree
  "entries を tree オブジェクトとして git-dir に保存し、hash を返す。
   entry = {:mode \"100644\"|\"40000\", :name \"a.txt\", :hash <40文字16進>}"
  ^String [git-dir entries]
  (let [sorted (sort-by tree-sort-key entries)
        baos (ByteArrayOutputStream.)]
    (doseq [{:keys [mode name hash]} sorted]
      (.write baos (.getBytes (str mode " " name) "UTF-8"))
      (.write baos 0)
      (.write baos (hex->bytes hash)))
    (obj/write-object git-dir "tree" (.toByteArray baos))))

(defn read-tree
  "git-dir から tree オブジェクトを読み、エントリ列 [{:mode :name :hash} ...] を返す。
   write-tree の逆。生20バイトSHAを40文字16進に戻す。"
  [git-dir ^String hash]
  (let [{:keys [type ^bytes content]} (obj/read-object git-dir hash)]
    (when-not (= type "tree")
      (throw (ex-info "not a tree object" {:type type :hash hash})))
    (loop [i 0
           acc []]
      (if (>= i (alength content))
        acc
        (let [sp (index-of-byte content i (int \space))
              mode (String. content i (- sp i) "UTF-8")
              nul (index-of-byte content (inc sp) 0)
              name (String. content (inc sp) (- nul (inc sp)) "UTF-8")
              sha-start (inc nul)
              sha (java.util.Arrays/copyOfRange content sha-start (+ sha-start 20))]
          (recur (+ sha-start 20)
                 (conj acc {:mode mode
                            :name name
                            :hash (bytes->hex sha)})))))))

(defn read-tree-recursive
  [git-dir ^String hash]
  (letfn [(walk [prefix h]
                (mapcat (fn [{:keys [mode name hash]}]
                          (let [path (if (empty? prefix) 
                                       name
                                       (str prefix "/" name))]
                            (if (= mode "40000")
                              (walk path hash)
                              [[path hash]])))
                        (read-tree git-dir h)))]
    (walk "" hash)))
