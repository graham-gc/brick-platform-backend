# Brick Platform Backend

[![Backend CI](https://github.com/graham-gc/brick-platform-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/graham-gc/brick-platform-backend/actions/workflows/ci.yml)

Brick Platform is an API workflow automation backend. It imports Swagger/OpenAPI definitions, resolves nested schemas, composes endpoints into executable flows, carries response data into downstream requests, evaluates business assertions, and retains detailed run history.

The companion web application is available in [brick-platform-frontend](https://github.com/graham-gc/brick-platform-frontend).

## What it demonstrates

- Swagger 2.0 and OpenAPI 3 document validation, import, checksum-based synchronisation, and endpoint discovery
- Document-scoped schema storage with recursive `$ref` resolution
- Directed API workflow execution with branching and `ALL`/`ANY` join strategies
- Flow-scoped response extraction using JSONPath and downstream binding to body, query, path, and header fields
- Shared flow headers alongside node-specific headers
- Separate transport and business outcomes: HTTP execution can succeed while assertions fail
- Status-code, JSONPath, response-header, and response-time assertions with immutable execution snapshots
- Test-suite execution, run history, failed-flow reruns, and node-level request/response diagnostics
- A self-contained mock commerce API for a reproducible end-to-end demonstration

## Architecture

```mermaid
flowchart LR
    UI["Next.js web application"] -->|"BFF proxy /api/brick"| API["Spring Boot API"]
    API --> DB[("MySQL")]
    API --> SPEC["Swagger / OpenAPI source"]
    API --> SUT["System under test"]
    API --> MOCK["Built-in mock commerce API"]
    API --> RUNS["Flow engine, assertions and reports"]
```

## Technology

- Java 8, Spring Boot 2.7, Spring MVC
- MyBatis and MySQL 8
- Apache HttpClient, JsonPath, Jackson, and Fastjson
- JUnit 5 and Mockito
- Maven

## Run locally

### Prerequisites

- JDK 8
- Maven 3.8 or later
- MySQL 8

### 1. Initialise the database

The schema script creates the `brick_platform` database and all required tables.

```bash
mysql -u root -p < src/main/resources/schema.sql
```

### 2. Configure the database connection

Credentials are deliberately not committed. Export them in the terminal or configure the same variables in the IDE run configuration.

```bash
export DB_USERNAME=root
export DB_PASSWORD=your-local-mysql-password
```

Optional configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/brick_platform?...` | JDBC connection URL |
| `SERVER_PORT` | `8080` | Spring Boot HTTP port |
| `APP_TIME_ZONE` | `Pacific/Auckland` | API date/time zone |
| `MOCK_COMMERCE_ENABLED` | `true` | Start the built-in demonstration API |
| `MOCK_COMMERCE_PORT` | `9090` | Demonstration API port |
| `APP_LOG_LEVEL` | `INFO` | Application logging level |

### 3. Start the backend

```bash
mvn spring-boot:run
```

The Brick API is served from `http://localhost:8080/api/brick`. With the default settings, the mock API specification is available at `http://localhost:9090/openapi.json`.

## Reproduce the demo

1. Start this backend and the companion frontend.
2. Create a Swagger Mapping using `http://localhost:9090/openapi.json`, environment `dev`, and version `local`.
3. Synchronise the mapping and inspect the imported request, response, and nested schema definitions.
4. Create a flow such as `Login -> List Products -> Create Order -> Pay Order -> Get Order`.
5. Extract the login token, product ID, and order ID as flow variables and bind them into downstream headers, bodies, and paths.
6. Add transport and business assertions, execute the flow, and inspect the node-level report or add the flow to a Test Suite.

The mock login accepts `demo.user` / `demo-password`. Protected endpoints expect `Authorization: Bearer mock-access-token`.

## Tests

```bash
mvn test
```

The test suite covers Swagger parsing, recursive schema resolution, context-variable extraction, HTTP execution, branching, assertions, flow copying, run snapshots, mapper SQL, and test-suite orchestration.

## Repository layout

```text
src/main/java/apiworkflow/
  controller/   HTTP endpoints
  execution/    flow context, HTTP and assertion engines
  mapper/       MyBatis data access
  service/      application services
  swagger/      Swagger/OpenAPI parsing and schema resolution
  mock/         reproducible commerce API
src/main/resources/
  mapper/       MyBatis XML statements
  schema.sql    complete MySQL schema
examples/       demonstration OpenAPI document
```

## Current scope

This is a portfolio reconstruction of an internal engineering-efficiency platform. It intentionally contains no former-employer source code, infrastructure details, production data, or proprietary identifiers. Authentication and multi-tenant access control are outside the current demonstration scope.
