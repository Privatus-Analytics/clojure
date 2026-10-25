(ns privatus.analytics.hiccup-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [privatus.analytics :as privatus]
            [privatus.analytics.hiccup :as ph]))

(def config {:site-id "pa_TEST123456" :enabled true})

(deftest tag-as-hiccup
  (testing "the tracker element"
    (is (= [[:script {:defer true :src "https://privatusstats.com/js/pa.js" :data-site "pa_TEST123456"}]]
           (ph/tag config))))
  (testing "nothing when off"
    (is (nil? (ph/tag (assoc config :enabled false))))))

(deftest renders-with-hiccup-2
  (let [html (str (h/html [:head (ph/tag config {:props {:plan "pro" :note "</script>"} :nonce "abc"})]))]
    (testing "inline code is not escaped twice"
      (is (str/includes? html (str "<script nonce=\"abc\">window.privatusPageProps=function(){return "
                                   "{\"plan\":\"pro\",\"note\":\"\\u003c/script\\u003e\"}}</script>"))))
    (is (str/includes? html "data-props=\"privatusPageProps\""))
    (is (str/includes? html "src=\"https://privatusstats.com/js/pa.js\""))))

(deftest event-attrs-in-hiccup
  (is (= "<button data-privatus-event=\"Export CSV\" data-privatus-prop-format=\"csv\">Export</button>"
         (str (h/html [:button (privatus/event-attrs "Export CSV" {:format "csv"}) "Export"]))))
  (testing "values are escaped by Hiccup"
    (is (str/includes? (str (h/html [:a (ph/event-attrs "Say \"hi\"" {:x "<b>"}) "x"]))
                       "data-privatus-event=\"Say &quot;hi&quot;\" data-privatus-prop-x=\"&lt;b&gt;\""))))

(deftest with-event-and-with-click
  (is (= [:button {:class "btn" :data-privatus-event "Report exported" :data-privatus-prop-format "csv"} "Export"]
         (ph/with-event [:button {:class "btn"} "Export"] "Report exported" {:format "csv"})))
  (is (= [:form {:data-privatus-event "Newsletter joined"} [:input]]
         (ph/with-event [:form [:input]] "Newsletter joined")))
  (is (= [:a {:href "/pricing" :data-privatus-click "pricing.cta"} "See plans"]
         (ph/with-click [:a {:href "/pricing"} "See plans"] "pricing.cta")))
  (binding [privatus/*warn* (constantly nil)]
    (is (= [:a {:href "/"} "x"] (ph/with-click [:a {:href "/"} "x"] "bad name")))))

(deftest rum-tag-test
  (is (= [[:script {:dangerouslySetInnerHTML {:__html privatus/queue-stub} :nonce "n"}]
          [:script {:defer true :src "https://privatusstats.com/js/pa.js" :data-site "pa_TEST123456" :nonce "n"}]]
         (ph/rum-tag config {:queue-stub true :nonce "n"}))))
