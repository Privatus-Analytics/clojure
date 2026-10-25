# Changelog

## 1.0.0 - 2026-10-25

- Initial release.
- `privatus.analytics/tag` returns the script tag as a string.
- `event-attrs` and `event-attrs-html` for custom events on buttons, links and forms.
- `click-attr` and `click-attr-html` for click map names.
- Page props: `:props` (a map, or a function that gets the request) print the
  `privatusPageProps` script and `data-props`. The JSON is written by the library
  and is safe inside a script.
- `:queue-stub`, so `privatus.track` calls made before the tracker loads are kept.
- `privatus.analytics.hiccup`: `tag`, `rum-tag`, `with-event` and `with-click`.
- `privatus.analytics.ring/wrap-privatus-analytics` adds the tag to the head of
  HTML responses and fixes `Content-Length`.
- The tag prints only when `PRIVATUS_ENV` is `production`, unless `:enabled` says otherwise.
