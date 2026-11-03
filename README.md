# MI cloud samples

Minimal WSO2 Integrator: MI 4.6.0 projects for end-to-end platform testing.

| Project | What it is | Ports |
|---|---|---|
| `mi-mcp-server/` | `GreetingAPI` REST API plus an MCP server (`GreetingMCPServer` inbound endpoint) that exposes `GET /greeting/hello/{name}` as the MCP tool `greet`. | 8290 (REST), 8300 (MCP, `/mcp`) |
| `mi-ai-agent/` | `AgentAPI` REST API that runs an `ai.agent` (OpenAI, `gpt-4o-mini`, file chat memory). | 8290 |
| `mi-automation/` | Automation: `HeartbeatAutomation` sequence set as the automation (main) sequence. MI runs it once and shuts down. | none |
| `mi-file-integration/` | Self-contained file integration: `GenerateOrderTask` writes an order JSON to `/tmp/mi-files/inbox` every 30 s, and the `OrderFileListener` file inbound endpoint summarizes each one into `outbox/` (File connector 5.0.5). No endpoints, no network. | none |
| `mi-event-integration/` | Event integration: `OrderEventListener` RabbitMQ inbound endpoint consumes the `orders` queue and logs each event. | none |
| `mi-weather-data-listener/` | File integration: MI port of the Ballerina [`weather-data-listener`](https://github.com/wso2/integration-samples/tree/main/integrator-default-profile/samples/weather-data-listener) sample. `WeatherDataListener` (File Event Listener `mi-inbound-file` 0.9.3) polls the NOAA FTP server and logs each updated weather data file. | none |
| `mi-jms-event-integration/` | Self-contained event integration: an embedded ActiveMQ broker runs inside MI. `OrderEventGeneratorTask` (every 30 s) and `OrderAPI` publish to the `orders` JMS queue; `OrderEventListener` (JMS inbound endpoint) consumes it. No external broker or outbound traffic. | 8290 (optional) |

## Build

```sh
docker run --rm -v "$PWD/mi-mcp-server":/src -w /src maven:3.9.9-eclipse-temurin-21 \
  mvn --batch-mode --no-transfer-progress clean install -Dmaven.test.skip=true
# -> mi-mcp-server/target/MIMcpServer_1.0.0.car
# Same for the others: MIAiAgent, MIAutomation, MIFileIntegration, MIEventIntegration, MIWeatherDataListener,
# MIJmsEventIntegration (<artifactId>_1.0.0.car)
```

## Run locally

```sh
docker run --rm -p 8290:8290 -p 8300:8300 \
  -v "$PWD/mi-mcp-server/target/MIMcpServer_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIMcpServer_1.0.0.car \
  wso2/wso2mi:4.6.0
```

For the agent, also pass `-e OPENAI_API_KEY=sk-...`.

## mi-mcp-server

REST:

```sh
curl http://localhost:8290/greeting/hello/World   # {"message":"Hello, World!","source":"mi-mcp-server"}
curl http://localhost:8290/greeting/time          # {"time":"2026-10-15 06:56:17"}
```

MCP: the `mi-inbound-mcp` 1.0.0 listener uses the MCP `2024-11-05` **HTTP+SSE** transport, not stateless
streamable HTTP. A plain `POST /mcp` returns `Missing sessionId: open an SSE stream first via GET /mcp`.

1. `GET /mcp` with `Accept: text/event-stream` and keep the stream open. The first event is
   `event: endpoint` / `data: http://<host>/mcp?sessionId=<uuid>`.
2. POST JSON-RPC messages (`initialize`, `notifications/initialized`, `tools/list`,
   `tools/call` with `{"name":"greet","arguments":{"name":"Bob"}}`) to that URL. Each POST returns
   `202`, and the JSON-RPC response arrives as an `event: message` on the SSE stream.

Any MCP client that supports the SSE transport works, for example MCP Inspector with transport "SSE"
and URL `http://localhost:8300/mcp`.

`.choreo/component.yaml` declares one endpoint, `mcp` (port 8300, basePath `/mcp`, REST, Public).

## mi-ai-agent

```sh
curl http://localhost:8290/agent/health
# {"status":"ok"}   (no LLM key needed)

curl -X POST http://localhost:8290/agent/ -H 'Content-Type: application/json' \
  -d '{"userID":"u1","query":"Say hi"}'
# With a valid key: the agent's answer (the ai.agent response object, overwriteBody=true).
# With a missing or invalid key: HTTP 500 {"error":"AI:ERROR_CHAT_COMPLETION","code":"701174"}
```

The OpenAI key is a configurable: `config.properties` declares `OPENAI_API_KEY:string`, and the
`OPENAI_CONN` local entry uses `{${configs.OPENAI_API_KEY}}`. Set the `OPENAI_API_KEY` environment
variable to supply it. If it is unset, the CApp still deploys and `/agent/health` still works, but
MI logs `ERROR {ConfigDeployer} - The value of the key:[OPENAI_API_KEY] is not found.`

Chat memory (`FILE_MEMORY_CONN`) is written to
`<MI_HOME>/registry/governance/ai/chat-memory/FILE_MEMORY_CONN.json`, so the MI home must be writable.

## mi-automation

`pom.xml` sets `<mainSequence>HeartbeatAutomation</mainSequence>` (what "Set as automation sequence" in
MI for VS Code writes), so the CApp's `artifacts.xml` carries `mainSequence="HeartbeatAutomation"`.
Start MI with `--car <CApp name>` to run in automation mode:

```sh
docker run --rm \
  -v "$PWD/mi-automation/target/MIAutomation_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIAutomation_1.0.0.car \
  wso2/wso2mi:4.6.0 --car MIAutomation
```

Expected log lines, then a graceful shutdown and exit code 0:

```
INFO {LogMediator} - HeartbeatAutomation started
INFO {LogMediator} - HeartbeatAutomation result: {"job":"HeartbeatAutomation","source":"mi-automation","startedAt":"2026-10-16T07:19:55","status":"ok"}
INFO {LogMediator} - HeartbeatAutomation completed
INFO {CoreServerInitializer} - Gracefully shutting down WSO2 Micro Integrator...
```

Without `--car`, MI starts in normal server mode and the sequence does not run.

## mi-file-integration

A file integration that needs no network access and no endpoints (the cloud `file-integration`
type rejects components with endpoints), so it can be deployed as is to test the type. It feeds
itself:

1. `GenerateOrderTask` runs every 30 s (first run at startup) and `GenerateOrderFile` writes
   `inbox/order-<yyyyMMdd-HHmmss>.json` with the File connector. One file in ten (the first file of
   every fifth minute) is malformed: it has no `items`.
2. The `OrderFileListener` file inbound endpoint polls `inbox/` every 2 s. `ProcessOrderFile` writes
   a summary to `outbox/` with the same name, and the input file is moved to `processed/`.
3. A malformed order fails in `ProcessOrderFile`. `OrderFileError` writes an error record to
   `failed/<name>`, and the input file is still moved to `processed/`.

All paths are under `/tmp/mi-files`, which is writable even with a read-only root filesystem. The
File connector creates `inbox/` and `outbox/` on the first write, and the listener creates
`processed/` (`transport.vfs.CreateFolder=true`). Until the first file is generated, the listener logs
`WARN {FilePollingConsumer} - Unable to access or read file or directory : file:///tmp/mi-files/inbox`.
This happens once at startup.

The directories live in the pod, so their contents are lost when the pod restarts. With more than one
replica, each pod generates and processes its own files.

```sh
docker run --rm \
  -v "$PWD/mi-file-integration/target/MIFileIntegration_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIFileIntegration_1.0.0.car \
  wso2/wso2mi:4.6.0
```

Expected logs, every 30 s:

```
INFO {LogMediator} - Generated inbox/order-20261102-092724.json (valid)
INFO {LogMediator} - {inboundendpoint:OrderFileListener} Picked up order file order-20261102-092724.json
INFO {LogMediator} - {inboundendpoint:OrderFileListener} Wrote order summary /tmp/mi-files/outbox/order-20261102-092724.json: {"orderId":"20261102-092724","itemCount":2,"total":45,"sourceFile":"order-20261102-092724.json"}
```

and, for a malformed file:

```
INFO {LogMediator} - Generated inbox/order-20261102-093024.json (malformed)
INFO {LogMediator} - {inboundendpoint:OrderFileListener} Picked up order file order-20261102-093024.json
ERROR {PayloadFactoryMediator} - {inboundendpoint:OrderFileListener} Error parsing FreeMarker template, Syntax error or invalid reference : payload.items At line: 1 column: 27
ERROR {LogMediator} - {inboundendpoint:OrderFileListener} Failed to process order file order-20261102-093024.json: Error parsing FreeMarker template, ...
```

`failed/order-20261102-093024.json`:

```json
{
    "sourceFile": "order-20261102-093024.json",
    "error": "Error parsing FreeMarker template, Syntax error or invalid reference : payload.items At line: 1 column: 27"
}
```

## mi-weather-data-listener

MI port of the Ballerina
[`weather-data-listener`](https://github.com/wso2/integration-samples/tree/main/integrator-default-profile/samples/weather-data-listener)
sample. It needs outbound FTP (port 21 plus passive data ports) to `tgftp.nws.noaa.gov`, and has no
endpoints.

`WeatherDataListener` uses the File Event Listener (`mi-inbound-file` 0.9.3, class
`org.wso2.carbon.inbound.vfs.VFSConsumer`) instead of the built-in `file` inbound endpoint. It polls
`ftp://anonymous:@tgftp.nws.noaa.gov/data/observations/metar/cycles/` every 60 s for `*.TXT`, and
`ProcessWeatherFile` logs the file name, size and first report.

Differences from the Ballerina sample:

| | Ballerina | MI |
|---|---|---|
| Directory | `/data/observations/metar/decoded/` (one file per station, ~11k files) | `/data/observations/metar/cycles/` (one file per UTC hour, 24 files of ~2 MB) |
| Which files are processed | Files whose names were not in the previous poll (`addedFiles`) | Files modified in the last 120 s |
| Logged | First line (station name) | File name, size, and first report (timestamp + METAR line) |

Why:

- **Read-only source.** NOAA is anonymous, read-only FTP. MI file listeners normally track progress by
  moving or deleting each processed file, and the built-in `file` inbound endpoint has no other mode:
  the delete fails, and so does writing the `<file>.fail` marker, so every file is processed again on
  every poll. The File Event Listener accepts `transport.vfs.ActionAfterProcess=NONE`, but it does not
  remember files it has seen.
- **Age window instead of name tracking.** `transport.vfs.MinimumAge=120` limits each poll to files
  modified in the last 120 s. In 0.9.3, `MinimumAge` accepts files whose age is *at most* the value
  (the opposite of its description), and this sample relies on that. FTP listings only give the
  modification time to the minute, so the window is twice the poll interval: an updated file is
  logged once or twice and is not missed. The current hour's file is updated every few minutes, so
  expect about one log line per poll.
- **`decoded/` is too large for the listener.** For every entry, the 0.9.3 consumer calls
  `getType()`, and commons-vfs re-lists the whole FTP directory, so one poll of the ~11k-file
  `decoded/` directory does not finish. `cycles/` has 24 entries.
- **`transport.vfs.CheckSizeInterval=0`.** The default size check downloads every matching file
  (twice) to confirm that it has stopped changing. `0` turns it off.

`coordination=true` keeps one active poller when there is more than one replica.

```sh
docker run --rm \
  -v "$PWD/mi-weather-data-listener/target/MIWeatherDataListener_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIWeatherDataListener_1.0.0.car \
  wso2/wso2mi:4.6.0
```

The first poll runs about 30 s after startup, then every 60 s:

```
INFO {LogMediator} - {inboundendpoint:WeatherDataListener} Received weather data file 07Z.TXT (1609171 bytes). First report: 2026/11/02 06:55 KCPF 020655Z AUTO 00000KT 10SM OVC048 11/11 A3034 RMK AO2
```

A poll with no recently updated file logs nothing.

## mi-event-integration

The inbound endpoint reads the broker connection from environment variables through `$SYSTEM:`
(inbound endpoint parameters do not resolve `${configs.X}` or `$config:X`; both fail with
`Number format error in port number`):

| Variable | Example |
|---|---|
| `RABBITMQ_HOST` | `rabbitmq` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |

The `orders` queue is declared (durable) if it does not exist. If the broker is unreachable, MI keeps
retrying every 30 s.

```sh
docker network create mi-events
docker run -d --name rabbitmq --network mi-events -p 15672:15672 rabbitmq:4.1-management
docker run --rm --network mi-events \
  -e RABBITMQ_HOST=rabbitmq -e RABBITMQ_PORT=5672 -e RABBITMQ_USERNAME=guest -e RABBITMQ_PASSWORD=guest \
  -v "$PWD/mi-event-integration/target/MIEventIntegration_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIEventIntegration_1.0.0.car \
  wso2/wso2mi:4.6.0

# Publish through the RabbitMQ management API
curl -u guest:guest -H 'content-type: application/json' \
  -X POST http://localhost:15672/api/exchanges/%2F/amq.default/publish \
  -d '{"properties":{"content_type":"application/json"},"routing_key":"orders","payload":"{\"orderId\":\"2002\",\"amount\":4999.99}","payload_encoding":"string"}'
# {"routed":true}
```

MI logs:

```
INFO {LogMediator} - {inboundendpoint:OrderEventListener} Order event received: orderId=2002, amount=4999.99
INFO {LogMediator} - {inboundendpoint:OrderEventListener} High-value order 2002 flagged for review
```

Orders with `amount` > 1000 get the extra "flagged for review" line.

Known MI 4.6.0 behavior: a message that is not valid JSON (with `content_type: application/json`)
fails in `JsonStreamBuilder` before mediation, and the RabbitMQ consumer stops. The queue then shows
0 consumers, and MI must be restarted to consume again. Publish only valid JSON when testing.

## mi-jms-event-integration

An event integration that needs no external broker, so it works where outbound traffic is blocked.
The JMS connection URL `vm://localhost?broker.persistent=false` makes ActiveMQ start an in-memory
broker inside the MI JVM on first use. The producer (`OrderQueueEP`) and the consumer
(`OrderEventListener`) use the same URL, so they share that broker.

| Piece | What it does |
|---|---|
| `OrderEventGeneratorTask` | Every 30 s, injects into `GenerateOrderEvent`, which publishes `{"orderId":"auto-<ms>","amount":<0..1800>}`. Events flow with no client at all. |
| `OrderAPI` | `POST /orders` publishes the request body to the queue and returns `202`. |
| `OrderEventListener` | JMS inbound endpoint on the `orders` queue. `ProcessOrderEvent` logs the event and flags `amount` > 1000 (same logic as `mi-event-integration`). |

Runtime requirements, both handled by the project:

- **`activemq-all-6.1.6.jar` in `MI_HOME/lib`.** It is a pom dependency, so the build copies it to
  `deployment/libs`, and the image build copies that to `lib/`. It must be the all-in-one jar:
  MI turns each `lib` jar into its own OSGi bundle, and with separate `activemq-client` and
  `activemq-broker` jars the client fails with `Transport scheme NOT recognized: [vm]`.
- **`[transport.jms] sender_enable = true` in `deployment/deployment.toml`.** No named sender is
  defined: the endpoint URI carries the JNDI settings and `transport.jms.JMSSpecVersion=3.1`
  (required for ActiveMQ 6, which uses Jakarta JMS). A named `[[transport.jms.sender]]` without
  `parameter.jms_spec_version = "3.1"` stops MI at startup with
  `Cannot cast org.apache.activemq.ActiveMQConnectionFactory to javax.jms.ConnectionFactory`.

The broker is non-persistent and writes nothing to disk, so queued messages are lost on restart.

```sh
H=/home/wso2carbon/wso2mi-4.6.0
docker run --rm -p 8290:8290 \
  -v "$PWD/mi-jms-event-integration/target/MIJmsEventIntegration_1.0.0.car":$H/repository/deployment/server/carbonapps/MIJmsEventIntegration_1.0.0.car \
  -v "$PWD/mi-jms-event-integration/deployment/deployment.toml":$H/conf/deployment.toml \
  -v "$PWD/mi-jms-event-integration/deployment/libs/activemq-all-6.1.6.jar":$H/lib/activemq-all-6.1.6.jar \
  wso2/wso2mi:4.6.0

curl -i -X POST http://localhost:8290/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"2002","amount":4999.99}'
# HTTP/1.1 202 Accepted
```

MI logs:

```
INFO {LogMediator} - Publishing generated order event: {"orderId":"auto-1793678467353","amount":900}
INFO {BrokerService} - Apache ActiveMQ 6.1.6 (localhost, ID:...) started
INFO {LogMediator} - {inboundendpoint:OrderEventListener} Order event received: orderId=auto-1793678467353, amount=900.0
INFO {LogMediator} - {api:OrderAPI POST /orders} Publishing order event: {"orderId":"2002","amount":4999.99}
INFO {LogMediator} - {inboundendpoint:OrderEventListener} Order event received: orderId=2002, amount=4999.99
INFO {LogMediator} - {inboundendpoint:OrderEventListener} High-value order 2002 flagged for review
```

## product-samples

Unmodified copies of samples from
[`wso2/product-integrator-mi/samples`](https://github.com/wso2/product-integrator-mi/tree/master/samples)
(commit `6d20322`), one per MI integration type, for testing project import in the console.

| Project | Integration type | What it is |
|---|---|---|
| `product-samples/HelloWorldService/` | Integration as API | `HelloWorld` API: `GET /HelloWorld` returns `{"Hello":"World"}`. |
| `product-samples/PeriodicalScheduledTasks/` | Automation | `PersonRecordRetrieveTask` runs 5 times, 30 s apart, and calls an external SOAP `FindPerson` service. |
| `product-samples/FileTransfer/` | File integration | `StudentDataFileProcessInboundEP` polls `file:///home/user/in` for `*.csv` and moves files to `out/` or `error/`. |
| `product-samples/RabbitMQIntegration/` | Event integration | `SalesOrderQueueInboundEP` consumes `SalesOrderQueue` (port 5672); `SalesOrderAddService` proxy publishes to it. |

These are not adapted for the cloud: the file paths, broker host and SOAP backend are the upstream
defaults, so the integrations may fail at runtime even when import and build succeed.
