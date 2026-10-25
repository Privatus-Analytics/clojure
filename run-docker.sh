#!/bin/sh
# Runs the tests (Clojure 1.11 and 1.12), clj-kondo, cljfmt and the jar build
# in the official Clojure image. Usage: ./run-docker.sh
# Downloads are cached in ./.cache so later runs are faster.
set -e
mkdir -p .cache/m2 .cache/gitlibs
docker run --rm -v "$PWD":/app -w /app \
  -v "$PWD/.cache/m2":/root/.m2 -v "$PWD/.cache/gitlibs":/root/.gitlibs \
  clojure:temurin-21-tools-deps sh -c 'set -e
  echo "--- tests on Clojure 1.11"
  clojure -M:test
  echo "--- tests on Clojure 1.12"
  clojure -M:1.12:test
  echo "--- clj-kondo"
  clojure -M:lint
  echo "--- cljfmt"
  clojure -M:fmt check src test build.clj
  echo "--- jar"
  clojure -T:build jar
  rm -rf target .cpcache
'
