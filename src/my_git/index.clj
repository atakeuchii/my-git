(ns my-git.index
  (:require [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.nio ByteBuffer]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util Arrays]))

(defn- hex->bytes
  ^bytes [^String hex]
  (let [n (quot (count hex) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset-byte out i
                 (unchecked-byte (Integer/parseInt (subs hex (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(defn- bytes->hex
  ^String [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- write-entry!
  [^DataOutputStream dos {:keys [mode sha path]}]
  (let [name-bytes (.getBytes ^String path "UTF-8")
        namelen (alength name-bytes)
        start (.size dos)]
    (dotimes [_ 6] (.writeInt dos 0))
    (.writeInt dos (Integer/parseInt mode 8))
    (dotimes [_ 3] (.writeInt dos 0))
    (.write dos (hex->bytes sha) 0 20)
    (.writeShort dos (min namelen 0xFFF))
    (.write dos name-bytes 0 namelen)
    (let [pad (- 8 (mod (- (.size dos) start) 8))]
      (dotimes [_ pad] (.writeByte dos 0)))))

(defn write-index
  [git-dir entries]
  (let [sorted (sort-by :path entries)
        baos (ByteArrayOutputStream.)
        dos (DataOutputStream. baos)]
    (.writeBytes dos "DIRC")
    (.writeInt dos 2)
    (.writeInt dos (count sorted))
    (doseq [e sorted] (write-entry! dos e))
    (.flush dos)
    (let [body (.toByteArray baos)
          digest (.digest (MessageDigest/getInstance "SHA-1") body)
          f (io/file git-dir "index")]
      (with-open [out (io/output-stream f)]
        (.write out body)
        (.write out digest 0 20))
      f)))

(defn read-index
  [git-dir]
  (let [f (io/file git-dir "index")
        raw (Files/readAllBytes (.toPath f))
        n (alength raw)]
    (let [body (Arrays/copyOfRange raw 0 (- n 20))
          stored (Arrays/copyOfRange raw (- n 20) n)
          calc (.digest (MessageDigest/getInstance "SHA-1") body)]
      (when-not (Arrays/equals stored calc)
        (throw (ex-info "index checksum mismatch" {:file (str f)}))))
    (let [buf (ByteBuffer/wrap raw)
          magic (let [b (byte-array 4)]
                  (.get buf b)
                  (String. b "UTF-8"))
          _ (when-not (= magic "DIRC")
              (throw (ex-info "not a git index" {:magic magic})))
          _ver (.getInt buf)
          cnt (.getInt buf)]
      (loop [i 0
             acc []]
        (if (>= i cnt)
          acc
          (let [start (.position buf)]
            (dotimes [_ 6] (.getInt buf))
            (let [mode (.getInt buf)
                  _ (dotimes [_ 3] (.getInt buf))
                  sha (let [b (byte-array 20)] (.get buf b) b)
                  flags (bit-and (.getShort buf) 0xFFFF)
                  namelen (bit-and flags 0x0FFF)
                  name (let [b (byte-array namelen)]
                         (.get buf b)
                         (String. b "UTF-8"))
                  pad (- 8 (mod (- (.position buf) start) 8))]
              (.position buf (+ (.position buf) pad))
              (recur (inc i)
                     (conj acc {:mode (Integer/toString mode 8)
                                :sha (bytes->hex sha)
                                :path name})))))))))
