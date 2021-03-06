;; Copyright 2014-2015 Red Hat, Inc, and individual contributors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns testing.app
  (:require [clojure.java.io :as io]
            [immutant.web :as web]
            [immutant.web.async :as async]
            [immutant.web.sse :as sse]
            [immutant.internal.util :refer [maybe-deref]]
            [immutant.web.middleware :refer (wrap-session wrap-websocket)]
            [immutant.codecs :refer (encode)]
            [compojure.core :refer (GET defroutes)]
            [ring.util.response :refer (charset redirect response)]
            [ring.middleware.params :refer [wrap-params]]))

(defn counter [{session :session}]
  (let [count (:count session 0)
        session (assoc session :count (inc count))]
    (-> (response (str count))
      (assoc :session session))))

(defn dump
  [request]
  (let [data (encode (dissoc request :server-exchange :body :servlet
                       :servlet-request :servlet-response :servlet-context))]
    (if (:websocket? request)
      (async/as-channel request :on-open (fn [ch] (async/send! ch data)))
      (response data))))

(defn with-charset [request]
  (let [[_ cs] (re-find #"charset=(.*)" (:query-string request))
        body "気になったら"]
    (charset {:headers {"BodyBytes" (pr-str (into [] (.getBytes body cs)))}
              :body body}
      cs)))

(defn chunked-stream [request]
  (update-in
    (async/as-channel request
      {:on-open
       (fn [stream]
         (dotimes [n 10]
           (async/send! stream (str n) {:close? (= n 9)})))})
    [:headers] assoc "ham" "biscuit"))

(defn non-chunked-stream [request]
  (async/as-channel request
    {:on-open
     (fn [stream]
       (async/send! stream (apply str (repeat 128 1)) {:close? true}))}))

(defn sse
  [request]
  (sse/as-channel request
    {:on-open (fn [ch]
                (doseq [x (range 5 0 -1)]
                  (sse/send! ch x))
                (sse/send! ch {:event "close", :data "bye!"}))}))

(defn ws-as-channel
  [request]
  (assoc
    (async/as-channel request
      {:on-message (fn [ch message]
                     (async/send! ch (.toUpperCase message)))
       :on-error (fn [ch err]
                   (println "Error on websocket")
                   (.printStackTrace err))})
    :session (assoc (:session request) :ham :sandwich)))

(def client-defined-handler (atom (fn [_] (throw (Exception. "no handler given")))))

(def client-state (atom nil))

(defn set-client-handler [new-handler]
  (binding [*ns* (find-ns 'testing.app)]
    (reset! client-defined-handler (eval (read-string new-handler))))
  (response "OK"))

(defn use-client-handler [request]
  (@client-defined-handler request))

(defn get-client-state [_]
  (-> @client-state (maybe-deref 5000 :failure!) pr-str response))

(defroutes routes
  (GET "/" [] counter)
  (GET "/session" {s :session} (encode s))
  (GET "/unsession" [] {:session nil})
  (GET "/charset" [] with-charset)
  (GET "/chunked-stream" [] chunked-stream)
  (GET "/non-chunked-stream" [] non-chunked-stream)
  (GET "/sse" [] sse))

(defroutes cdef-handler
  (GET "/" [] use-client-handler)
  (GET "/set-handler" [new-handler] (set-client-handler new-handler))
  (GET "/state" [] get-client-state))

(defroutes nested-ws-routes
  (GET "/" [] dump)
  (GET "/foo" [] dump)
  (GET "/foo/bar" [] dump))

(defn run []
  (web/run (-> #'routes wrap-session))
  (web/run (-> #'cdef-handler wrap-params) :path "/cdef")
  (web/run (-> ws-as-channel wrap-session) :path "/ws")
  (web/run (-> dump wrap-session wrap-params) :path "/dump")
  (web/run nested-ws-routes :path "/nested-ws"))
