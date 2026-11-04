# Backend development guide

Start with the [README](../README.md) for product background, the complete Compose quick start, and the demo walkthrough. This guide covers working on the local frontend and backend together.

## Choose a development mode

| Mode | What runs locally | Notes |
| --- | --- | --- |
| Complete Compose stack | Docker builds frontend, backend, and MySQL | Set `FRONTEND_BUILD_CONTEXT` to your local frontend checkout to include local changes. |
| Native apps with Docker database | Maven backend, Next.js frontend, Compose MySQL | Use MySQL host port `3307` by default; commands follow below. |
| Fully native | Maven backend, Next.js frontend, local MySQL | Initialise `schema.sql` manually; the default JDBC URL uses port `3306`. |

The built-in mock is part of the backend Java process. It is not a separate Compose service.

## Native development with a Docker database

Use JDK 8, Maven 3.8 or later, and Docker Compose v2. The source imports `jdk.nashorn.api.scripting` directly, so selecting a newer JDK can cause compilation failures. Check both `java -version` and `mvn -version`; Maven must also use JDK 8. This repository has no Maven wrapper, so `mvn` must be available separately.

Run from the backend directory. Copy `.env.example` only on first setup and retain an existing `.env`:

```bash
cp .env.example .env
docker compose up -d database
docker compose ps database
```

Wait until the database is healthy. The following exports match the defaults in `.env.example`; use your configured values if you changed the credentials or database port:

```bash
export DB_USERNAME=brick
export DB_PASSWORD=brick_local_dev
export DB_URL='jdbc:mysql://localhost:3307/brick_platform?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Pacific/Auckland'
mvn spring-boot:run
```

Compose reads `.env` for interpolation. A native Spring Boot process does **not** load that file automatically; export its database settings in the same shell used to start Maven. The credentials above are local demonstration defaults.

In another terminal, use the frontend checkout:

```bash
npm ci
cp .env.example .env.local
npm run dev
```

Retain an existing frontend `.env.local` rather than overwriting it. Its `API_BASE_URL` should be `http://localhost:8080/api/brick` for this arrangement. Use Node.js 22 to match frontend CI and Docker.

Check the backend, the same-origin frontend proxy, and the native mock:

```bash
curl --fail http://localhost:8080/api/actuator/health
curl --fail http://localhost:3000/api/brick/mappings
curl --fail http://localhost:9090/openapi.json
```

The first response should report `UP`. The mappings response uses the Brick `{ success, message, data }` envelope and may contain an empty list before import. The final command assumes the backend runs natively; Docker mock reachability differs as described below.

## Service addresses and Docker networking

| Connection | Native applications | Complete Compose stack |
| --- | --- | --- |
| Browser → frontend | `http://localhost:3000` | `http://localhost:3000` |
| Next.js → backend | `http://localhost:8080/api/brick` | `http://backend:8080/api/brick` |
| Backend → MySQL | `localhost:3307` with Compose DB, or local `3306` | `database:3306` |
| Backend → built-in mock | `http://localhost:9090` | `http://localhost:9090`, inside the backend container |
| Host → Compose MySQL | `localhost:3307` | `localhost:3307` |

These are defaults. `BACKEND_PORT`, `FRONTEND_PORT`, and `DB_PORT` in `.env` change published **host** ports; container-to-container addresses keep their service names and internal ports. Compose explicitly configures its backend JDBC URL and frontend `API_BASE_URL`.

Contract URLs entered in the UI are fetched by the backend. Target URLs in imported OpenAPI definitions are also used by the backend when executing flows. For an API running on the host while Brick runs in Docker Desktop, use a backend-reachable address such as `host.docker.internal`; `localhost` refers to the backend container.

### Built-in mock limitation

`MockCommerceServer` binds to `127.0.0.1`. Native development can open its port directly from the host. In Compose, the backend can import `http://localhost:9090/openapi.json` and execute the resulting endpoints, but the host cannot directly reach that loopback listener through the published port. Import through **Swagger Mappings** instead.

The bundled OpenAPI document advertises `http://localhost:9090/api/v1`. If you change the mock's actual listening port, update the imported server address/custom host as well; changing a port setting does not rewrite an existing endpoint definition. Compose currently fixes the internal mock port at `9090`; its `.env` `MOCK_COMMERCE_PORT` setting changes the published host port.

The mock keeps demonstration state in memory and is intended for a repeatable single-user walkthrough. Restarting the backend resets that state; it does not clear Brick's persisted test definitions or reports.

## Database lifecycle

[schema.sql](../src/main/resources/schema.sql) creates the database and tables. Fully native MySQL setup requires importing it with a user allowed to create the database/tables, as shown in the README.

Compose mounts it into `/docker-entrypoint-initdb.d/`, which initialises a fresh MySQL data directory. An existing named volume is reused and does not rerun initialisation scripts. `CREATE TABLE IF NOT EXISTS` also does not upgrade an existing table. There is currently no Flyway/Liquibase migration configuration; review and apply explicit schema changes when retaining existing data.

Changing `.env` database credentials does not change accounts already created in a persisted MySQL volume. Update the database account and application configuration together. `docker compose down` retains the volume. Use `docker compose down -v` only for an intentional reset: it deletes the Compose database, including saved flows and reports.

## Backend configuration

The full defaults are in [application.yml](../src/main/resources/application.yml). Native Spring Boot settings include:

| Setting | Default / requirement | Purpose |
| --- | --- | --- |
| `DB_USERNAME`, `DB_PASSWORD` | Required | JDBC credentials; no native default is provided |
| `DB_URL` | MySQL `localhost:3306/brick_platform` | JDBC URL; set host port `3307` when using the default Compose database from a native backend |
| `SERVER_PORT` | `8080` | Backend HTTP port; context path remains `/api` |
| `APP_TIME_ZONE` | `Pacific/Auckland` | Jackson date/time zone; the JDBC URL has its own `serverTimezone` parameter |
| `MOCK_COMMERCE_ENABLED` | `true` | Start the built-in commerce mock |
| `MOCK_COMMERCE_PORT` | `9090` | Native mock listening port |
| `APP_LOG_LEVEL`, `SPRING_LOG_LEVEL` | `INFO` | Application and Spring logging levels |
| `DB_ALLOW_PUBLIC_KEY_RETRIEVAL` | `true` | Hikari datasource property for MySQL public-key retrieval |
| `APP_VERSION` | `1.0-SNAPSHOT` | Actuator application metadata |

Additional database-query variable sources are registered backend `DataSource` beans. `GlobalVariableDataSourceRegistry` exposes the primary source as `primary` and other configured sources by bean name. The UI selects a source identifier; it does not configure JDBC credentials.

## Source map

Paths below are relative to `src/main/java/apiworkflow/`.

| Area | Entry point | Responsibility |
| --- | --- | --- |
| Startup | `BrickApplication.java` | Spring Boot entry point |
| HTTP API | `controller/BrickController.java` | Mapping, endpoint, flow, suite, variable, and report APIs |
| Contract ingestion | `swagger/`, `service/impl/BrickApiServiceImpl.java` | Parse Swagger/OpenAPI, resolve schemas, and synchronise endpoints |
| Flow persistence and scheduling | `service/impl/BrickFlowServiceImpl.java` | Save full canvases, copy flows, schedule nodes, and persist run results |
| Flow variables | `execution/FlowContextEngine.java` | Validate/extract response variables and bind downstream request values |
| Global variables | `execution/GlobalVariableEngine.java` | Resolve stored values, JavaScript functions, and database queries |
| HTTP execution | `execution/FlowHttpExecutor.java` | Build requests, merge headers, apply timeouts/retries, and capture responses |
| Assertions | `execution/AssertionExecutor.java` | Status, JSONPath, header, and response-time assertions |
| Suites | `service/impl/BrickTestSuiteServiceImpl.java` | Ordered execution, aggregation, and failed-flow reruns |
| Persistence | `mapper/`, `src/main/resources/mapper/` (repository root) | MyBatis interfaces and SQL mappings |
| Observability | `web/RequestTracingFilter.java`, `exception/GlobalExceptionHandler.java` | Request IDs and consistent errors |

## API and execution semantics

The service root is `/api/brick`. Use the generated [Swagger UI](http://localhost:8080/api/swagger-ui.html) for the full endpoint inventory. Common API groups are `/mappings`, `/endpoints`, `/flows`, `/test-suites`, and `/global-variables`. The [save contract](flow-save-frontend-contract.md) documents the full-canvas replacement rules; it is particularly important when changing node IDs or assertions.

Flow and suite execution is synchronous in the HTTP request. A run row is created first and updated when execution finishes. The scheduler processes ready nodes sequentially. It supports DAG dependencies and `ALL`/`ANY` joins; it does not run independent branches in parallel. Only `http` nodes execute. Cycles or unsupported node types fail at runtime; fields referring to gRPC or conditions do not establish an implemented execution feature.

For each ready node, the engine resolves global variables, applies available flow-variable bindings, resolves shared headers, sends the HTTP request, extracts response variables, and evaluates assertions. A transport failure blocks dependent paths according to their join strategy; independent eligible paths can continue. A missing flow variable can still fail at runtime if its producer has not executed, including on an `ANY` branch.

| Result field | Interpretation |
| --- | --- |
| API envelope `success` | Brick API processing succeeded; a completed failing test can still be returned with `success: true` |
| Run/node `status` | Request execution/dependency status; receiving an HTTP error response still counts as request success |
| `businessStatus` | `passed`, `failed`, `not_configured` (no enabled assertions), or `not_evaluated` |

To reject an HTTP `401` or `500`, configure a status-code assertion. Join readiness depends on request status, so assertion failures do not stop downstream nodes. HTTP retries cover transport exceptions and responses with status `500` or above, up to the configured retry count; assertions are evaluated after the final response.

## Tests and troubleshooting

```bash
mvn test
mvn --batch-mode --no-transfer-progress verify
```

Use the first command for normal development and the second to reproduce CI verification/package checks. Tests use mocks, H2, and temporary loopback HTTP servers, so they need JDK 8 and local port-binding permission but no running MySQL. Frontend interaction checks are documented in the [frontend guide](https://github.com/graham-gc/brick-platform-frontend/blob/main/docs/development.md). A passing backend suite does not verify Docker networking or browser interactions.

| Symptom | What to check |
| --- | --- |
| `jdk.nashorn` compilation errors | Check Maven's selected JDK, not just the shell's `java`; use JDK 8. |
| Missing database placeholders / connection refused | Export native credentials, check DB health, and distinguish host port `3307` from internal/default port `3306`. |
| Changed credentials but MySQL rejects them | Existing volumes retain database accounts; changing `.env` alone does not recreate them. |
| Missing table/column after schema edits | Existing volumes are not automatically migrated; review the schema change before applying it. |
| `Unable to start Mock Commerce API` | Check whether the listening port is occupied or local binding is restricted; disable the mock if it is not needed. |
| `Flow variable is not available yet` | Check producer/consumer dependencies and `ANY` joins; the producer must execute before its value is bound. |
| Requests succeed but business status fails | Inspect enabled assertion snapshots, expected values, and actual response content. |

Use the response's `X-Request-ID` to correlate errors with backend logs. Run and node reports hold request/response snapshots; the request-completion log intentionally records method, URI, status, and duration without payloads or query values.
