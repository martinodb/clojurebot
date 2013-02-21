(ns clojurebot.triples.postgres
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging]))

(defmacro with-c [db & body]
  `(if (:connection clojure.java.jdbc.internal/*db*)
     (do
       ~@body)
     (sql/with-connection ~db
       ~@body)))

(def pg (delay (read-string (slurp (System/getProperty "postgres")))))

(defn create-store
  [name]
  (with-c (:url @pg)
    (sql/create-table
     (:table @pg)
     [:id :uuid "PRIMARY KEY" "DEFAULT uuid_generate_v4()"]
     [:subject "varchar(32670)"]
     [:predicate "varchar(32670)"]
     [:object "varchar(32670)"]
     [:upper_subject "varchar(32670)"]
     [:created_at :timestamp "NOT NULL" "DEFAULT CURRENT_TIMESTAMP"])))

;; TODO: remove this hack once this is fixed upstream
(in-ns 'clojure.java.jdbc.internal)

(defn do-prepared-return-keys*
  "Executes an (optionally parameterized) SQL prepared statement on the
  open database connection. Each param-group is a seq of values for all of
  the parameters.
  Return the generated keys for the (single) update/insert."
  [^String sql & param-groups]
  (with-open [^PreparedStatement stmt (prepare-statement* (connection*) sql :return-keys true)]
    (doseq [param-group param-groups]
      (set-parameters stmt param-group)
      (.addBatch stmt))
    (transaction* (fn []
                    (let [counts (.executeBatch stmt)]
                      (try
                        (first (resultset-seq* (.getGeneratedKeys stmt)))
                        (catch Exception _
                          ;; assume generated keys is unsupported and return counts instead:
                          counts)))))))

(in-ns 'clojurebot.triples.postgres)

(defn store-triple [{:keys [s p o]}]
  (with-c (:url @pg)
    (sql/transaction
     (sql/insert-values
      (:table @pg)
      [:subject :predicate :object :upper_subject]
      [(.trim (str s)) (.trim (str p)) (.trim (str o))
       (.toUpperCase (.trim (str s)))]))))

(defmulti query
  (fn [s p o]
    (cond
     (and (list? s) (keyword? p) (keyword? o))
     ::like_subject-_-_
     (and (not (keyword? s)) (not (keyword? p)) (keyword? o))
     ::subject-predicate-_
     (and (keyword? s) (keyword? p) (not (keyword? o)))
     ::_-_-object
     (and (keyword? s) (not (keyword? p)) (keyword? o))
     ::_-predicate-_
     (and (not (keyword? s)) (keyword? p) (keyword? o))
     ::subject-_-_
     (and (keyword? s) (keyword? p) (keyword? o))
     ::_-_-_
     (and (keyword? s) (not (keyword? p)) (not (keyword? o)))
     ::_-predicate-object
     :else
     ::subject-predicate-object)))

(defmethod query ::subject-_-_ [^String s p o]
  (try
    (with-c (:url @pg)
      (sql/with-query-results res
        ["SELECT * FROM ? WHERE upper_subject = ?"
         (:table @pg)
         (.toUpperCase s)]
        (doall res)))
    (catch Exception e
      (throw e))))

(defmethod query ::like_subject-_-_ [s p o]
  (try
    (with-c (:url @pg)
      (sql/with-query-results res
        ["SELECT * FROM ? WHERE upper_subject LIKE ?"
         (:table @pg)
         (.toUpperCase ^String (first s))]
        (doall res)))
    (catch Exception e
      (throw e))))

(defmethod query ::_-predicate-_ [s p o]
  (with-c (:url @pg)
    (sql/with-query-results res
      ["SELECT * FROM ? WHERE predicate = ?" (:table @pg) p]
      (doall res))))

(defmethod query ::subject-predicate-object [^String s p o]
  (try
    (with-c (:url @pg)
      (sql/with-query-results res
        [(str "SELECT * FROM ? WHERE "
              "predicate = ? AND "
              "upper_subject = ? AND "
              "object = ?")
         (:table @pg)
         p (.toUpperCase s) o]
        (doall res)))
    (catch Exception e
      (throw e))))

(defmethod query ::subject-predicate-_ [^String s p o]
  (clojure.tools.logging/info "QUERY" s p o)
  (try
    (with-c (:url @pg)
      (sql/with-query-results res
        [(str "SELECT * FROM ? WHERE "
              "predicate = ? AND "
              "upper_subject = ?")
         (:table @pg)
         p (.toUpperCase s)]
        (doall res)))
    (catch Exception e
      (throw e))))

(defmethod query ::_-predicate-object [s p o]
  (clojure.tools.logging/info "QUERY" s p o)
  (with-c (:url @pg)
    (sql/with-query-results res
      [(str "SELECT * FROM ? WHERE "
            "predicate = ? AND "
            "object = ?")
       (:table @pg)
       p o]
      (doall res))))

(defmethod query ::_-_-_ [s p o]
  (with-c (:url @pg)
    (sql/with-query-results res
      ["SELECT * FROM ?" (:table @pg)]
      (doall res))))

(defn delete [s p o]
  (doseq [id (map :id (query s p o))]
    (with-c (:url @pg)
      (sql/delete-rows (:table @pg) ["id = ?" id]))))