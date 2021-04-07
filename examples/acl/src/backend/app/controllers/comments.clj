(ns controllers.comments
  (:require
    [data-ownership.comments :as owner]
    [models.comments :as model]
    [views.comments :as views]
    [xiana.core :as xiana]))

(defn fetch
  [state]
  (xiana/flow->
    (assoc state :view views/comments)
    model/fetch-query
    owner/fetch-owner-fn))

(defn add
  [state]
  (xiana/flow->
    (assoc state :view views/comments)
    model/add-query))

(defn update-comment
  [state]
  (xiana/flow->
    (assoc state :view views/comments)
    model/update-query
    owner/update-owner-fn))

(defn delete-comment
  [state]
  (xiana/flow->
    (assoc state :view views/comments)
    model/delete-query
    owner/delete-owner-fn))

