(ns privatus.analytics
  "Prints the cookieless Privatus Analytics tracking script and the HTML
  attributes the tracker reads for custom events and click maps.

  This library only builds HTML. It never sends events from the server, sets
  no cookies and reads nothing about the visitor."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def version "1.0.1")

(def default-script-host "https://privatusstats.com")

(def spa-modes #{"auto" "history" "hash" "off"})

(def props-function "privatusPageProps")

(def marker
  "Text that shows a page already has the tracker tag."
  "/js/pa.js")

(def queue-stub
  "The tracker's official queue stub. It keeps privatus.track calls made before
  pa.js loads and the tracker sends them later."
  (str "window.privatus=window.privatus||{q:[]};['track','pageview','props','optOut','optIn','ready']"
       ".forEach(function(m){window.privatus[m]=window.privatus[m]||function(){window.privatus.q.push("
       "[m,Array.prototype.slice.call(arguments)])}})"))

(def ^:private max-name 120)
(def ^:private max-key-length 64)
(def ^:private max-props 30)
(def ^:private max-value 500)
(def ^:private max-items 30)
(def ^:private click-name #"[A-Za-z0-9_.:-]{1,100}")

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- getenv [k] (System/getenv ^String k))

(defn- callable? [x] (or (fn? x) (var? x)))

(defn- env-value [k]
  (let [v (some-> (getenv k) str/trim)]
    (when (seq v) v)))

(defn- text
  "Turns a value into text. Keywords and symbols lose their colon."
  [v]
  (cond
    (nil? v) ""
    (keyword? v) (subs (str v) 1)
    (instance? java.math.BigDecimal v) (.toPlainString ^java.math.BigDecimal v)
    :else (str v)))

(defn- cut
  "The first n characters of s, never splitting a character in two."
  [^String s n]
  (if (<= (.codePointCount s 0 (.length s)) n)
    s
    (subs s 0 (.offsetByCodePoints s 0 (int n)))))

(defn escape-html
  "Escapes & < > \" and ' for use in HTML text or attribute values."
  [v]
  (let [s ^String (text v)
        sb (StringBuilder. (.length s))]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \& (.append sb "&amp;")
          \< (.append sb "&lt;")
          \> (.append sb "&gt;")
          \" (.append sb "&quot;")
          \' (.append sb "&#39;")
          (.append sb c))))
    (str sb)))

;; ---------------------------------------------------------------------------
;; Props: the rules shared by event props and page props

(defn normalize-key
  "Lowercases a prop key, swaps anything outside a-z 0-9 _ for _ and cuts it
  to 64 characters."
  [k]
  (-> (str/lower-case (text k))
      (str/replace #"[^a-z0-9_]" "_")
      (cut max-key-length)))

(defn- ordered-merge
  "Adds [k v] pairs to a vector of pairs. A key seen before keeps its place
  and gets the new value."
  [pairs [k v]]
  (if-let [i (first (keep-indexed (fn [i [pk]] (when (= pk k) i)) pairs))]
    (assoc pairs i [k v])
    (conj pairs [k v])))

(defn normalize-props
  "Clean prop pairs, in order: keys normalised, nil values dropped, at most 30."
  [props]
  (->> props
       (keep (fn [[k v]]
               (let [k (normalize-key k)]
                 (when (and (seq k) (some? v)) [k v]))))
       (reduce ordered-merge [])
       (take max-props)
       vec))

(defn- list-value? [v]
  (or (sequential? v) (set? v) (and (some? v) (.isArray ^Class (class v)))))

(defn- attr-value [v]
  (-> (if (list-value? v)
        (str/join "," (map text (remove nil? (seq v))))
        (text v))
      (cut max-value)))

;; ---------------------------------------------------------------------------
;; Event and click attributes

(defn event-attrs
  "Attributes for a custom event, as a map with keyword keys. Put it straight
  into a Hiccup element:

    [:button (event-attrs \"Report exported\" {:format \"csv\"}) \"Export\"]

  The name is trimmed and cut to 120 characters. An empty name gives {}.
  On a <form> the event is sent when the form is submitted."
  ([event-name] (event-attrs event-name nil))
  ([event-name props]
   (let [n (-> (str/trim (text event-name)) (cut max-name) str/trim)]
     (if (str/blank? n)
       {}
       (into (array-map :data-privatus-event n)
             (map (fn [[k v]] [(keyword (str "data-privatus-prop-" k)) (attr-value v)]))
             (normalize-props props))))))

(defn valid-click?
  "Whether a click map name is valid: letters, digits, _ . : - and at most 100
  characters."
  [click-map-name]
  (boolean (and (or (string? click-map-name) (keyword? click-map-name))
                (re-matches click-name (text click-map-name)))))

(defn- warn-default [message]
  (when-not (= "production" (env-value "PRIVATUS_ENV"))
    (binding [*out* *err*]
      (println message))))

(def ^:dynamic *warn*
  "Called with a message when a click map name is not valid. By default it
  prints to stderr, except when PRIVATUS_ENV is production. Rebind it to send
  the warning to your logger, or to (constantly nil) to keep quiet."
  warn-default)

(defn click-attr
  "The click map name attribute, as a map: {:data-privatus-click \"pricing.cta\"}.
  Gives {} when the name is not valid."
  [click-map-name]
  (if (valid-click? click-map-name)
    {:data-privatus-click (text click-map-name)}
    (do (*warn* (str "[privatus-analytics] click map name " (pr-str click-map-name)
                     " is not valid. Use letters, digits and _ . : - only, at most 100 characters."
                     " Nothing was printed."))
        {})))

(defn attrs->html
  "Turns an attribute map into HTML, with every value escaped.
  true prints the bare name, nil and false print nothing."
  [attrs]
  (->> attrs
       (keep (fn [[k v]]
               (cond
                 (or (nil? v) (false? v)) nil
                 (true? v) (text k)
                 :else (str (text k) "=\"" (escape-html v) "\""))))
       (str/join " ")))

(defn event-attrs-html
  "The event attributes as escaped HTML, for string templates like Selmer:

    <button {{export-attrs|safe}}>Export</button>"
  ([event-name] (event-attrs-html event-name nil))
  ([event-name props] (attrs->html (event-attrs event-name props))))

(defn click-attr-html
  "The click map attribute as escaped HTML: data-privatus-click=\"pricing.cta\"."
  [click-map-name]
  (attrs->html (click-attr click-map-name)))

;; ---------------------------------------------------------------------------
;; Page props JSON (no JSON library needed)

(defn- json-string [^String s]
  (let [sb (StringBuilder. (+ 2 (.length s)))]
    (.append sb \")
    (dotimes [i (.length s)]
      (let [c (.charAt s i)
            n (int c)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          (if (or (< n 0x20) (= c \<) (= c \>) (= c \&) (= n 0x2028) (= n 0x2029)
                  (Character/isSurrogate c))
            (if (and (Character/isHighSurrogate c) (< (inc i) (.length s))
                     (Character/isLowSurrogate (.charAt s (inc i))))
              (.append sb c)
              (if (and (Character/isLowSurrogate c) (pos? i)
                       (Character/isHighSurrogate (.charAt s (dec i))))
                (.append sb c)
                (.append sb (format "\\u%04x" n))))
            (.append sb c)))))
    (.append sb \")
    (str sb)))

(defn- json-scalar
  "A JSON text for one value, or nil to drop it."
  [v]
  (cond
    (nil? v) nil
    (boolean? v) (str v)
    (or (instance? Long v) (instance? Integer v) (instance? Short v) (instance? Byte v)
        (instance? java.math.BigInteger v) (instance? clojure.lang.BigInt v))
    (str v)
    (or (instance? Double v) (instance? Float v) (ratio? v) (decimal? v))
    (let [d (double v)]
      (when-not (or (Double/isNaN d) (Double/isInfinite d))
        (if (decimal? v) (.toPlainString ^java.math.BigDecimal v) (str d))))
    :else (json-string (cut (text v) max-value))))

(defn- json-value [v]
  (if (list-value? v)
    (str "[" (str/join "," (keep json-scalar (take max-items (remove nil? (seq v))))) "]")
    (json-scalar v)))

(defn page-props-json
  "Page props as a JSON object that is safe inside a <script>, or nil when there
  is nothing to send. Numbers and booleans stay JSON numbers and booleans, lists
  stay arrays (at most 30 items). < > & U+2028 and U+2029 are escaped."
  [props]
  (let [pairs (keep (fn [[k v]] (when-let [j (json-value v)] [k j]))
                    (normalize-props props))]
    (when (seq pairs)
      (str "{" (str/join "," (map (fn [[k j]] (str (json-string k) ":" j)) pairs)) "}"))))

;; ---------------------------------------------------------------------------
;; Settings

(defn site-id
  "The site id from the config, or the PRIVATUS_SITE_ID environment variable."
  [config]
  (let [v (str/trim (text (:site-id config)))]
    (if (seq v) v (env-value "PRIVATUS_SITE_ID"))))

(defn script-host
  "Where /js/pa.js is served: the config, PRIVATUS_SCRIPT_HOST, or
  https://privatusstats.com. A trailing slash is removed."
  [config]
  (let [v (str/trim (text (:script-host config)))
        v (if (seq v) v (or (env-value "PRIVATUS_SCRIPT_HOST") default-script-host))]
    (if (str/ends-with? v "/") (subs v 0 (dec (count v))) v)))

(defn enabled?
  "true or false in :enabled forces the tag on or off. Otherwise the tag is on
  only when the PRIVATUS_ENV environment variable is production."
  [config]
  (if (contains? #{true false} (:enabled config))
    (:enabled config)
    (= "production" (env-value "PRIVATUS_ENV"))))

(defn render?
  "Whether the tag prints: there is a site id and the tag is enabled."
  [config]
  (boolean (and (site-id config) (enabled? config))))

(defn- string-list
  "nil stays nil. Anything else becomes a clean vector of strings."
  [v]
  (when (some? v)
    (->> (if (list-value? v) (seq v) [v])
         (mapcat #(str/split (text %) #","))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- keyed [props]
  (map (fn [[k v]] [(normalize-key k) v]) props))

(defn page-props
  "The configured props for one request, with per call props merged on top.
  :props in the config may be a map or a function that gets the request."
  ([config] (page-props config nil))
  ([config overrides]
   (let [configured (:props config)
         configured (if (callable? configured) (configured (:request overrides (:request config))) configured)]
     (reduce ordered-merge []
             (concat (keyed (when (map? configured) configured))
                     (keyed (:props overrides)))))))

(defn- nonce-value [opts]
  (let [n (:nonce opts)]
    (if (callable? n) (n (:request opts)) n)))

(defn tracker-attrs
  "The tracker <script> attributes as an ordered map with string keys."
  ([config] (tracker-attrs config nil))
  ([config overrides]
   (let [opts (merge config (dissoc overrides :props))
         base (array-map "defer" true
                         "src" (str (script-host opts) "/js/pa.js")
                         "data-site" (site-id opts))
         lists (for [[k attr] [[:modules "data-modules"] [:excluded-paths "data-exclude"]
                               [:domains "data-domains"]]
                     :let [vs (string-list (get opts k))]
                     ;; nil keeps the tracker default. An empty modules list loads none.
                     :when (and vs (or (= k :modules) (seq vs)))]
                 [attr (str/join "," vs)])
         spa (text (:spa opts))
         extra (for [[k v] (:extra-attributes opts) :when (some? v)] [(text k) (if (false? v) "false" v)])
         nonce (nonce-value opts)]
     (cond-> (into base lists)
       (and (spa-modes spa) (not= "auto" spa)) (assoc "data-spa" spa)
       (true? (:manual opts)) (assoc "data-manual" "true")
       :always (into extra)
       nonce (assoc "nonce" (text nonce))))))

(defn tag-parts
  "The parts of the tag as data, or nil when nothing should print:
  {:queue-stub js-or-nil, :props-script js-or-nil, :nonce str-or-nil,
   :attrs ordered-map-of-tracker-attributes-or-nil}.

  When the tag is off (not enabled, or no site id) and :queue-stub is on, only
  {:queue-stub js :nonce str-or-nil} is given (no tracker and no page props;
  a :props function is not called), so privatus.track calls never fail."
  ([config] (tag-parts config nil))
  ([config overrides]
   (let [opts (merge config (dissoc overrides :props))
         stub (when (true? (:queue-stub opts)) queue-stub)]
     (cond
       (render? opts)
       (let [props-json (page-props-json (page-props config overrides))
             attrs (tracker-attrs config overrides)]
         {:queue-stub stub
          :props-script (when props-json
                          (str "window." props-function "=function(){return " props-json "}"))
          :nonce (some-> (nonce-value opts) text)
          :attrs (cond-> attrs props-json (assoc "data-props" props-function))})

       stub
       {:queue-stub stub
        :props-script nil
        :nonce (some-> (nonce-value opts) text)
        :attrs nil}

       :else nil))))

(defn- inline-script [nonce code]
  (str (if nonce (str "<script nonce=\"" (escape-html nonce) "\">") "<script>") code "</script>"))

(defn tag
  "The Privatus Analytics script tag as an HTML string. Put it in the <head>.

    (tag {:site-id \"pa_YOURSITEID\"})
    (tag config {:request request :props {:section \"billing\"}})

  config keys (all optional): :site-id :script-host :modules :excluded-paths
  :domains :spa :manual :enabled :extra-attributes :props :queue-stub :nonce.
  overrides: any config key, plus :request (passed to a :props function) and
  :props (merged over the configured props; a nil value removes one).

  Without a site id or when the tag is not enabled, gives only the queue stub
  script when :queue-stub is on, else \"\"."
  ([] (tag {} nil))
  ([config] (tag config nil))
  ([config overrides]
   (let [parts (tag-parts config overrides)
         nonce (:nonce parts)]
     (if (nil? parts)
       ""
       (->> [(some->> (:queue-stub parts) (inline-script nonce))
             (some->> (:props-script parts) (inline-script nonce))
             (when-let [attrs (:attrs parts)]
               (str "<script " (attrs->html attrs) "></script>"))]
            (remove nil?)
            (str/join "\n"))))))
