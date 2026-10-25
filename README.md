# Privatus Analytics for Clojure

Adds the cookieless [Privatus Analytics](https://privatusanalytics.com)
tracking script to your Clojure web app. You get a function that returns the
tag as a string, Hiccup helpers, and an optional Ring middleware that puts the
tag in the `<head>` for you.

Privatus Analytics counts visits, sources, pages and conversions without
cookies, local storage or fingerprinting, so analytics does not need a consent
banner by itself. Check with your own counsel for your country.

The library only prints the browser script. It never sends events from your
server and sets no cookies. It has no dependencies besides Clojure. You need a
Privatus Analytics account to see reports, and there is a free plan. Sign up
at https://privatusanalytics.com.

## Requirements

* Clojure 1.11 or later, on Java 11 or later.
* A Privatus Analytics site id (it starts with `pa_`). You find it under
  Site settings, Tracking.

## Install

Add the library to your `deps.edn`:

```clojure
com.privatusanalytics/privatus-analytics {:mvn/version "1.0.0"}
```

or with Leiningen, to `project.clj`:

```clojure
[com.privatusanalytics/privatus-analytics "1.0.0"]
```

For the full setup guide and every tracker option, see the [Privatus Analytics
docs](https://privatusanalytics.com/docs).

Set your site id with an environment variable, and tell the library it runs
in production:

```sh
PRIVATUS_SITE_ID=pa_YOURSITEID
PRIVATUS_ENV=production
```

or pass it in the config map:

```clojure
(def privatus-config {:site-id "pa_YOURSITEID"})
```

## Add the tag

The library has three namespaces:

* `privatus.analytics` builds strings and attribute maps.
* `privatus.analytics.hiccup` returns Hiccup elements.
* `privatus.analytics.ring` has the Ring middleware.

With Hiccup, put the tag in the head of your layout:

```clojure
(require '[hiccup2.core :as h]
         '[privatus.analytics.hiccup :as privatus-hiccup])

(defn layout [request & body]
  (str (h/html
        [:html
         [:head
          [:title "My app"]
          (privatus-hiccup/tag privatus-config {:request request})]
         (into [:body] body)])))
```

With any string template, use `privatus.analytics/tag`. It returns the tag as
a string:

```clojure
(require '[privatus.analytics :as privatus])

(privatus/tag privatus-config)
```

In production it prints:

```html
<script defer src="https://privatusstats.com/js/pa.js" data-site="pa_YOURSITEID"></script>
```

The tag prints nothing when there is no site id, and nothing outside
production (see below). If your app uses a Content Security Policy nonce (a
random code that lets one inline script run), pass it as `:nonce`.

### Or let the middleware add it

If you would rather not touch your layouts, wrap your Ring handler:

```clojure
(require '[privatus.analytics.ring :refer [wrap-privatus-analytics]])

(def app
  (-> handler
      (wrap-privatus-analytics {:site-id "pa_YOURSITEID"
                                :props (fn [request] {:plan (get-in request [:session :plan])})})))
```

It adds the tag just before `</head>` in uncompressed `text/html` responses with
status 200. It skips Ajax requests (`X-Requested-With: XMLHttpRequest`), pages
without a `<head>`, and pages that already have the tag. It reads String,
InputStream, File, byte array and seq bodies, and sets `Content-Length` to the
new size. Other bodies, like streaming functions, are left alone. Responses
that carry a `Content-Encoding` header are left alone too, so if you compress
in your Ring stack (for example with `ring.middleware.gzip`), put this
middleware inside it, closer to your handler. It works with sync and async
Ring handlers.

In the middleware, `:props` and `:nonce` may be functions. They get the Ring
request.

## Configuration

Every setting is optional except the site id. The same map works for
`privatus.analytics/tag`, `privatus.analytics.hiccup/tag` and the middleware.

```clojure
(def privatus-config
  {:site-id          "pa_YOURSITEID"               ; or PRIVATUS_SITE_ID
   :script-host      "https://privatusstats.com"   ; or PRIVATUS_SCRIPT_HOST
   :modules          ["engage" "auto" "vitals" "clicks" "ask"]
   :excluded-paths   ["/admin/*" "/preview/*"]
   :domains          ["example.com" "www.example.com"]
   :spa              "auto"     ; auto, history, hash or off
   :manual           false      ; true turns off the automatic pageview
   :enabled          nil        ; true or false forces the tag on or off
   :extra-attributes {"data-mask" "/orders/*->/orders/:id"}
   :props            (fn [request] {:plan (get-in request [:session :plan])})
   :queue-stub       false
   :nonce            nil})
```

| Key | Default | What it does |
|---|---|---|
| `:site-id` | `PRIVATUS_SITE_ID` | The `data-site` attribute. Nothing prints without it |
| `:script-host` | `https://privatusstats.com` | Where `/js/pa.js` is served. Change it only if you host your own copy. Also read from `PRIVATUS_SCRIPT_HOST` |
| `:modules` | tracker default (`engage`) | The `data-modules` attribute. Setting it replaces the default, so keep `engage` for time on page and scroll depth. `[]` loads none |
| `:excluded-paths` | none | The `data-exclude` attribute. Path globs the tracker never counts |
| `:domains` | all hosts | The `data-domains` attribute. Count only these hostnames |
| `:spa` | `auto` | The `data-spa` attribute (single-page app mode) |
| `:manual` | `false` | The `data-manual` attribute |
| `:enabled` | `nil` | `true` or `false` forces the tag on or off. `nil` means on only when `PRIVATUS_ENV` is `production` |
| `:auto-inject` | `true` | Middleware only. `false` turns the middleware off without removing it |
| `:extra-attributes` | `{}` | Any other `data-*` attribute |
| `:props` | `{}` | Page props sent with every pageview and event. A map, or a function that gets the request. See below |
| `:queue-stub` | `false` | Prints a small script first, so `privatus.track` calls made before the tracker loads are kept and sent later |
| `:nonce` | `nil` | Your Content Security Policy nonce, or a function that gets the request and returns it |

Lists may be vectors or comma separated strings. Keywords work as values, so
`:spa :hash` is fine.

Each tracker attribute is explained in the [script attributes
reference](https://privatusanalytics.com/docs/tracker/attributes), and the
modules in [Modules](https://privatusanalytics.com/docs/tracker/modules).

You can also override a setting for one page. The second argument takes any
config key, plus `:request` and `:props`:

```clojure
(privatus/tag privatus-config {:modules ["engage" "auto"] :excluded-paths ["/preview/*"]})
```

### Development and test

Development and test runs are not counted by default, so your own clicking
around never reaches your reports. The tag prints only when `PRIVATUS_ENV` is
`production`. If your framework has its own idea of the environment, pass it
in yourself, for example `:enabled (= profile :prod)`. To see the tag while
you build, set `:enabled true`. The tracker also ignores `localhost` unless
you pass `data-allow-local` or `data-debug` (see [Debug
mode](https://privatusanalytics.com/docs/tracker/debug)):

```clojure
{:extra-attributes {"data-debug" "true"}}
```

If `:queue-stub` is on, the tag still prints the small queue stub (and nothing
else), so `privatus.track` calls in your own JavaScript keep working. They
wait in a queue and are never sent. The Ring middleware adds nothing when the
tag is off.

### Leaving people out

Use `data-exclude` through `:excluded-paths` for admin areas. To leave out
signed-in staff, print the tag only for other people:

```clojure
(when-not (staff? request)
  (privatus-hiccup/tag privatus-config {:request request}))
```

## Selmer

Selmer escapes every value by default, so mark the tag as safe:

```clojure
(selmer.parser/render-file "layout.html"
                           {:privatus-tag (privatus/tag privatus-config {:request request})})
```

```html
<head>
  <title>My app</title>
  {{ privatus-tag|safe }}
</head>
```

Or add a Selmer tag once at start up. A custom tag's output is not escaped:

```clojure
(selmer.parser/add-tag! :privatus_analytics
  (fn [_args context]
    (privatus/tag privatus-config {:request (:request context)})))
```

```html
<head>{% privatus_analytics %}</head>
```

For event attributes, pass the HTML string and mark it safe:

```clojure
{:export-attrs (privatus/event-attrs-html "Report exported" {:format "csv"})}
```

```html
<button {{ export-attrs|safe }}>Export</button>
```

## Kit and Biff

**Kit** uses Selmer and Ring. Use the Selmer setup above, or add the
middleware in `wrap-base` in `web/middleware/core.clj`:

```clojure
(defn wrap-base [opts]
  (fn [handler]
    (cond-> ((:middleware env/defaults) handler opts)
      true (wrap-privatus-analytics {:site-id "pa_YOURSITEID"})
      true (defaults/wrap-defaults (:site-defaults-config opts)))))
```

Kit sets its own profile (`:dev`, `:test` or `:prod`). Either set
`PRIVATUS_ENV=production` on your server, or pass `:enabled true` from your
production config.

Keep it inside any compression middleware (see "Or let the middleware add
it").

**Biff** renders pages with Rum. Rum escapes plain strings, so use `rum-tag`.
It puts the inline code in `:dangerouslySetInnerHTML`:

```clojure
(require '[privatus.analytics.hiccup :as privatus-hiccup])

(defn base [ctx & body]
  (apply biff/base-html
         (assoc ctx :base/head (privatus-hiccup/rum-tag privatus-config {:request ctx}))
         body))
```

The event and click attribute maps work the same in Rum. You can also wrap
your Biff handler with `wrap-privatus-analytics` instead.

## Product analytics: see which features people use

Privatus can show which buttons, forms and features people really use. You
add a few attributes to your HTML. The tracker reads them in the browser and
sends the events. Your server still sends nothing.

### Name events well

Use a short "Noun verb" name, like `Report exported` or `Invite sent`. Put the
details in props, not in the name. So write `Report exported` with
`{:format "csv"}`, not `Report exported as CSV`. Then all exports add up in one
place, and you can split them by format.

### Events on buttons, links and forms

`event-attrs` returns an attribute map. Put it straight into a Hiccup element:

```clojure
(require '[privatus.analytics :as privatus])

[:button (privatus/event-attrs "Report exported" {:format "csv" :source "toolbar"}) "Export"]
```

It prints:

```html
<button data-privatus-event="Report exported" data-privatus-prop-format="csv" data-privatus-prop-source="toolbar">Export</button>
```

When the element already has attributes, merge them, or use `with-event`:

```clojure
[:button (merge {:class "btn"} (privatus/event-attrs "Report exported" {:format "csv"})) "Export"]

(privatus-hiccup/with-event [:button {:class "btn"} "Export"] "Report exported" {:format "csv"})
```

On a form, the event is sent when the form is submitted:

```clojure
[:form (merge {:action "/newsletter" :method "post"}
              (privatus/event-attrs "Newsletter joined" {:list "weekly"}))
 ...]
```

For string templates, `event-attrs-html` returns the same attributes as
escaped HTML.

The rules:

* The name is trimmed and cut to 120 characters. An empty name gives an empty
  map, so nothing prints.
* Prop keys are made lowercase. Anything that is not a letter, a digit or `_`
  becomes `_`, so `"Report Type"` becomes `report_type`. Keys are cut to 64
  characters.
* At most 30 props. Values become text: `true`, `false`, numbers as written,
  keywords without the colon, lists joined with commas. `nil` values are left
  out. Values are cut to 500 characters. Clojure maps with more than 8 keys do
  not keep their order, so use an `array-map` if order matters to you.
* Everything is escaped for HTML.
* The attributes print in every environment, even when the tag is off. They do
  nothing without the tracker, so your pages look the same in development and
  production.

### Click maps

A click map shows where people click on a page. Give the important parts a
name so they stay easy to find, even when the page changes:

```clojure
[:a (merge {:href "/pricing"} (privatus/click-attr "pricing.cta")) "See plans"]
(privatus-hiccup/with-click [:a {:href "/pricing"} "See plans"] "pricing.cta")
```

A name may use letters, digits and `_ . : -`, up to 100 characters. Anything
else gives an empty map, and the library prints a warning to stderr unless
`PRIVATUS_ENV` is `production`. To send the warning to your logger instead,
rebind `privatus.analytics/*warn*` to a function that takes the message.
`click-attr-html` returns the attribute as HTML.

### Page props: plan, role and other groups

Page props are sent with every pageview and event on the page. Use them to
split your reports by plan, role, app version, feature flag or account tier.

Set them for every page with a function. It runs on each request and gets the
request:

```clojure
{:site-id "pa_YOURSITEID"
 :props (fn [request]
          {:plan (get-in request [:session :plan])
           :role (get-in request [:session :role])
           :app_version "2.4"})}
```

For the function to get the request, pass it: `(privatus/tag config {:request
request})`. The middleware does this for you. A plain map works too. You can
also set or change props for one page. They are merged over the configured
ones, and `nil` removes one:

```clojure
(privatus/tag privatus-config {:request request :props {:section "billing" :role nil}})
```

The tag then prints a small script before the tracker, with your nonce when you
pass one, and points the tracker at it:

```html
<script nonce="...">window.privatusPageProps=function(){return {"plan":"pro","role":"admin"}}</script>
<script defer src="https://privatusstats.com/js/pa.js" data-site="pa_YOURSITEID" data-props="privatusPageProps"></script>
```

Keys follow the same rules as event props. Numbers and true or false stay as
they are, and lists stay lists (up to 30 items). The props are written as JSON
by the library itself, so you need no JSON library. They are escaped so they
are safe inside a script.

**Never put names, emails, user ids or anything that points to one person in
props.** Privatus counts visitors without any id. Put groups (like the plan or
the role), not people. The server also removes emails and long ids it finds.

### Track from your own JavaScript

For things that are not a click or a submit, call the tracker from your
JavaScript, as the [JavaScript API
docs](https://privatusanalytics.com/docs/tracker/javascript-api) explain:

```js
privatus.track("Feature used", { feature: "export" })
```

If your code may run before the tracker has loaded, switch on the queue stub.
It prints a tiny script before the tracker that keeps your calls in a list and
sends them once the tracker is ready:

```clojure
{:site-id "pa_YOURSITEID" :queue-stub true}
```

or for one layout: `(privatus/tag privatus-config {:queue-stub true})`.

The stub prints even when the tag is off (in development, without a site id,
or with `:enabled false`). Only the stub prints then: no tracker and no page
props. So your `privatus.track` calls never fail; they wait in the queue and
are never sent.

### See the results

* [Funnels](https://privatusanalytics.com/docs/features/funnels): how many
  people go from one step to the next.
* [Click maps](https://privatusanalytics.com/docs/features/click-maps): where
  people click.
* [Goals](https://privatusanalytics.com/docs/features/goals): turn an event
  into a goal to count conversions.
* [Events](https://privatusanalytics.com/docs/events): every custom event and
  its props.

## Privacy

The script sets no cookies and stores nothing in the browser. The event, click
and props helpers only print HTML attributes and a small script. Keep people
out of your props and event props: no names, emails or user ids. See the
[privacy documentation](https://privatusanalytics.com/docs/privacy) for what is
collected.

If you have a privacy policy, it should say which tools handle your visitors' data. You can paste this:

> This website uses [Privatus Analytics](https://privatusanalytics.com/privacy-analytics) to count visits. It
> does not use cookies and does not follow you from one website to another. Your IP address is used for a moment
> to count visits and is never stored.

Change it to fit your site. The [privacy policy generator](https://privatusanalytics.com/tools/privacy-policy-generator)
makes a longer version with your site's name in it.

## Development

The checks run in Docker so your local Java and Clojure do not matter:

```sh
./run-docker.sh
```

It runs the tests on Clojure 1.11 and 1.12, the clj-kondo linter, the cljfmt
format check, and the jar build. Without Docker:

```sh
clojure -M:test              # tests
clojure -M:lint              # clj-kondo
clojure -M:fmt check         # cljfmt (use fix to format)
clojure -T:build jar         # builds target/privatus-analytics-1.0.0.jar
```

## Contributing

Bug reports and pull requests are welcome at
https://github.com/Privatus-Analytics/clojure.

## License

MIT. See [LICENSE](LICENSE). Privatus Analytics is a product of Two Phase LLC,
Wyoming, USA.
