# Brick Platform Backend

[![Backend CI](https://github.com/graham-gc/brick-platform-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/graham-gc/brick-platform-backend/actions/workflows/ci.yml)

Brick Platform turns Swagger/OpenAPI contracts into executable API workflows. Users can import endpoint definitions, compose them as a directed graph, pass response data between nodes, apply transport and business assertions, and inspect a complete execution history.

This repository contains the Spring Boot execution service. The visual application is in [brick-platform-frontend](https://github.com/graham-gc/brick-platform-frontend).

## Project background

Brick is a clean-room portfolio reconstruction of an internal engineering-productivity platform that I independently designed and delivered while working in a commercial technology team.

The original internal implementation reached usable production status within one week and stable team-wide use within one month. It supported approximately 30 applications, 4,000 API definitions, and more than 800 test flows. A test suite containing roughly 400–500 flows could run unattended in about five minutes, replacing repetitive script-by-script execution and centralising test assets and reports.

This public implementation recreates the system design and core workflow using new code, mock services, and synthetic data. It contains no former-employer source code, production data, credentials, infrastructure details, or proprietary identifiers.

### Problem and outcome

API checks previously existed as separate scripts that had to be started and managed manually. The engineering problem was therefore not simply “send an HTTP request”; it was to make existing test assets discoverable, composable, repeatable, and understandable to other users. Brick moved that work into a shared workflow: import the contract, compose a flow, run it unattended, and review one report.

### Engineering approach

- **End-to-end ownership:** requirements discovery, technical design, implementation, testing, rollout, and operational support were handled as one delivery lifecycle.
- **Practical problem-solving:** the design prioritises the workflow bottlenecks that affected users—reusing endpoints, transferring data, reducing manual execution, and diagnosing failures.
- **Clear handover:** architecture diagrams, explicit design decisions, a mock service, repeatable setup, and automated checks make the repository reviewable by another engineer.
- **Safe defaults:** credentials stay outside source control, logs avoid sensitive payloads, SQL variables are read-only, and the public project contains synthetic data only.
- **Maintainable scope:** enterprise identity and distributed scheduling are documented as future boundaries rather than represented by incomplete placeholder features.

## What this project demonstrates

### Contract ingestion

- Swagger 2.0 and OpenAPI 3 validation from a URL or uploaded JSON document
- Checksum-based synchronisation and endpoint discovery
- Mapping-scoped schema storage instead of duplicating schemas for every endpoint
- Recursive `$ref` expansion for nested request, parameter, and response definitions

### Workflow execution

- Directed flow execution with branching and configurable `ALL` / `ANY` joins
- Multiple independent nodes referencing the same endpoint definition
- Flow-scoped response extraction using JSONPath
- Downstream binding into request bodies, query parameters, path parameters, and headers
- Shared flow headers plus node-specific header overrides
- Static values, stored Cookie values, JavaScript functions, and read-only database-query variables using `${{...}}`

### Verification and diagnostics

- Separate transport and business outcomes: a successful HTTP exchange can still fail its assertions without being misreported as a request failure
- Status-code, JSONPath, response-header, and response-time assertions
- Immutable node and assertion snapshots for each execution
- Run history, failed-flow reruns, test-suite orchestration, and node-level request/response diagnostics
- Consistent JSON errors, request validation, and end-to-end `X-Request-ID` correlation

### Reproducible demonstration

- A built-in commerce API with login, products, orders, and payment endpoints
- A bundled OpenAPI document containing nested schemas and realistic variable-transfer paths
- Docker Compose for the frontend, backend, MySQL, and mock service
- Generated OpenAPI documentation and an interactive Swagger UI

## Architecture

```mermaid
flowchart LR
    UI["Next.js visual designer"] -->|"BFF proxy"| API["Spring Boot API"]
    SPEC["Swagger / OpenAPI"] --> PARSER["Contract parser"]
    PARSER --> DEFINITIONS[("Endpoints and schemas")]
    API --> DEFINITIONS
    API --> FLOWS[("Flow definitions")]
    API --> ENGINE["DAG execution engine"]
    DEFINITIONS --> ENGINE
    FLOWS --> ENGINE
    GLOBALS["Global variable engine"] --> ENGINE
    ENGINE --> SUT["API under test"]
    ENGINE --> RUNS[("Run and assertion snapshots")]
    RUNS --> UI
```

### Execution lifecycle

```mermaid
flowchart TD
    LOAD["Load flow, nodes and edges"] --> READY["Find executable nodes"]
    READY --> RESOLVE["Resolve global and flow variables"]
    RESOLVE --> REQUEST["Build and send HTTP request"]
    REQUEST --> TRANSPORT{"Transport succeeded?"}
    TRANSPORT -->|No| BLOCK["Record failure and block dependent paths"]
    TRANSPORT -->|Yes| EXTRACT["Extract response variables"]
    EXTRACT --> ASSERT["Evaluate business assertions"]
    ASSERT --> SNAPSHOT["Persist request, response and assertion snapshot"]
    SNAPSHOT --> READY
```

## Selected design decisions

| Decision | Reason |
| --- | --- |
| Keep `flow_node.id` separate from `endpoint_definition.id` | The same endpoint can appear multiple times in one flow with different inputs, assertions, and outgoing edges. |
| Store schemas against a Swagger mapping | OpenAPI schemas are document-scoped and can be referenced recursively by many endpoints. |
| Separate transport status from assertion status | HTTP success and business correctness answer different questions and must remain diagnosable independently. |
| Persist execution snapshots | Historical reports remain meaningful even after a flow or assertion definition changes. |
| Use explicit variable bindings | Interactive source/target configuration is safer and easier to review than hidden string substitution. |
| Restrict database variables to backend-managed sources and read-only SQL | Credentials remain server-side, parameters are bound rather than interpolated, and workflow execution cannot mutate data. |

## Technology

- Java 8 and Spring Boot 2.7
- Spring MVC, Bean Validation, and Actuator
- MyBatis and MySQL 8
- Apache HttpClient, JsonPath, Jackson, and Fastjson
- Nashorn with host access disabled for bounded JavaScript variable functions
- springdoc-openapi and Swagger UI
- JUnit 5, Mockito, and H2 for isolated query tests
- Maven, Docker, and GitHub Actions

## Quick start

The fastest way to review the complete project is Docker Compose.

### Prerequisites

- Docker Desktop or Docker Engine with Docker Compose v2

### Start the complete stack

```bash
cp .env.example .env
docker compose up --build
```

By default, Compose builds the public frontend repository directly from GitHub. When both repositories are available locally, set `FRONTEND_BUILD_CONTEXT` in `.env` to the absolute frontend directory.

| Service | URL |
| --- | --- |
| Web application | `http://localhost:3000` |
| Backend health | `http://localhost:8080/api/actuator/health` |
| Backend information | `http://localhost:8080/api/actuator/info` |
| Swagger UI | `http://localhost:8080/api/swagger-ui.html` |
| Generated OpenAPI JSON | `http://localhost:8080/api/v3/api-docs/brick-platform` |
| Mock commerce OpenAPI | `http://localhost:9090/openapi.json` |
| MySQL | `localhost:3307` |

Stop the stack while retaining its data:

```bash
docker compose down
```

Use `docker compose down -v` only when a completely fresh demonstration database is required; it deletes the Compose-managed MySQL volume.

## Ten-minute product walkthrough

1. Open `http://localhost:3000/mappings`.
2. Create a mapping from `http://localhost:9090/openapi.json` using environment `dev` and version `local`.
3. Synchronise it, then inspect the imported endpoint and recursively resolved schema definitions.
4. Create a flow: `Login -> List Products -> Create Order -> Pay Order -> Get Order`.
5. Extract the login token, product ID, and order ID, then bind them into downstream headers, request bodies, and path parameters.
6. Add transport and business assertions, execute the flow, and inspect the result drawer and run history.
7. Add the flow to a Test Suite to demonstrate unattended multi-flow execution.

The mock login accepts `demo.user` / `demo-password`. Protected endpoints expect `Authorization: Bearer mock-access-token`.

### Global-variable examples

```text
${{baseUrl}}
${{sessionCookie}}
${{randomString(8)}}
${{orderStatus("ORD-001")}}
```

Function variables execute a user-defined JavaScript function body with declared parameters. Database-query variables select a backend-configured data source and execute a single parameterised, comment-free `SELECT`; queries have a five-second timeout and a 1,000-row limit.

## Manual development setup

### Prerequisites

- JDK 8
- Maven 3.8 or later
- MySQL 8

### Initialise and configure MySQL

```bash
mysql -u root -p < src/main/resources/schema.sql
export DB_USERNAME=root
export DB_PASSWORD=your-local-mysql-password
```

Optional environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/brick_platform?...` | JDBC connection URL |
| `SERVER_PORT` | `8080` | Spring Boot port |
| `APP_TIME_ZONE` | `Pacific/Auckland` | API date/time zone |
| `APP_VERSION` | `1.0-SNAPSHOT` | Service version metadata |
| `MOCK_COMMERCE_ENABLED` | `true` | Start the demonstration API |
| `MOCK_COMMERCE_PORT` | `9090` | Demonstration API port |
| `APP_LOG_LEVEL` | `INFO` | Application logging level |

Start the backend:

```bash
mvn spring-boot:run
```

The main API is served from `http://localhost:8080/api/brick`.

## Testing and CI

```bash
mvn test
```

The automated suite covers contract parsing, recursive schema resolution, variable extraction and binding, global functions, read-only database variables, HTTP execution, branching, assertions, flow copying, execution snapshots, test-suite orchestration, mapper SQL, API metadata, and request correlation.

GitHub Actions runs Maven verification and builds the production container image on pushes to `main` and pull requests.

## Request tracing

Every response contains `X-Request-ID`. A client can provide a safe correlation ID containing letters, digits, `.`, `_`, or `-`; otherwise the backend generates a UUID. The same ID appears in application logs.

```bash
curl -i \
  -H 'X-Request-ID: local-demo-001' \
  http://localhost:8080/api/brick/mappings
```

Completion logs include method, URI, status, and duration without recording request bodies, query values, credentials, or tokens.

## Repository layout

```text
src/main/java/apiworkflow/
  controller/   HTTP API and validation boundary
  execution/    DAG context, variables, HTTP and assertion engines
  mapper/       MyBatis data-access interfaces
  service/      application orchestration
  swagger/      Swagger/OpenAPI parsing and schema resolution
  mock/         reproducible commerce API
src/main/resources/
  mapper/       MyBatis XML statements
  schema.sql    complete MySQL schema
examples/       demonstration OpenAPI document
```

## Scope

The portfolio version intentionally focuses on API workflow automation. Enterprise identity, multi-tenant authorisation, distributed scheduling, and secret-manager integration are outside the demonstration scope. Authentication is represented by ordinary reusable headers/Cookie values so the execution and data-flow design can be reviewed without requiring external identity infrastructure.
