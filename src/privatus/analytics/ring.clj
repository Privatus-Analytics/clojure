(ns privatus.analytics.ring
  "Ring middleware that adds the Privatus Analytics tag just before </head> in
  HTML responses."
  (:require [clojure.string :as str]
            [privatus.analytics :as privatus])
  (:import (java.io File InputStream)
           (java.nio.charset Charset StandardCharsets)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(def ^:private head-close #"(?i)</head>")

(defn- header
  "A header value, whatever the case of its name."
  [headers ^String header-name]
  (some (fn [[k v]] (when (.equalsIgnoreCase header-name (name k)) v)) headers))

(defn- without-header [headers ^String header-name]
  (into {} (remove (fn [[k _]] (.equalsIgnoreCase header-name (name k)))) headers))

(defn- html? [headers]
  (str/starts-with? (str/lower-case (str (header headers "Content-Type"))) "text/html"))

(defn- charset ^Charset [headers]
  (or (when-let [[_ cs] (re-find #"(?i)charset=\"?([^;\s\"]+)" (str (header headers "Content-Type")))]
        (try (Charset/forName cs) (catch Exception _ nil)))
      StandardCharsets/UTF_8))

(defn- xhr? [request]
  (= "xmlhttprequest" (str/lower-case (str (header (:headers request) "X-Requested-With")))))

(defn- inject? [config request {:keys [status headers]}]
  (and (not (false? (:auto-inject config)))
       (= 200 status)
       (html? headers)
       (nil? (header headers "Content-Encoding"))
       (not (xhr? request))
       (privatus/render? config)))

(defn- body->string
  "The body as a string, or nil for bodies that are left alone (functions,
  streaming bodies and so on). Streams are read in full and closed."
  [body ^Charset cs]
  (cond
    (string? body) body
    (instance? InputStream body) (with-open [in ^InputStream body]
                                   (String. (.readAllBytes in) cs))
    (and (instance? File body) (.isFile ^File body)) (String. (Files/readAllBytes (.toPath ^File body)) cs)
    (bytes? body) (String. ^bytes body cs)
    (seq? body) (apply str body)
    :else nil))

(defn inject-tag
  "Puts the tag before the first </head> of an HTML string, unless the page has
  no </head> or already loads /js/pa.js."
  [^String html ^String tag-html]
  (if (or (str/includes? html privatus/marker)
          (not (re-find head-close html))
          (str/blank? tag-html))
    html
    (str/replace-first html head-close (fn [m] (str tag-html "\n" m)))))

(defn- process [config request response]
  (if-not (and (map? response) (inject? config request response))
    response
    (let [{:keys [headers body]} response
          cs (charset headers)
          html (body->string body cs)]
      (if (nil? html)
        response
        (let [tag-html (privatus/tag config {:request request})
              out (inject-tag html tag-html)]
          (if (and (string? body) (identical? out html))
            response
            (assoc response
                   :body out
                   :headers (assoc (without-header headers "Content-Length")
                                   "Content-Length" (str (alength (.getBytes ^String out cs)))))))))))

(defn wrap-privatus-analytics
  "Adds the tag to the <head> of HTML responses.

    (wrap-privatus-analytics handler {:site-id \"pa_YOURSITEID\"
                                      :props (fn [request] {:plan (plan-of request)})})

  config takes the same keys as privatus.analytics/tag. :props and :nonce may
  be functions that get the Ring request. :auto-inject false turns it off.

  Only 200 responses with a text/html content type are changed, and only when
  they have no Content-Encoding, are not Ajax requests (X-Requested-With:
  XMLHttpRequest), have a </head> and do not already load /js/pa.js.
  String, InputStream, File, byte array and seq bodies are read. Any other body
  is left alone. Content-Length is set to the new size.
  Works with both sync and async Ring handlers."
  ([handler] (wrap-privatus-analytics handler {}))
  ([handler config]
   (fn
     ([request]
      (process config request (handler request)))
     ([request respond raise]
      (handler request
               (fn [response]
                 (let [result (try {:ok (process config request response)}
                                   (catch Throwable t {:error t}))]
                   (if (contains? result :ok)
                     (respond (:ok result))
                     (raise (:error result)))))
               raise)))))
