# Changelog

## Unreleased

- When the tag is off (not enabled or no site id) and `:queue-stub` is on, `tag`
  and the Hiccup `tag` print only the queue stub, so `privatus.track` calls never
  fail. No tracker and no page props script print then, and a `:props` function is
  not called. `tag-parts` gives `:attrs nil` in that case.
- Add the conformance example app in `example/` (Ring and Hiccup).
- README: a privacy policy paragraph you can paste.

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
