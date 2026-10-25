(ns privatus.analytics.hiccup
  "Hiccup helpers. They return plain vectors and maps, so this namespace does
  not need Hiccup itself. When Hiccup 2 is on the classpath, inline script code
  is wrapped with hiccup.util/raw-string so it is not escaped twice."
  (:require [privatus.analytics :as privatus]))

(set! *warn-on-reflection* true)

(def ^:private raw-string
  (delay (try (requiring-resolve 'hiccup.util/raw-string)
              (catch Exception _ nil))))

(defn- raw [code]
  (if-let [f @raw-string] (f code) code))

(defn- attrs-map [attrs]
  (into (array-map) (map (fn [[k v]] [(keyword k) v])) attrs))

(defn- elements [config overrides inline]
  (if-let [{:keys [nonce attrs] stub :queue-stub props :props-script}
           (privatus/tag-parts config overrides)]
    (->> [(when stub (inline nonce stub))
          (when props (inline nonce props))
          (when attrs [:script (attrs-map attrs)])]
         (remove nil?)
         seq)
    nil))

(defn tag
  "The tag as a seq of Hiccup elements, or nil when nothing should print.
  When the tag is off and :queue-stub is on, only the queue stub element.
  Put it in the head; Hiccup expands the seq:

    [:head [:title \"My app\"] (privatus.analytics.hiccup/tag config {:request request})]

  Takes the same config and overrides as privatus.analytics/tag."
  ([config] (tag config nil))
  ([config overrides]
   (elements config overrides
             (fn [nonce code] [:script (if nonce {:nonce nonce} {}) (raw code)]))))

(defn rum-tag
  "Like tag, for Rum (used by Biff). Inline code goes in :dangerouslySetInnerHTML,
  because Rum escapes plain strings."
  ([config] (rum-tag config nil))
  ([config overrides]
   (elements config overrides
             (fn [nonce code]
               [:script (cond-> {:dangerouslySetInnerHTML {:__html code}} nonce (assoc :nonce nonce))]))))

(def event-attrs
  "Same as privatus.analytics/event-attrs: an attribute map for a custom event.

    [:button (event-attrs \"Report exported\" {:format \"csv\"}) \"Export\"]"
  privatus/event-attrs)

(def click-attr
  "Same as privatus.analytics/click-attr: {:data-privatus-click name}, or {}."
  privatus/click-attr)

(defn- merge-attrs [element attrs]
  (let [[tag-name & more] element
        [current children] (if (map? (first more)) [(first more) (rest more)] [{} more])]
    (into [tag-name (merge current attrs)] children)))

(defn with-event
  "Adds custom event attributes to a Hiccup element:

    (with-event [:button {:class \"btn\"} \"Export\"] \"Report exported\" {:format \"csv\"})"
  ([element event-name] (with-event element event-name nil))
  ([element event-name props]
   (merge-attrs element (privatus/event-attrs event-name props))))

(defn with-click
  "Adds a click map name to a Hiccup element:

    (with-click [:a {:href \"/pricing\"} \"See plans\"] \"pricing.cta\")"
  [element click-map-name]
  (merge-attrs element (privatus/click-attr click-map-name)))
