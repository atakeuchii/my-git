(ns my-git.object
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util.zip DeflaterOutputStream InflaterInputStream]
           [java.io ByteArrayOutputStream File]))

(defn sha1-hex
  ^String [^bytes data]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1") data)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn object-bytes
  ^bytes [^String type ^bytes content]
  (let [header (.getBytes (str type " " (alength content)) "UTF-8")
        baos (ByteArrayOutputStream.)]
    (.write baos header)
    (.write baos 0)
    (.write baos content)
    (.toByteArray baos)))

(defn object-hash
  ^String [^String type ^bytes content]
  (sha1-hex (object-bytes type content)))

(defn- object-path
  ^File [git-dir ^String hash]
  (io/file git-dir "objects" (subs hash 0 2) (subs hash 2)))

(defn zlib-compress
  ^bytes [^bytes data]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [dos (DeflaterOutputStream. baos)]
      (.write dos data))
    (.toByteArray baos)))

(defn write-object
  ^String [git-dir ^String type ^bytes content]
  (let [bytes (object-bytes type content)
        hash (sha1-hex bytes)
        file (object-path git-dir hash)]
    (io/make-parents file)
    (with-open [out (io/output-stream file)]
      (.write out (zlib-compress bytes)))
    hash))

(defn- index-of-zero
  [^bytes bs]
  (loop [i 0]
    (cond
      (>= i (alength bs)) -1
      (zero? (aget bs i)) i
      :else (recur (inc i)))))

(defn read-object
  [git-dir ^String hash]
  (let [file (object-path git-dir hash)
        raw (with-open [in (InflaterInputStream. (io/input-stream file))
                        out (ByteArrayOutputStream.)]
              (io/copy in out)
              (.toByteArray out))
        nul (index-of-zero raw)
        header (String. raw 0 nul "UTF-8")
        [type size] (str/split header #" ")
        content (java.util.Arrays/copyOfRange raw (inc nul) (alength raw))]
    {:type type
     :size (Integer/parseInt size)
     :content content}))
