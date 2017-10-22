(ns reitit.ring
  (:require [meta-merge.core :refer [meta-merge]]
            [reitit.middleware :as middleware]
            [reitit.core :as reitit]
            [reitit.impl :as impl]))

(def http-methods #{:get :head :patch :delete :options :post :put})
(defrecord Methods [get head post put delete trace options connect patch any])

(defn- group-keys [meta]
  (reduce-kv
    (fn [[top childs] k v]
      (if (http-methods k)
        [top (assoc childs k v)]
        [(assoc top k v) childs])) [{} {}] meta))

(defn ring-handler [router]
  (with-meta
    (fn
      ([request]
       (if-let [match (reitit/match-by-path router (:uri request))]
         (let [method (:request-method request :any)
               params (:params match)
               result (:result match)
               handler (or (-> result method :handler)
                           (-> result :any :handler))]
           (if handler
             (handler
               (cond-> (impl/fast-assoc request ::match match)
                       (seq params) (impl/fast-assoc :path-params params)))))))
      ([request respond raise]
       (if-let [match (reitit/match-by-path router (:uri request))]
         (let [method (:request-method request :any)
               params (:params match)
               result (:result match)
               handler (or (-> result method :handler)
                           (-> result :any :handler))]
           (if handler
             (handler
               (cond-> (impl/fast-assoc request ::match match)
                       (seq params) (impl/fast-assoc :path-params params))
               respond raise))))))
    {::router router}))

(defn get-router [handler]
  (some-> handler meta ::router))

(defn get-match [request]
  (::match request))

(defn coerce-handler [[path meta] {:keys [expand] :as opts}]
  [path (reduce
          (fn [acc method]
            (if (contains? acc method)
              (update acc method expand opts)
              acc)) meta http-methods)])

(defn compile-result [[path meta] opts]
  (let [[top childs] (group-keys meta)]
    (if-not (seq childs)
      (let [middleware (middleware/compile-result [path top] opts)]
        (map->Methods {:any (middleware/compile-result [path top] opts)}))
      (let [any-handler (if (:handler top) (middleware/compile-result [path meta] opts))]
        (reduce-kv
          (fn [acc method meta]
            (let [meta (meta-merge top meta)]
              (assoc acc method (middleware/compile-result [path meta] opts method))))
          (map->Methods {:any any-handler})
          childs)))))

(defn router
  ([data]
   (router data nil))
  ([data opts]
   (let [opts (meta-merge {:coerce coerce-handler, :compile compile-result} opts)]
     (reitit/router data opts))))