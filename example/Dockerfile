# Conformance example app (Ring + Hiccup). Build with the package folder as
# the context: docker build -f example/Dockerfile .
FROM clojure:temurin-21-tools-deps-alpine
# The library from this repo's own source (a :local/root dependency).
WORKDIR /pkg
COPY deps.edn ./
COPY src/ src/
COPY example/ example/
WORKDIR /pkg/example
RUN clojure -P -M -m example.main
EXPOSE 8080
CMD ["clojure", "-M", "-m", "example.main"]
