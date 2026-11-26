# Task Management MCP Server (MI)

An MCP server over a small task board, built with WSO2 Micro Integrator, for trying out MCP on
the platform. It is the Micro Integrator counterpart of the Ballerina
[`task-management-mcp-server`](https://github.com/wso2/integration-samples/tree/main/integrator-default-profile/samples/task-management-mcp-server)
sample: the same four tools over the same seeded board. It carries no authentication and no
configuration of its own.

## Tools

| Tool | What it does |
| --- | --- |
| `listTasks` | Lists the board, or only the tasks in one status |
| `createTask` | Adds a task, open and at medium priority unless told otherwise |
| `setTaskStatus` | Moves a task to `open`, `in-progress` or `done` |
| `assignTask` | Gives a task to someone |

A request the board refuses (a blank title, an unknown status, a task id that is not on the
board) comes back as a tool error carrying the board's own message.

## How it is built

| Artifact | Role |
| --- | --- |
| `TaskManagementMCPServer` (inbound endpoint) | The MCP server: the `mi-inbound-mcp` listener on port 8300 at `/mcp` |
| `TaskManagementMCPServer-mcp-config` (local entry) | Names each tool, its input schema, and the API resource that runs it |
| `TaskBoardAPI` (API, `/tasks`) | One resource per tool; each puts its inputs in variables and calls the board |
| `TaskBoardMediator` (class mediator, `src/main/java`) | Holds the board and runs each operation |

| Tool | API resource |
| --- | --- |
| `listTasks` | `GET /tasks?status=…` |
| `createTask` | `POST /tasks` |
| `setTaskStatus` | `PUT /tasks/{taskId}/status` |
| `assignTask` | `PUT /tasks/{taskId}/assignee` |

The MCP listener calls these resources on the runtime's own HTTP port, so the API is also usable
directly while you run the sample locally. Only the MCP endpoint is published.

## The board

Four tasks are seeded in memory. There is no database: each replica keeps its own copy, and a
restart returns to the seeded four. That keeps the sample to one deployable with nothing to
provision, which is the point of it. A real integration would write to a store.

## MCP transport

The `mi-inbound-mcp` listener speaks the MCP `2024-11-05` **HTTP+SSE** transport, not Streamable
HTTP. Configure your MCP client for SSE:

1. `GET /mcp` with `Accept: text/event-stream`, and keep the stream open. The first event is
   `event: endpoint` with `data: <url>/mcp?sessionId=<id>`.
2. POST JSON-RPC messages (`initialize`, `notifications/initialized`, `tools/list`,
   `tools/call`) to that URL. Each POST returns `202`, and the reply arrives on the stream as
   an `event: message`.

## Running it locally

Build the CAR with Maven (Java 21):

```bash
mvn clean install -Dmaven.test.skip=true
# target/TaskManagementMCPServer_1.0.0.car
```

Run it on Micro Integrator 4.6.0:

```bash
docker run --rm -p 8290:8290 -p 8300:8300 \
  -v "$PWD/target/TaskManagementMCPServer_1.0.0.car":/home/wso2carbon/wso2mi-4.6.0/repository/deployment/server/carbonapps/TaskManagementMCPServer_1.0.0.car \
  wso2/wso2mi:4.6.0
```

Or open the folder in VS Code with the WSO2 Integrator: MI extension and run it from there.

Point an MCP client at `http://localhost:8300/mcp` with the SSE transport (for example, MCP
Inspector with transport "SSE"). The API answers on port 8290:

```bash
curl http://localhost:8290/tasks/
curl "http://localhost:8290/tasks/?status=open"
curl -X POST http://localhost:8290/tasks/ -H 'Content-Type: application/json' \
  -d '{"title":"Renew the domain","priority":"high"}'
curl -X PUT http://localhost:8290/tasks/T-1003/status -H 'Content-Type: application/json' \
  -d '{"status":"in-progress"}'
```
