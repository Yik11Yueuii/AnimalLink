# AnimalLink

AnimalLink is a **multimodal-model-assisted campus animal incident collaboration platform** (基于多模态大模型的校园动物事件协同平台). Its long-lived `Animal` identity connects campus observations, abnormal-event rescue, case-linked public support, adoption handover, and post-adoption updates. AI may prepare drafts and candidates, but people confirm and Java services perform formal state changes.

This repository is the Phase 0 infrastructure baseline, not a working business product. No formal User, Campus, Animal, Circle, Event, Case, Adoption, or AI business workflow is implemented yet. The approved [PRD V2.1](docs/product/AnimalLink-PRD-V2.1.docx), [Technical Design V1.0](docs/technical/AnimalLink-Technical-Design-V1.0.docx), and [project rules](AGENTS.md) define the scope.

## Architecture and layout

Java 21, Spring Boot 3.2.12, Spring Cloud 2023.0.4, Spring Cloud Alibaba 2023.0.3.4, and Nacos 2.4.3 form the backend baseline. A Gateway fronts five independently started services. Each business service owns one logical MySQL 8 database and has Flyway migrations. MinIO is the object-storage dependency. RabbitMQ and other later-phase infrastructure are not part of Phase 0.

| Path | Purpose |
| --- | --- |
| `backend/` | Gateway and five service Maven modules |
| `infra/` | Local Compose, MySQL initialization, MinIO build, and Nacos example |
| `scripts/` | Local development helpers |
| `docs/product/` | Current approved product baseline |
| `docs/technical/` | Current approved technical baseline |
| `docs/archive/` | Clearly deprecated history only |
| `pom.xml`, `mvnw`, `.mvn/` | Root Maven reactor and pinned Wrapper |

The Gateway uses port 8080. `identity-service`, `animal-service`, `incident-service`, `adoption-service`, and `intelligence-service` use ports 8081–8085, respectively.

## Local prerequisites and configuration

Install JDK 21 and Docker Desktop with Compose. Set `JAVA_HOME` to JDK 21; the Wrapper downloads Maven 3.9.9, so no separate Maven installation is required. Copy `infra/.env.example` to `infra/.env` and replace every example credential with a unique local value. The five database passwords must use only letters, digits, `_`, or `-` because of the local initialization script. Never commit `infra/.env`, credentials, personal records, or local volumes.

The Compose project binds MySQL to `127.0.0.1:3308` by default, Nacos to `127.0.0.1:8848`, and MinIO to `127.0.0.1:9000` (console 9001). The MinIO server image is built from a pinned official source release by `infra/minio/Dockerfile`.

From the repository root in PowerShell:

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build
. ./scripts/load-local-env.ps1
.\mvnw.cmd clean verify
```

On Unix or macOS, run `./mvnw clean verify` from the root. Use the equivalent shell environment export instead of the PowerShell helper.

## Start the six applications

Open one terminal per application. Load the same local environment in each terminal, then run one of these commands from the repository root:

```powershell
. ./scripts/load-local-env.ps1
.\mvnw.cmd -f backend/pom.xml -pl api-gateway spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl identity-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl animal-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl incident-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl adoption-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl intelligence-service spring-boot:run
```

Only run one application command in each terminal. The `local` Spring profile is the default; use `-Dspring-boot.run.profiles=dev` when explicitly testing the dev profile. Host addresses can be overridden with `NACOS_SERVER_ADDR`, `MYSQL_HOST`, `MYSQL_PORT`, `MINIO_ENDPOINT`, and `SERVER_PORT`.

## Phase 0 checks

Request `http://localhost:8080/actuator/health/liveness` and the same path on ports 8081–8085. `/actuator/health` reports dependency status; each business service exposes `db` and `minio` health components. The first start applies only a no-op Phase 0 Flyway migration and creates each service's schema-history table. Nacos should show six registered application names.

Gateway paths `/identity/**`, `/animal/**`, `/incident/**`, `/adoption/**`, and `/intelligence/**` forward to their corresponding services after stripping the first path segment. Gateway accepts a valid `X-Trace-Id` (1–64 letters, digits, `_`, `-`) or creates a UUID and propagates it downstream. For example, `/identity/actuator/health/liveness` checks routing without a business API.

Nacos Config is optional for local startup. The Phase 0.1 verification uses only a harmless property in `identity-service.yaml`; see [infra/nacos/README.md](infra/nacos/README.md) for the Data ID, group, namespace, and proof endpoint. Credentials remain local environment variables, never Nacos test data.

Changing database passwords in `.env` after a MySQL volume has initialized does not rotate existing users. Rotate those users explicitly or deliberately recreate an expendable local volume.
