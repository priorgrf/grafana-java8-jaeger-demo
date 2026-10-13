# Java 8 + Jaeger client + Alloy + Grafana demo

Setup: `cp .env.example .env` and fill in your Grafana Cloud OTLP endpoint, instance ID and an API token (scope `traces:write`).
Run: `docker compose up --build`

- App: http://localhost:8080/hello, `/work` (manual nested spans), `/error` (exception span)
- Jaeger UI: http://localhost:16686
- Grafana Cloud: Explore → your Tempo datasource → service `java8-demo`

The app uses the Jaeger Java client (OpenTracing API) and posts Thrift over HTTP to Grafana Alloy (`alloy/config.alloy`, UI at http://localhost:12345), which forwards to both Grafana Cloud (OTLP/HTTP) and the local Jaeger.
A `loadgen` container generates traffic. Without Docker: `mvn package`, then
`JAEGER_ENDPOINT=http://localhost:14268/api/traces JAEGER_SAMPLER_TYPE=const JAEGER_SAMPLER_PARAM=1 java -jar target/app.jar`.
