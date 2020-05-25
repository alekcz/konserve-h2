(ns konserve-jdbc.core
  "Address globally aggregated immutable key-value conn(s)."
  (:require [clojure.core.async :as async]
            [konserve.serializers :as ser]
            [hasch.core :as hasch]
            [clojure.string :as str]
            [clojure.java.jdbc :as j]
            [konserve.protocols :refer [PEDNAsyncKeyValueStore
                                        -exists? -get -get-meta
                                        -update-in -assoc-in -dissoc
                                        PBinaryAsyncKeyValueStore
                                        -bassoc -bget
                                        -serialize -deserialize
                                        PKeyIterable
                                        -keys]])
  (:import  [java.io ByteArrayInputStream ByteArrayOutputStream]
            [java.sql Connection Blob]
            [java.net URI]))

(set! *warn-on-reflection* 1)
(def version 1)

(defn add-version [bytes]
  (when (seq bytes) 
    (byte-array (into [] (concat [(byte version)] (vec bytes))))))

(defn strip-version [bytes]
  (when (seq bytes) 
    (byte-array (rest (vec bytes)))))

(defn it-exists? 
  [conn id]
  (let [res (first (j/query (:db conn) [(str "select 1 from " (:table conn) " where id = '" id "'")]))]
    (not (nil? res)))) 
  
(defn get-conn [db]
  (->> db j/get-connection (j/add-connection db)))

(defn close-conn [db]
  (.close ^Connection (db :connection)))

(defn get-it 
  [conn id]
  (let [db (get-conn (:db conn))
        res' (first (j/query db [(str "select * from " (:table conn) " where id = '" id "'")]))
        ^Blob data (:data res')
        ^Blob meta (:meta res')
        res (if (and data meta)
              [(strip-version (.getBytes meta 0 (.length meta))) (strip-version (.getBytes data 0 (.length data)))]
              [nil nil])]
    (close-conn db)
    res))  

(defn get-it-only
  [conn id]
  (let [db (get-conn (:db conn))
        res' (first (j/query db [(str "select id,data from " (:table conn) " where id = '" id "'")]))
        ^Blob data (:data res')
        res (when data (strip-version (.getBytes data 0 (.length data))))]
    (close-conn db)
    res))

(defn get-meta 
  [conn id]
  (let [db (get-conn (:db conn))
        res' (first (j/query db [(str "select id,meta from " (:table conn) " where id = '" id "'")]))
        ^Blob meta (:meta res')
        res (when meta (strip-version (.getBytes meta 0 (.length meta))))]
    (close-conn db)
    res))

(defn update-it 
  [conn id data]
  ;; INSERT INTO tablename (a,b,c) VALUES (1,2,3)
  ;; ON DUPLICATE KEY UPDATE c=c+1;

  ;; INSERT INTO tablename (a, b, c) values (1, 2, 10)
  ;; ON CONFLICT (a) DO UPDATE SET c = tablename.c + 1;
  (let [db (get-conn (:db conn))
        ^Connection raw-conn (db :connection)
        p (j/prepare-statement 
            raw-conn
            (str "insert into " (:table conn) " (id,meta,data) values(?, ?, ?) on conflict (id) do update set meta = ?, data = ?" ))]
    (j/execute! (:db conn) 
      [p id (add-version (first data)) 
            (add-version (second data)) 
            (add-version (first data)) 
            (add-version (second data))])
    (close-conn db)))

(defn delete-it 
  [conn id]
  (j/execute! (:db conn) [(str "delete from " (:table conn) " where id = '" id "'")])) 

(defn get-keys 
  [conn]
  (let [db (get-conn (:db conn))
        res' (j/query db [(str "select id,meta from " (:table conn))])
        res (doall (map #(strip-version (.getBytes ^Blob (:meta %) 0 (.length ^Blob (:meta %)))) res'))]
    (close-conn db)
    res))


(defn str-uuid 
  [key] 
  (str (hasch/uuid key))) 

(defn prep-ex 
  [^String message ^Exception e]
  (.printStackTrace e)
  (ex-info message {:error (.getMessage e) :cause (.getCause e) :trace (.getStackTrace e)}))

(defn prep-stream 
  [bytes]
  { :input-stream  (ByteArrayInputStream. bytes) 
    :size (count bytes)})

(defrecord JDBCStore [conn serializer read-handlers write-handlers locks]
  PEDNAsyncKeyValueStore
  (-exists? 
    [this key] 
      (let [res-ch (async/chan 1)]
        (async/thread
          (try
            (async/put! res-ch (it-exists? conn (str-uuid key)))
            (catch Exception e (async/put! res-ch (prep-ex "Failed to determine if item exists" e)))))
        res-ch))

  (-get 
    [this key] 
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (let [res (get-it-only conn (str-uuid key))]
            (if (some? res) 
              (let [data (-deserialize serializer read-handlers res)]
                (async/put! res-ch data))
              (async/close! res-ch)))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to retrieve value from store" e)))))
      res-ch))

  (-get-meta 
    [this key] 
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (let [res (get-meta conn (str-uuid key))]
            (if (some? res) 
              (let [data (-deserialize serializer read-handlers res)] 
                (async/put! res-ch data))
              (async/close! res-ch)))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to retrieve value metadata from store" e)))))
      res-ch))

  (-update-in 
    [this key-vec meta-up-fn up-fn args]
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (let [[fkey & rkey] key-vec
                [ometa' oval'] (get-it conn (str-uuid fkey))
                old-val [(when ometa'
                          (-deserialize serializer read-handlers ometa'))
                         (when oval'
                          (-deserialize serializer read-handlers oval'))]            
                [nmeta nval] [(meta-up-fn (first old-val)) 
                              (if rkey (apply update-in (second old-val) rkey up-fn args) (apply up-fn (second old-val) args))]
                ^ByteArrayOutputStream mbaos (ByteArrayOutputStream.)
                ^ByteArrayOutputStream vbaos (ByteArrayOutputStream.)]
            (when nmeta (-serialize serializer mbaos write-handlers nmeta))
            (when nval (-serialize serializer vbaos write-handlers nval))    
            (update-it conn (str-uuid fkey) [(.toByteArray mbaos) (.toByteArray vbaos)])
            (async/put! res-ch [(second old-val) nval]))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to update/write value in store" e)))))
        res-ch))

  (-assoc-in [this key-vec meta val] (-update-in this key-vec meta (fn [_] val) []))

  (-dissoc 
    [this key] 
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (delete-it conn (str-uuid key))
          (async/close! res-ch)
          (catch Exception e (async/put! res-ch (prep-ex "Failed to delete key-value pair from store" e)))))
        res-ch))

  PBinaryAsyncKeyValueStore
  (-bget 
    [this key locked-cb]
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (let [res (get-it-only conn (str-uuid key))]
            (if (some? res) 
              (async/put! res-ch (locked-cb (prep-stream res)))
              (async/close! res-ch)))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to retrieve binary value from store" e)))))
      res-ch))

  (-bassoc 
    [this key meta-up-fn input]
    (let [res-ch (async/chan 1)]
      (async/thread
        (try
          (let [[old-meta' old-val] (get-it conn (str-uuid key))
                old-meta (when old-meta' (-deserialize serializer read-handlers old-meta'))           
                new-meta (meta-up-fn old-meta) 
                ^ByteArrayOutputStream mbaos (ByteArrayOutputStream.)]
            (when new-meta (-serialize serializer mbaos write-handlers new-meta))
            (update-it conn (str-uuid key) [(.toByteArray mbaos) input])
            (async/put! res-ch [old-val input]))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to write binary value in store" e)))))
        res-ch))

  PKeyIterable
  (-keys 
    [_]
    (let [res-ch (async/chan)]
      (async/thread
        (try
          (let [key-stream (get-keys conn)
                keys' (when key-stream
                        (for [k key-stream]
                          (let [bais (ByteArrayInputStream. k)]
                            (-deserialize serializer read-handlers bais))))
                keys (doall (map :key keys'))]
            (doall
              (map #(async/put! res-ch %) keys))
            (async/close! res-ch)) 
          (catch Exception e (async/put! res-ch (prep-ex "Failed to retrieve keys from store" e)))))
        res-ch)))


(defn new-jdbc-store
  ([db & {:keys [table serializer read-handlers write-handlers]
                    :or {table "konserve"
                         serializer (ser/fressian-serializer)
                         read-handlers (atom {})
                         write-handlers (atom {})}}]
    (let [res-ch (async/chan 1)]                      
      (async/thread 
        (try
          (j/execute! db [(str "create table if not exists " table " (id varchar(100) primary key, meta blob, data blob)")])
          (async/put! res-ch
            (map->JDBCStore { :conn {:db db :table (str "`" table "`")}
                              :read-handlers read-handlers
                              :write-handlers write-handlers
                              :serializer serializer
                              :locks (atom {})}))
          (catch Exception e (async/put! res-ch (prep-ex "Failed to connect to store" e)))))
      res-ch)))

(defn delete-store [store]
  (let [res-ch (async/chan 1)]
    (async/thread
      (try
        (println (-> store :conn :db))
        (j/execute! (-> store :conn :db) [(str "drop table " (-> store :conn :table))])
        (async/close! res-ch)
        (catch Exception e (async/put! res-ch (prep-ex "Failed to delete store" e)))))          
    res-ch))