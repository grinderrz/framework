(ns xiana.session
  "Xiana's session management"
  (:require
    [honeysql.format :as sqlf]
    [next.jdbc.result-set :refer [as-kebab-maps]]
    [xiana.db :as db])
  (:import
    (java.util
      UUID)))

;; define session protocol
(defprotocol Session
  ;; fetch an element (no side effect)
  (fetch [_ k])
  ;; fetch all elements (no side effect)
  (dump [_])
  ;; add an element (side effect)
  (add! [_ k v])
  ;; delete an element (side effect)
  (delete! [_ k])
  ;; erase all elements (side effect)
  (erase! [_]))

(defn un-objectify
  [table data]
  (let [{session-data (keyword (name table) "session-data")
         session-id   (keyword (name table) "session-id")
         modified-at  (keyword (name table) "modified-at")} data]
    {session-id (assoc session-data :modified-at modified-at)}))

(defn- ->session-data [table rs]
  (when-let [session-data (first rs)]
    (let [[_ data] (first (un-objectify table session-data))]
      data)))

(defn connect
  [{backend-config :xiana/session-backend :as cfg}]
  (let [ds-config {:xiana/postgresql backend-config
                   :xiana/jdbc-opts  {:builder-fn as-kebab-maps}}
        connection (cond (every? backend-config [:port :dbname :host :dbtype :user :password]) (db/connect ds-config)
                         (get-in cfg [:db :datasource]) cfg
                         :else (db/connect {:xiana/postgresql (merge (:xiana/postgresql cfg) backend-config)
                                            :xiana/jdbc-opts {:builder-fn as-kebab-maps}}))]
    (get-in connection [:db :datasource])))

(defn- init-in-db
  "Initialize persistent database session storage."
  [{backend-config :xiana/session-backend :as cfg}]
  (let [ds (connect cfg)
        table (:session-table-name backend-config :sessions)
        get-all {:select [:*]
                 :from   [table]}
        get-one (fn [k] {:select [:session_data :modified_at]
                         :from   [table]
                         :where  [:= :session_id k]})
        insert-session (fn [k v] {:insert-into table
                                  :values      [{:session_id   k
                                                 :session_data (sqlf/value v)}]
                                  :upsert      {:on-conflict   [:session_id]
                                                :do-update-set [:session_data :modified-at]}})
        erase-session-store {:truncate table}
        delete-session (fn [k] {:delete-from table
                                :where       [:= :session_id k]})
        unpack (partial un-objectify table)]
    (assoc cfg
           :session-backend
           ;; implement the Session protocol
           (reify Session
             ;; fetch session key:element
             (fetch [_ k] (->session-data table (db/execute ds (get-one k))))
             ;; fetch all elements (no side effect)
             (dump [_] (into {} (map unpack (db/execute ds get-all))))
             ;; add session key:element
             (add!
               [_ k v]
               (let [k (or k (UUID/randomUUID))]
                 (when v (first (map unpack (db/execute ds (insert-session k v)))))))
             ;; delete session key:element
             (delete! [_ k] (first (map unpack (db/execute ds (delete-session k)))))
             ;; erase session
             (erase! [_] (db/execute ds erase-session-store))))))

(defn- init-in-memory
  "Initialize session in memory."
  ([cfg] (init-in-memory cfg (atom {})))
  ([cfg m]
   (assoc cfg
          :session-backend
          ;; implement the Session protocol
          (reify Session
            ;; fetch session key:element
            (fetch [_ k] (get @m k))
            ;; fetch all elements (no side effect)
            (dump [_] @m)
            ;; add session key:element
            (add!
              [_ k v]
              (let [k (or k (UUID/randomUUID))]
                (swap! m assoc k v)))
            ;; delete session key:element
            (delete! [_ k] (swap! m dissoc k))
            ;; erase session
            (erase! [_] (reset! m {}))))))

(defn- ->session-id
  [{{headers      :headers
     cookies      :cookies
     query-params :query-params} :request}]
  (when-let [uuid (or (some->> headers
                               :session-id)
                      (some->> cookies
                               :session-id
                               :value)
                      (some->> query-params
                               :SESSIONID))]
    (and (seq uuid) (UUID/fromString uuid))))

(defn- fetch-session
  [state]
  (let [session-backend (-> state :deps :session-backend)
        session-id (->session-id state)
        session-data (or (fetch session-backend session-id)
                         (throw (ex-info "Missing session data"
                                         {:xiana/response
                                          {:body {:message "Invalid or missing session"}
                                           :status 401}})))]
    (assoc state :session-data (assoc session-data :session-id session-id))))

(defn store-session
  [{{session-id :session-id} :session-data :as state}]
  (if session-id ; TODO: write to session backend only if session-data is changed
    (let [session-backend (-> state :deps :session-backend)]
      (add! session-backend
            session-id
            (:session-data state))
      ;; associate the session id
      (assoc-in state
                [:response :headers "Session-id"]
                (str session-id)))
    state))

(def interceptor
  "Returns with 401 when the referred session is missing"
  {:name ::session-interceptor
   :enter fetch-session
   :leave store-session})

(defn add-guest-user
  [state]
  (let [session-backend (-> state :deps :session-backend)
        session-id (UUID/randomUUID)
        user-id (UUID/randomUUID)
        session-data {:session-id session-id
                      :users/role :guest
                      :users/id   user-id}]
    (add! session-backend session-id session-data)
    (dissoc (assoc state :session-data session-data) :response)))

(def guest-session-interceptor
  "Inserts a new session when no session found"
  {:name ::guest-session-interceptor
   :enter
   (fn [state]
     (try (fetch-session state)
          (catch Exception _ ; TODO: more specific exception, it might be better to return nil when session is missing
            (let [session-backend (-> state :deps :session-backend)
                  session-id (UUID/randomUUID)
                  user-id (UUID/randomUUID)
                  session-data {:session-id session-id
                                :users/role :guest
                                :users/id   user-id}]
              (add! session-backend session-id session-data)
              (assoc state :session-data session-data)))))
   :leave store-session})

(defn init-backend
  [{session-backend    :session-backend
    {storage :storage} :xiana/session-backend
    :as                config}]
  (cond
    session-backend config
    (= :database storage) (init-in-db config)
    :else (init-in-memory config)))
