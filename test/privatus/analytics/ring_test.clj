(ns privatus.analytics.ring-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [privatus.analytics.ring :refer [inject-tag wrap-privatus-analytics]])
  (:import (java.io ByteArrayInputStream File)))

(def config {:site-id "pa_TEST123456" :enabled true})

(def page "<html><head><title>Hi</title></head><body>Hello</body></html>")

(defn- html-response [body]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body body})

(defn- run
  ([response] (run config response {}))
  ([cfg response] (run cfg response {}))
  ([cfg response request]
   ((wrap-privatus-analytics (constantly response) cfg) (merge {:uri "/" :headers {}} request))))

(defn- content-length [response]
  (some (fn [[k v]] (when (= "content-length" (str/lower-case k)) v)) (:headers response)))

(deftest injects-into-string-bodies
  (let [response (run (html-response page))]
    (is (str/includes? (:body response) "data-site=\"pa_TEST123456\"></script>\n</head>"))
    (is (= (str (count (.getBytes ^String (:body response) "UTF-8"))) (content-length response)))))

(deftest replaces-an-old-content-length
  (let [response (run (assoc-in (html-response page) [:headers "content-length"] "5"))]
    (is (= 1 (count (filter #(= "content-length" (str/lower-case (key %))) (:headers response)))))
    (is (= (str (count (.getBytes ^String (:body response) "UTF-8"))) (content-length response)))))

(deftest counts-bytes-not-characters
  (let [response (run (html-response "<head></head>é"))]
    (is (= (str (count (.getBytes ^String (:body response) "UTF-8"))) (content-length response)))))

(deftest reads-input-stream-bodies
  (let [response (run (html-response (ByteArrayInputStream. (.getBytes (str page "é") "UTF-8"))))]
    (is (string? (:body response)))
    (is (str/includes? (:body response) "/js/pa.js"))
    (is (str/ends-with? (:body response) "é"))))

(deftest reads-input-streams-in-their-charset
  (let [response (run {:status 200 :headers {"content-type" "text/html; charset=ISO-8859-1"}
                       :body (ByteArrayInputStream. (.getBytes (str page "é") "ISO-8859-1"))})]
    (is (str/ends-with? (:body response) "é"))
    (is (= (str (count (.getBytes ^String (:body response) "ISO-8859-1"))) (content-length response)))))

(deftest reads-file-bodies
  (let [file (File/createTempFile "privatus" ".html")]
    (try
      (spit file page)
      (let [response (run (html-response file))]
        (is (str/includes? (:body response) "/js/pa.js\" data-site")))
      (finally (io/delete-file file true)))))

(deftest reads-seq-bodies
  (is (str/includes? (:body (run (html-response (list "<head>" "</head>")))) "/js/pa.js")))

(deftest leaves-other-bodies-alone
  (let [body (reify Object)
        response (html-response body)]
    (is (identical? response (run response)))))

(deftest skips-what-it-should
  (testing "non-200"
    (is (= page (:body (run (assoc (html-response page) :status 404))))))
  (testing "not HTML"
    (is (= page (:body (run (assoc (html-response page) :headers {"Content-Type" "application/json"}))))))
  (testing "encoded"
    (is (= page (:body (run (assoc-in (html-response page) [:headers "Content-Encoding"] "gzip"))))))
  (testing "Ajax"
    (is (= page (:body (run config (html-response page) {:headers {"x-requested-with" "XMLHttpRequest"}})))))
  (testing "already has the tag"
    (let [html "<head><script src=\"https://x/js/pa.js\"></script></head>"]
      (is (= html (:body (run (html-response html)))))))
  (testing "no head"
    (is (= "<p>hi</p>" (:body (run (html-response "<p>hi</p>"))))))
  (testing "disabled, no site id, or auto inject off"
    (is (= page (:body (run {:site-id "pa_TEST123456" :enabled false} (html-response page)))))
    (is (= page (:body (run {:enabled true} (html-response page)))))
    (is (= page (:body (run (assoc config :auto-inject false) (html-response page)))))))

(deftest unchanged-streams-still-get-a-string-body
  (let [response (run (html-response (ByteArrayInputStream. (.getBytes "<p>hi</p>" "UTF-8"))))]
    (is (= "<p>hi</p>" (:body response)))
    (is (= "9" (content-length response)))))

(deftest props-and-nonce-are-functions-of-the-request
  (let [cfg (assoc config
                   :props (fn [request] {:plan (get-in request [:session :plan])})
                   :nonce (fn [request] (:csp-nonce request)))
        body (:body (run cfg (html-response page) {:session {:plan "pro"} :csp-nonce "n0nce"}))]
    (is (str/includes? body "<script nonce=\"n0nce\">window.privatusPageProps=function(){return {\"plan\":\"pro\"}}</script>"))
    (is (str/includes? body "data-props=\"privatusPageProps\""))))

(deftest async-handlers
  (let [handler (wrap-privatus-analytics (fn [_ respond _] (respond (html-response page))) config)
        result (promise)]
    (handler {:headers {}} #(deliver result %) #(deliver result %))
    (is (str/includes? (:body @result) "/js/pa.js"))))

(deftest inject-tag-test
  (is (= "<HEAD>T\n</HEAD>" (inject-tag "<HEAD></HEAD>" "T")))
  (is (= "<head>$1\\\n</head></head>" (inject-tag "<head></head></head>" "$1\\"))))
