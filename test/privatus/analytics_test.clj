(ns privatus.analytics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [privatus.analytics :as privatus]))

(def ^:dynamic *env* {})

(use-fixtures :each
  (fn [t]
    (binding [*env* {}]
      (with-redefs [privatus/*warn* (constantly nil)
                    ;; Tests never read the real environment.
                    privatus.analytics/getenv (fn [k] (get *env* k))]
        (t)))))

(def config {:site-id "pa_TEST123456" :enabled true})

(def basic "<script defer src=\"https://privatusstats.com/js/pa.js\" data-site=\"pa_TEST123456\"></script>")

;; --- settings ---------------------------------------------------------------

(deftest site-id-test
  (is (nil? (privatus/site-id {})))
  (binding [*env* {"PRIVATUS_SITE_ID" " pa_FROMENV123 "}]
    (is (= "pa_FROMENV123" (privatus/site-id {})))
    (is (= "pa_EXPLICIT01" (privatus/site-id {:site-id "pa_EXPLICIT01"})))))

(deftest script-host-test
  (is (= "https://privatusstats.com" (privatus/script-host {})))
  (is (= "https://stats.example.com" (privatus/script-host {:script-host "https://stats.example.com/"})))
  (binding [*env* {"PRIVATUS_SCRIPT_HOST" "https://env.example.com/"}]
    (is (= "https://env.example.com" (privatus/script-host {})))))

(deftest enabled-test
  (testing "only PRIVATUS_ENV=production by default"
    (is (false? (privatus/enabled? {})))
    (binding [*env* {"PRIVATUS_ENV" "development"}]
      (is (false? (privatus/enabled? {}))))
    (binding [*env* {"PRIVATUS_ENV" "production"}]
      (is (true? (privatus/enabled? {})))))
  (testing "enabled forces it on or off"
    (is (true? (privatus/enabled? {:enabled true})))
    (binding [*env* {"PRIVATUS_ENV" "production"}]
      (is (false? (privatus/enabled? {:enabled false}))))))

;; --- the tag ----------------------------------------------------------------

(deftest tag-test
  (testing "prints the basic snippet"
    (is (= basic (privatus/tag config))))
  (testing "prints nothing without a site id"
    (is (= "" (privatus/tag {:enabled true}))))
  (testing "prints nothing when not enabled"
    (is (= "" (privatus/tag {:site-id "pa_TEST123456"})))
    (is (= "" (privatus/tag))))
  (testing "uses the environment"
    (binding [*env* {"PRIVATUS_SITE_ID" "pa_TEST123456" "PRIVATUS_ENV" "production"}]
      (is (= basic (privatus/tag))))))

(deftest tag-options-test
  (testing "modules and exclusions"
    (let [html (privatus/tag (assoc config :modules ["engage" :auto] :excluded-paths ["/admin/*" "/preview/*"]))]
      (is (str/includes? html "data-modules=\"engage,auto\""))
      (is (str/includes? html "data-exclude=\"/admin/*,/preview/*\""))))
  (testing "an empty modules list loads none, empty exclusions print nothing"
    (let [html (privatus/tag (assoc config :modules [] :excluded-paths []))]
      (is (str/includes? html "data-modules=\"\""))
      (is (not (str/includes? html "data-exclude")))))
  (testing "domains, spa mode and manual mode"
    (let [html (privatus/tag (assoc config :domains "example.com, www.example.com" :spa :hash :manual true))]
      (is (str/includes? html "data-domains=\"example.com,www.example.com\""))
      (is (str/includes? html "data-spa=\"hash\""))
      (is (str/includes? html "data-manual=\"true\""))))
  (testing "unknown spa mode and auto print nothing"
    (is (not (str/includes? (privatus/tag (assoc config :spa "bogus")) "data-spa")))
    (is (not (str/includes? (privatus/tag (assoc config :spa "auto")) "data-spa"))))
  (testing "escapes attribute values"
    (is (str/includes? (privatus/tag (assoc config :excluded-paths ["/a\"b'<"])) "data-exclude=\"/a&quot;b&#39;&lt;\"")))
  (testing "per call options override the config"
    (is (str/includes? (privatus/tag (assoc config :modules ["engage"]) {:modules ["vitals"]})
                       "data-modules=\"vitals\"")))
  (testing "extra attributes and a nonce"
    (let [html (privatus/tag (assoc config :extra-attributes {"data-mask" "/orders/*->/orders/:id"
                                                              :data-debug "true"
                                                              "data-gone" nil})
                             {:nonce "abc123"})]
      (is (str/includes? html "data-mask=\"/orders/*-&gt;/orders/:id\""))
      (is (str/includes? html "data-debug=\"true\""))
      (is (not (str/includes? html "data-gone")))
      (is (str/includes? html "nonce=\"abc123\""))))
  (testing "a nonce function gets the request"
    (is (str/includes? (privatus/tag (assoc config :nonce (fn [req] (:nonce req))) {:request {:nonce "n1"}})
                       "nonce=\"n1\""))))

(deftest page-props-in-tag-test
  (testing "inline script before the tag and data-props on it"
    (is (= (str "<script>window.privatusPageProps=function(){return {\"plan\":\"pro\",\"role\":\"admin\"}}</script>\n"
                "<script defer src=\"https://privatusstats.com/js/pa.js\" data-site=\"pa_TEST123456\" "
                "data-props=\"privatusPageProps\"></script>")
           (privatus/tag config {:props {:plan "pro" :role "admin"}}))))
  (testing "the props script gets the nonce"
    (is (str/starts-with? (privatus/tag config {:props {:plan "pro"} :nonce "abc"})
                          "<script nonce=\"abc\">window.privatusPageProps=")))
  (testing "empty props print no script"
    (is (= basic (privatus/tag config {:props {:plan nil}}))))
  (testing "configured props can be a map"
    (is (str/includes? (privatus/tag (assoc config :props {:plan "pro"})) "{\"plan\":\"pro\"}")))
  (testing "configured props can be a function of the request"
    (is (str/includes? (privatus/tag (assoc config :props (fn [req] {:plan (:plan req)})) {:request {:plan "team"}})
                       "{\"plan\":\"team\"}")))
  (testing "a props function returning nil means no props"
    (is (= basic (privatus/tag (assoc config :props (constantly nil))))))
  (testing "per call props merge over configured props and nil removes one"
    (is (str/includes? (privatus/tag (assoc config :props {:plan "pro" :role "admin"}) {:props {:role nil :tier "gold"}})
                       "{\"plan\":\"pro\",\"tier\":\"gold\"}")))
  (testing "the props function is not called when the tag is off"
    (is (= "" (privatus/tag {:site-id "pa_TEST123456" :enabled false
                             :props (fn [_] (throw (ex-info "should not run" {})))})))))

(deftest queue-stub-test
  (testing "the stub is the official one"
    (is (= (str "window.privatus=window.privatus||{q:[]};['track','pageview','props','optOut','optIn','ready']"
                ".forEach(function(m){window.privatus[m]=window.privatus[m]||function(){window.privatus.q.push("
                "[m,Array.prototype.slice.call(arguments)])}})")
           privatus/queue-stub)))
  (testing "prints first, then props, then the tracker"
    (let [[a b c] (str/split-lines (privatus/tag (assoc config :queue-stub true) {:nonce "abc" :props {:plan "pro"}}))]
      (is (= (str "<script nonce=\"abc\">" privatus/queue-stub "</script>") a))
      (is (str/includes? b "privatusPageProps"))
      (is (str/includes? c "/js/pa.js"))))
  (testing "can be switched on per call"
    (is (str/includes? (privatus/tag config {:queue-stub true}) "q:[]"))
    (is (not (str/includes? (privatus/tag config) "q:[]")))))

;; --- event and click attributes ---------------------------------------------

(deftest event-attrs-test
  (testing "an event becomes data attributes"
    (is (= {:data-privatus-event "Export CSV" :data-privatus-prop-format "csv" :data-privatus-prop-source "toolbar"}
           (privatus/event-attrs "Export CSV" {:format "csv" :source "toolbar"}))))
  (testing "the name is trimmed and cut to 120 characters"
    (is (= "Export CSV" (:data-privatus-event (privatus/event-attrs "  Export CSV  "))))
    (is (= (apply str (repeat 120 "a")) (:data-privatus-event (privatus/event-attrs (apply str (repeat 200 "a")))))))
  (testing "an empty name gives nothing"
    (is (= {} (privatus/event-attrs "   " {:format "csv"})))
    (is (= {} (privatus/event-attrs nil))))
  (testing "prop keys are lowercased, cleaned and cut to 64 characters"
    (let [attrs (privatus/event-attrs "Saved" {"Report Type" "a" "x-y" "b" (apply str (repeat 80 "K")) "c"})]
      (is (= "a" (:data-privatus-prop-report_type attrs)))
      (is (= "b" (:data-privatus-prop-x_y attrs)))
      (is (= "c" (get attrs (keyword (str "data-privatus-prop-" (apply str (repeat 64 "k")))))))))
  (testing "values become strings and nil values are dropped"
    (let [attrs (privatus/event-attrs "Saved" {:on true :off false :count 3 :ratio 1.5 :tags ["a" nil "b"]
                                               :kw :pro :gone nil})]
      (is (= ["true" "false" "3" "1.5" "a,b" "pro"]
             (map attrs [:data-privatus-prop-on :data-privatus-prop-off :data-privatus-prop-count
                         :data-privatus-prop-ratio :data-privatus-prop-tags :data-privatus-prop-kw])))
      (is (not (contains? attrs :data-privatus-prop-gone)))))
  (testing "values are cut to 500 characters and at most 30 props are kept"
    (is (= 31 (count (privatus/event-attrs "Saved" (into {} (for [i (range 40)] [(str "p" i) "v"]))))))
    (is (= 500 (count (:data-privatus-prop-long (privatus/event-attrs "Saved" {:long (apply str (repeat 900 "x"))}))))))
  (testing "cuts never split a character in two"
    (let [v (:data-privatus-prop-e (privatus/event-attrs "Saved" {:e (apply str (repeat 600 "😀"))}))]
      (is (= 500 (.codePointCount ^String v 0 (count v)))))))

(deftest event-attrs-html-test
  (is (= "data-privatus-event=\"Say &quot;hi&quot; &lt;now&gt;\" data-privatus-prop-note=\"a&amp;b\""
         (privatus/event-attrs-html "Say \"hi\" <now>" {:note "a&b"})))
  (is (= "" (privatus/event-attrs-html ""))))

(deftest click-attr-test
  (is (= {:data-privatus-click "pricing.cta"} (privatus/click-attr "pricing.cta")))
  (is (= {:data-privatus-click "a-b_c:d.1"} (privatus/click-attr "a-b_c:d.1")))
  (is (= "data-privatus-click=\"pricing.cta\"" (privatus/click-attr-html "pricing.cta")))
  (doseq [bad ["" "has space" (apply str (repeat 101 "x")) "<b>" nil 42]]
    (is (= {} (privatus/click-attr bad)))
    (is (= "" (privatus/click-attr-html bad))))
  (testing "an invalid name warns"
    (let [messages (atom [])]
      (binding [privatus/*warn* #(swap! messages conj %)]
        (privatus/click-attr "has space"))
      (is (= 1 (count @messages)))
      (is (str/includes? (first @messages) "\"has space\" is not valid")))))

;; --- page props JSON --------------------------------------------------------

(deftest page-props-json-test
  (testing "props become a JSON object"
    (is (= "{\"plan\":\"pro\",\"role\":\"admin\"}" (privatus/page-props-json {:plan "pro" :role "admin"}))))
  (testing "empty props give nil"
    (is (nil? (privatus/page-props-json {})))
    (is (nil? (privatus/page-props-json nil)))
    (is (nil? (privatus/page-props-json {:gone nil}))))
  (testing "numbers, booleans and lists keep their JSON types"
    (is (= "{\"seats\":5,\"ratio\":1.5,\"beta\":true,\"old\":false,\"flags\":[\"a\",\"b\",3]}"
           (privatus/page-props-json (array-map :seats 5 :ratio 1.5 :beta true :old false :flags ["a" nil "b" 3])))))
  (testing "other number types"
    (is (= "{\"big\":12345678901234567890,\"half\":0.5,\"dec\":2.50}"
           (privatus/page-props-json (array-map :big 12345678901234567890N :half 1/2 :dec 2.50M)))))
  (testing "NaN and infinity are dropped"
    (is (nil? (privatus/page-props-json {:a Double/NaN :b Double/POSITIVE_INFINITY}))))
  (testing "keys are cleaned like event props"
    (is (= "{\"app_version\":\"2.1\"}" (privatus/page-props-json {"App Version" "2.1"}))))
  (testing "limits props, list items and value length"
    (is (= 30 (count (re-seq #"\"p\d+\"" (privatus/page-props-json (into {} (for [i (range 40)] [(str "p" i) i])))))))
    (is (= 30 (count (re-seq #"\"\d+\"" (privatus/page-props-json {:list (map str (range 50))})))))
    (is (str/includes? (privatus/page-props-json {:long (apply str (repeat 900 "x"))})
                       (str "\"" (apply str (repeat 500 "x")) "\""))))
  (testing "the JSON is safe inside a script tag"
    (let [json (privatus/page-props-json {:note "</script><b>&\u2028\u2029"})]
      (is (= "{\"note\":\"\\u003c/script\\u003e\\u003cb\\u003e\\u0026\\u2028\\u2029\"}" json))
      (is (not (re-find #"[<>&\u2028\u2029]" json)))))
  (testing "quotes, backslashes and control characters are escaped"
    (is (= "{\"q\":\"a\\\"b\\\\c\\nd\\u0001\"}" (privatus/page-props-json {:q "a\"b\\c\nd\u0001"}))))
  (testing "emoji pass through, a lone surrogate is escaped"
    (is (= "{\"e\":\"😀\",\"s\":\"\\ud800\"}" (privatus/page-props-json (array-map :e "😀" :s "\ud800"))))))
