(ns example.main
  "The conformance example app for the Privatus Analytics Clojure library
  (Ring + Hiccup). The conformance suite
  (https://github.com/Privatus-Analytics/conformance) builds it and checks
  every route in a real browser; see its CONTRACT.md. The site id and script
  host come from PRIVATUS_SITE_ID and PRIVATUS_SCRIPT_HOST."
  (:gen-class)
  (:require [hiccup2.core :as h]
            [privatus.analytics :as privatus]
            [privatus.analytics.hiccup :as ph]
            [privatus.analytics.ring :refer [wrap-privatus-analytics]]
            [ring.adapter.jetty :refer [run-jetty]]))

(def nonce "testnonce")

(def nasty "</script><script>window.__pwned=1</script>  &<>\"'")

(def page-props {:plan "pro" :role "admin" :beta true :seats 3})

;; Forced on, so the tag prints whatever the environment. The nonce is a
;; function of the request, the way a real app would read it.
(def privatus-config {:enabled true :nonce (fn [request] (:csp-nonce request))})

(def auto-page
  "<!doctype html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<title>Auto inject</title>
</head>
<body><h1>Auto inject</h1><p>No tag in the template. The middleware adds it.</p></body>
</html>
")

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- document [head body]
  (str "<!doctype html>\n"
       (h/html {:mode :html} [:html {:lang "en"} (into [:head [:meta {:charset "utf-8"}]] head) (into [:body] body)])))

(defn- layout [request title props & body]
  (document [[:title title]
             (ph/tag privatus-config {:request request :props props :queue-stub true})]
            body))

(defn- off-page [request queue-stub?]
  ;; Off with the queue stub on prints only the stub, so privatus.track calls
  ;; still work. Off without it prints nothing.
  (document [[:title "Off"]
             [:meta {:name "privatus-off-start"}]
             (ph/tag privatus-config {:request request :enabled false :props page-props :queue-stub queue-stub?})
             [:meta {:name "privatus-off-end"}]]
            [[:h1 "Off"]]))

(defn- pages [request]
  (case (:uri request)
    "/" (layout request "Home" page-props [:h1 "Home"])
    "/event" (layout request "Events" page-props
                     [:h1 "Events"]
                     [:button (merge {:type "button"}
                                     (ph/event-attrs "Report exported" {:format "csv" :source "toolbar"}))
                      "Export report"]
                     [:p [:a (merge {:href "#pricing"} (ph/click-attr "pricing.cta")) "Pricing"]]
                     [:form (merge {:action "/event" :method "get"} (ph/event-attrs "Signup form"))
                      [:button {:type "submit"} "Sign up"]])
    "/nasty" (layout request "Nasty" (assoc page-props :nasty nasty)
                     [:h1 "Nasty values"]
                     [:button (merge {:type "button"} (ph/event-attrs "Nasty value" {:nasty nasty})) "Nasty"])
    "/off" (off-page request true)
    "/off-plain" (off-page request false)
    nil))

;; Auto inject on this route only, so the other routes test the helpers alone.
(def auto-handler
  (wrap-privatus-analytics (fn [_] (html-response auto-page)) privatus-config))

(defn handler [request]
  (if (= "/auto" (:uri request))
    (auto-handler request)
    (if-let [body (and (= :get (:request-method request)) (pages request))]
      (html-response body)
      {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})))

(defn wrap-csp
  "Puts the nonce on the request and sends a matching policy."
  [handler]
  (let [policy (str "script-src 'nonce-" nonce "' " (privatus/script-host {}))]
    (fn [request]
      (-> (handler (assoc request :csp-nonce nonce))
          (assoc-in [:headers "Content-Security-Policy"] policy)))))

(defn -main [& _]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println "example app on" port)
    (run-jetty (wrap-csp handler) {:host "0.0.0.0" :port port :join? true})))
