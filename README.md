# MI cloud samples

Minimal WSO2 Integrator: MI 4.6.0 projects for end-to-end platform testing.

| Project | What it is | Ports |
|---|---|---|
| `mi-mcp-server/` | `GreetingAPI` REST API plus an MCP server (`GreetingMCPServer` inbound endpoint) that exposes `GET /greeting/hello/{name}` as the MCP tool `greet`. | 8290 (REST), 8300 (MCP, `/mcp`) |
| `mi-ai-agent/` | `AgentAPI` REST API that runs an `ai.agent` (OpenAI, `gpt-4o-mini`, file chat memory). | 8290 |
| `mi-automation/` | Automation: `HeartbeatAutomation` sequence set as the automation (main) sequence. MI runs it once and shuts down. | none |
| `mi-file-integration/` | File integration: `OrderFileListener` file inbound endpoint polls `/tmp/mi-files/inbox`, summarizes each order JSON into `outbox/` (File connector 5.0.5). `FileAPI` drops and reads files over HTTP. | 8290 |
| `mi-event-integration/` | Event integration: `OrderEventListener` RabbitMQ inbound endpoint consumes the `orders` queue and logs each event. | none |

## Build

```sh
docker run --rm -v "$PWD/mi-mcp-server":/src -w /src maven:3.9.9-eclipse-temurin-21 \
  mvn --batch-mode --no-transfer-progress clean install -Dmaven.test.skip=true
# -> mi-mcp-server/target/MIMcpServer_1.0.0.car
# Same for the others: MIAiAgent, MIAutomation, MIFileIntegration, MIEventIntegration (<artifactId>_1.0.0.car)
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

All paths are under `/tmp/mi-files` (writable even with a read-only root filesystem):

| Directory | Contents |
|---|---|
| `inbox/` | Polled every 2 s for `*.json`. |
| `outbox/` | One summary per processed order, same file name. |
| `processed/` | Every picked-up input file is moved here (success or failure). |
| `failed/` | An error record for each input that could not be processed. |

The `InitFileDirsTask` task runs once at startup and creates the four directories with the File
connector. The listener can poll before the task finishes, so one
`WARN {FilePollingConsumer} - Unable to access or read file or directory : file:///tmp/mi-files/inbox`
at startup is expected.

```sh
docker run --rm -p 8290:8290 \
  -v "$PWD/mi-file-integration/target/MIFileIntegration_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/MIFileIntegration_1.0.0.car \
  wso2/wso2mi:4.6.0

# Drop an order into inbox/ (or copy a file there directly)
curl -X POST http://localhost:8290/files/inbox/order-1001.json -H 'Content-Type: application/json' \
  -d '{"orderId":"1001","items":[{"sku":"A","qty":2,"price":10.5},{"sku":"B","qty":1,"price":1000}]}'
# {"accepted":"inbox/order-1001.json"}

# A couple of seconds later:
curl http://localhost:8290/files/outbox
# {"success":true,"files":{"files":[{"name":"order-1001.json","path":"/tmp/mi-files/outbox/order-1001.json"}]}}
curl http://localhost:8290/files/outbox/order-1001.json
# {"orderId":"1001","itemCount":2,"total":1021,"sourceFile":"order-1001.json"}
```

An order without `items` (for example `{"orderId":"x"}`) is moved to `processed/`, and
`failed/<name>` gets `{"sourceFile":"<name>","error":"Error parsing FreeMarker template, ..."}`.

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
