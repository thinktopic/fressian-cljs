(ns fressian-cljs.writer
  (:use [fressian-cljs.defs :only [codes ranges tag-to-code TaggedObject
                                   create-interleaved-index-hop-map old-index]]
        [fressian-cljs.fns :only [read-utf8-chars expected lookup
                                  buffer-string-chunk-utf8 ]])
  (:require [goog.string :as gstring]
            [goog.string.format]))

(defrecord FressianWriter [buffer index handlers])

(declare write-object write-tag write-int)

(def priority-cache (create-interleaved-index-hop-map 16))
(def struct-cache   (create-interleaved-index-hop-map 16))

(defn notify-bytes-written [wtr cnt]
  (swap! wtr update-in [:index] + cnt))

(defn write-raw-byte [wtr b]
  (aset (js/Int8Array. (:buffer @wtr)) (:index @wtr) b)
  (notify-bytes-written wtr 1)
  wtr)

(defn write-raw-bytes [wtr b off len]
  (let [i8array (js/Int8Array. (:buffer @wtr))]
    (.set i8array (.subarray b off (+ off len)) (:index @wtr))
    (notify-bytes-written wtr len)
    wtr))

(defn write-code [wtr code]
  (write-raw-byte wtr code))

(defn- write-tag [wtr tag component-count]
  (if-let [shortcut-code (tag-to-code tag)]
    (write-code wtr shortcut-code)
    (let [index (old-index struct-cache tag)]
      (cond
        (= index -1) (do (write-code wtr (codes :structtype))
                         (write-object wtr tag)
                         (write-int wtr component-count))
        (< index (ranges :struct-cache-packed-end))
        (write-code wtr (+ (codes :struct-cache-packed-start) index))

        :default (do (write-code wtr (codes :struct))
                     (write-int wtr index)))))
  wtr)

(defn- write-named [tag wtr s]
  (write-tag wtr tag 2)
  (write-object wtr (namespace s) true)
  (write-object wtr (name s) true))

(defn write-raw-int16 [wtr s]
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right s 8) 0xFF))
  (write-raw-byte wtr
    (bit-and s 0xFF)))

(defn write-raw-int24 [wtr i]
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 16) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 8) 0xFF))
  (write-raw-byte wtr
    (bit-and i 0xFF)))

(defn write-raw-int32 [wtr i]
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 24) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 16) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 8) 0xFF))
  (write-raw-byte wtr
    (bit-and i 0xFF)))

(defn write-raw-int40 [wtr i]
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 32) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 24) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 16) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 8) 0xFF))
  (write-raw-byte wtr
    (bit-and i 0xFF)))

(defn write-raw-int48 [wtr i]
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 40) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 32) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 24) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 16) 0xFF))
  (write-raw-byte wtr
    (bit-and (unsigned-bit-shift-right i 8) 0xFF))
  (write-raw-byte wtr
    (bit-and i 0xFF)))

(defn write-raw-int64 [wtr i]
  (doall
    (for [x (range 0 8)]
      (write-raw-byte wtr
        (unsigned-bit-shift-right i (* (- 7 x) 8)))))
  wtr)

(defn write-raw-float [wtr f]
  (let [f32array (js/Float32Array. 1)]
    (aset f32array 0 f)
    (let [bytes (js/Int8Array. (. f32array -buffer))]
      (doall
        (for [i (range 0 4)]
          (write-raw-byte wtr (aget bytes i))))))
  wtr)

(defn write-raw-double [wtr f]
  (let [f64array (js/Float64Array. 1)]
    (aset f64array 0 f)
    (let [bytes (js/Int8Array. (. f64array -buffer))]
      (dotimes [i 8]
          (write-raw-byte wtr (aget bytes i)))))
  wtr)

(defn bit-switch [l]
  (- 64 (.-length (.toString (if (< l 0) ~l l) 2))))

(defn write-int [wtr n]
  (cond (bit-switch n)
    (<= 1 14)  (do (write-code wtr (codes :int))
                 (write-raw-int64 wtr n))

    (<= 15 22) (do (write-raw-byte wtr (+ (codes :int-packed-7-zero)
                                         (/ n (.pow js/Math 2 48))))
                 (write-raw-int48 wtr n))

    (<= 23 30) (do (write-raw-byte wtr (+ (codes :int-packed-6-zero)
                                         (/ n (.pow js/Math 2 40))))
                 (write-raw-int40 wtr n))

    (<= 31 38) (do (write-raw-byte wtr (+ (codes :int-packed-5-zero)
                                         (/ n (.pow js/Math 2 32))))
                 (write-raw-int32 wtr n))n

    (<= 39 44) (do (write-raw-byte wtr (+ (codes :int-packed-4-zero)
                                         (bit-shift-right n 24)))
                 (write-raw-int24 wtr n))
    (<= 45 51) (do (write-raw-byte wtr (+ (codes :int-packed-3-zero)
                                         (bit-shift-right n 16)))
                 (write-raw-int16 wtr n))
    (<= 52 57) (do (write-raw-byte wtr (+ (codes :int-packed-2-zero)
                                         (bit-shift-right n 8)))
                 (write-raw-byte wtr n))
    (<= 58 64) (do (when (< n -1)
                     (write-raw-byte wtr (+ (codes :int-packed_2_zero)
                                           (bit-shift-right n 8))))
                 (write-raw-byte wtr n))
    :default (throw "more than 64 bits in a long!")))

(defn write-float [wtr f]
  (write-code wtr (codes :float))
  (write-raw-float wtr f))

(defn write-double [wtr d]
  (case d
    0.0 (write-code wtr (codes :doulbe-0))
    1.0 (write-code wtr (codes :double-1))
    (do (write-code wtr (codes :double))
        (write-raw-double wtr d))))

(defn write-count [wtr cnt]
  (write-int wtr cnt))

(defn write-string [wtr s]
  (let [max-buf-needed (min (* (count s) 3) 65536)
         string-buffer (js/Int8Array. (js/ArrayBuffer. max-buf-needed))]
    (loop [[string-pos buf-pos] (buffer-string-chunk-utf8 s 0 string-buffer)]
      (cond
        (< buf-pos (ranges :string-packed-length-end))
        (write-raw-byte wtr (+ (codes :string-packed-length-start) buf-pos))

        (= string-pos (count s))
        (do (write-code wtr (codes :string))
            (write-code wtr buf-pos))

        :default
        (do (write-code wtr (codes :string-chunk))
            (write-count wtr buf-pos)))
      (write-raw-bytes wtr string-buffer 0 buf-pos)
      (when (< string-pos (count s))
        (recur (buffer-string-chunk-utf8 s string-pos string-buffer)))))
  wtr)

(defn write-list [wtr l]
  (let [length (count l)]
    (if (< length (ranges :list-packed-length-end))
      (write-raw-byte wtr (+ length (codes :list-packed-length-start)))
      (do (write-code wtr (codes :list))
          (write-count wtr length)))
    (doall
      (doseq [item l]
        (write-object wtr item)))))

(defn write-map [wtr m]
  (write-tag wtr "map" 1)
  (write-list wtr (flatten (seq m))))

(defn- clear-caches [wtr])

(defn- internal-write-footer [wtr length]
  (write-raw-int32 wtr (codes :footer-magic))
  (write-raw-int32 wtr length)
  ;;(write-raw-int32 wtr (checksum))
  (reset wtr))

(defn write-footer [wtr]
  (internal-write-footer wtr (:index @wtr))
  (clear-caches wtr)
  wtr)


(defmulti internal-write (fn [_ o] (type o)))

(defmethod internal-write js/Number [wtr n]
  (cond
    ;; Integer/Long
    (= (.ceil js/Math n) n) (write-int wtr n)
    ;; Float
    (< (.pow js/Math -126) n (.pow js/Math 128)) (write-float wtr n)
    ;; Double
    :default (write-double wtr n)))

(defmethod internal-write js/Boolean [wtr b]
  (if b (write-code wtr (codes :true))
        (write-code wtr (codes :false))))

(defmethod internal-write js/String [wtr s]
  (cond
    (keyword? s) (write-named "key" wtr s)
    :default (write-string wtr s)))

;; Date
(defmethod internal-write js/Date [wtr d]
  (write-tag wtr "inst" 1)
  (write-int wtr (.getTime d)))

;; Javascript Array
(defmethod internal-write js/Array [wtr arr]
  (write-list wtr arr))

;; Map
(defmethod internal-write cljs.core/ObjMap [wtr m]
  (write-map wtr m))

(defmethod internal-write cljs.core/PersistentHashMap [wtr m]
  (write-map wtr m))

(defmethod internal-write cljs.core/PersistentArrayMap [wtr m]
  (write-map wtr m))

;; Vector
(defmethod internal-write cljs.core/PersistentVector [wtr v])

;; Sequence
(defmethod internal-write cljs.core/ChunkedSeq [wtr s])

;; Set
(defmethod internal-write cljs.core/PersistentHashSet [wtr s])

;; Keyword
(defmethod internal-write cljs.core/Keyword [wtr k]
  (write-named "key" wtr k))

;; Symbol
(defmethod internal-write cljs.core/Symbol [wtr s]
  (write-named "sym" wtr s))

;; Null
(defmethod internal-write nil [wtr null-ref]
  (write-code wtr (codes :null)))

(defn- should-skip-cache? [o]
  (cond
    (or (nil? o) (= (type o) js/Boolean)) true
    (= (type o) js/String) (= (count o) 0)
    :default false))

(defn do-write [wtr tag o handler cache?]
  (if cache?
    (if (should-skip-cache? o)
      (do-write wtr tag o handler false)
      (let [index (old-index priority-cache o)]
        (if (= index -1)
          (do (write-code wtr (codes :put-priority-cache))
              (do-write wtr tag o handler false))
          (if (< index (ranges :priority-cache-packed-end))
            (write-code wtr (+ (codes :priority-cache-packed-start) index))
            (do (write-code wtr  (codes :get-priority-cache))
                (write-int wtr index))))))
    (handler wtr o)))

(defn write-as
  ([wtr tag o]
    (write-as wtr nil o false))
  ([wtr tag o cache?]
    (let [handler (get-method internal-write (type o))]
      (do-write wtr tag o handler cache?))))

(defn write-object
  ([wtr o cache?] (write-as wtr nil o cache?))
  ([wtr o]        (write-as wtr nil o)))
