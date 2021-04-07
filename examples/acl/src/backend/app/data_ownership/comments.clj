(ns data-ownership.comments
  (:require
    [honeysql.helpers :refer :all :as helpers]
    [xiana.core :as xiana]))

(defn fetch-owner-fn
  [state]
  (xiana/ok (assoc state :over (fn [query user-id over]
                                 (if (= :own over)
                                   (-> query (merge-where [:= :user_id user-id]))
                                   query)))))

(defn update-owner-fn
  [state]
  (xiana/ok (assoc state :over (fn [query user-id over]
                                 (if (= :own over)
                                   (-> query (merge-where [:= :user_id user-id]))
                                   query)))))

(defn delete-owner-fn
  [state]
  (xiana/ok (assoc state :over (fn [query user-id over]
                                 (if (= :own over)
                                   (-> query (merge-where [:= :user_id user-id]))
                                   query)))))