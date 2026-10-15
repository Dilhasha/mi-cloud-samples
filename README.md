# MI cloud samples

Two minimal WSO2 Integrator: MI 4.6.0 projects for end-to-end platform testing.

| Project | What it is | Ports |
|---|---|---|
| `mi-mcp-server/` | `GreetingAPI` REST API plus an MCP server (`GreetingMCPServer` inbound endpoint) that exposes `GET /greeting/hello/{name}` as the MCP tool `greet`. | 8290 (REST), 8300 (MCP, `/mcp`) |
| `mi-ai-agent/` | `AgentAPI` REST API that runs an `ai.agent` (OpenAI, `gpt-4o-mini`, file chat memory). | 8290 |

## Build

```sh
docker run --rm -v "$PWD/mi-mcp-server":/src -w /src maven:3.9.9-eclipse-temurin-21 \
  mvn --batch-mode --no-transfer-progress clean install -Dmaven.test.skip=true
# -> mi-mcp-server/target/MIMcpServer_1.0.0.car   (same for mi-ai-agent -> MIAiAgent_1.0.0.car)
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
