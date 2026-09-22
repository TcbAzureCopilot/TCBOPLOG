# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Structure

Two Maven projects in one repo:
- **Root** (`pom.xml`): `com.machineroom:machineroom-log` — Java EE 7 WAR for **WebSphere + DB2** (target bytecode Java 8)
- **`tcboplog/`** (`tcboplog/pom.xml`): Spring Boot 2.7 wrapper that **overlays** the root WAR for standalone Docker use with embedded H2

The TCBOPLOG build depends on the root WAR being installed locally first:
```
mvn -DskipTests install           # from repo root — installs machineroom-log to ~/.m2
mvn -DskipTests package -f tcboplog/pom.xml  # produces tcboplog/target/tcboplog.war
```

## Build / Test Commands

```bash
# All tests (JUnit 4, H2 in-memory DB2-compat mode)
mvn test

# Run a single test class
mvn test -Dtest=RulesTest
mvn test -Dtest=IntegrationTest

# Local dev server (Jetty on :8080/mrlog/, H2, auth.mode=dev)
mvn jetty:run

# Docker trial build
docker build -t tcboplog .
docker compose up -d
```

Tests require no running DB — H2 in `MODE=DB2` is bootstrapped by `@BeforeClass` calling `Db.setDataSource(ds)` and running `db/01_schema_db2.sql` + `db/02_seed_db2.sql`.

Surefire passes `-Dmachineroom.config=config/app.dev.properties` automatically.

## Configuration

The external config file is resolved in this order (config auto-reloads every 60 s):
1. JVM system property `-Dmachineroom.config=<path>`
2. Env var `MACHINEROOM_CONFIG=<path>`
3. JNDI `java:comp/env/machineroom/config`
4. `~/machineroom/app.properties`

Without a valid config file the app runs **fail-closed** (nobody can log in).
Three auth modes: `ldap` (LDAPS, production), `dev` (password list in `dev.users`), `bypass` (no password check, Docker default).

## Code Conventions

**No framework DI** — the WAR module is plain Java EE. Injection is manual:
- `AppConfig.get()` — singleton, auto-reloading
- `Db.tx(c -> { ... })` — only way to run DB writes; opens a connection with `autoCommit=false`, commits on success, rolls back on any exception
- `Db.setDataSource(ds)` — test-only hook to bypass JNDI

**DAOs** use raw JDBC via `Jdbc.*` helpers (null-safe setters/getters; booleans stored as `SMALLINT` 0/1 in DB2).

**Service layer** (`com.machineroom.service`): all mutations go through `LogService`, which re-checks permissions server-side, writes to `AUDIT_LOG`, and returns the updated `DailyLog`.

**Error handling**: throw `ServiceException.forbidden(msg)` / `badRequest(msg)` / `notFound(msg)` / `conflict(msg)` — they carry an HTTP status and are caught by `ApiServlet` to produce JSON error responses.

**HTML output**: always use `Html.esc(s)` / `Html.orDash(s)` for server-rendered JSP/HTML strings. Never concatenate raw strings into HTML.

**PDF fonts**: use TrueType (`.ttf`/`.ttc`). CFF-based `.otf` files (e.g. Noto Sans CJK `.otf`) produce broken glyphs with OpenPDF subsetting. TTC paths must include a `,index` suffix (e.g. `msjh.ttc,0`).

**ApiServlet routing**: routes are declared as `(method, regexPattern, handler)` tuples. Path parameters are accessed via `ctx.p(n)` (URL-decoded) or `ctx.date(n)`. No annotation-based routing.

**Package-private test bridge**: `ApiServletAccess` in `src/test/.../web/` exposes `ApiServlet.logJson()` which is package-private. Place test helpers that need package access in the matching test package.

## Architecture Flow

```
Browser → ApiServlet (routes /api/*) → LogService / DefService / HistoryService
                                          → DailyLogDao / DefDao / AuditDao (Db.tx)
                                          → DB2 (prod) / H2 in DB2 mode (dev/test)
Browser → PdfServlet / PrintServlet → PdfService / PrintHtml → OpenPDF
```

Permissions are enforced **exclusively server-side** in `Permissions.java`; the front-end (`static/app.js`) reads the `perm` object from the JSON response and only toggles UI visibility.

## DB2 Compatibility Notes

- H2 must be started with `MODE=DB2` and `DEFAULT_NULL_ORDERING=HIGH` for dev and test to match production behaviour
- Schema lives in `db/01_schema_db2.sql`; `db.autoInit=true` runs it automatically (dev only — **never use in production DB2**)
- Seed data in `db/02_seed_db2.sql` is loaded when `db.seedDefaultsIfEmpty=true`
