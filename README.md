# AnimalLink

AnimalLink 是一个**基于多模态大模型的校园动物事件协同平台**，连接动物观察、救助协作、领养流转与长期数字档案。平台围绕同一只 Animal 的长期身份，持续记录校园生活、异常救助、公益支持、领养过程及领养后的生活动态。AI 用于辅助理解、生成草稿和提供候选，由人确认，正式业务状态由 Java 服务执行。

当前仓库是 Phase 0 工程与基础设施基线，尚未实现正式业务流程。User、Campus、Animal、校园圈、Event、Case、Adoption 和 AI 等业务能力将在后续阶段按计划开发。项目范围以正式的[产品需求文档 V2.1](docs/product/AnimalLink-PRD-V2.1.docx)、[技术设计 V1.0](docs/technical/AnimalLink-Technical-Design-V1.0.docx)及[项目规则](AGENTS.md)为准。

## 技术架构

后端采用 Java 21、Spring Boot 3.2.12、Spring Cloud 2023.0.4、Spring Cloud Alibaba 2023.0.3.4 和 Nacos 2.4.3。API Gateway 统一接入五个独立服务。各业务服务拥有自己的 MySQL 8 逻辑库，并使用 Flyway 管理迁移。MinIO 用于对象存储。Phase 0 暂不部署 RabbitMQ 或其他后续阶段的基础设施。

| 目录 | 用途 |
| --- | --- |
| `backend/` | API Gateway 和五个后端服务 |
| `infra/` | 本地 Docker Compose、MySQL 初始化、MinIO 构建及 Nacos 配置示例 |
| `scripts/` | 本地开发辅助脚本 |
| `docs/product/` | 当前正式产品需求文档 |
| `docs/technical/` | 当前正式技术设计文档 |
| `docs/archive/` | 已明确标记为废弃的历史材料 |
| `pom.xml`、`mvnw`、`.mvn/` | 根目录 Maven 工程与固定版本的 Maven Wrapper |

API Gateway 使用 8080 端口；`identity-service`、`animal-service`、`incident-service`、`adoption-service`、`intelligence-service` 分别使用 8081–8085 端口。

## 本地环境准备

安装 JDK 21 和 Docker Desktop（需支持 Docker Compose）。设置 `JAVA_HOME` 指向 JDK 21。Maven Wrapper 会自动下载 Maven 3.9.9，无需另行安装 Maven。

将 `infra/.env.example` 复制为 `infra/.env`，并为每项示例凭据设置不同的本地值。五个数据库密码只能使用英文字母、数字、下划线和连字符，以符合本地初始化脚本的校验规则。不要提交 `infra/.env`、真实凭据、个人材料或本地数据。

本地基础设施端口：MySQL 为 `127.0.0.1:3308`，Nacos 为 `127.0.0.1:8848`，MinIO 为 `127.0.0.1:9000`，MinIO 控制台为 `127.0.0.1:9001`。MySQL 主机端口默认使用 3308，以避免与已有 MySQL 服务冲突。MinIO 镜像由 `infra/minio/Dockerfile` 按固定的官方源码版本在本机构建。

在仓库根目录的 PowerShell 中启动基础设施并构建：

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build
. ./scripts/load-local-env.ps1
.\mvnw.cmd clean verify
```

在 Unix 或 macOS 上，可在仓库根目录执行：

```sh
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build
set -a
. infra/.env
set +a
./mvnw clean verify
```

## 启动六个服务

每个服务分别在独立终端中启动。PowerShell 终端先加载本地环境，再执行对应服务的命令：

```powershell
. ./scripts/load-local-env.ps1
.\mvnw.cmd -f backend/pom.xml -pl api-gateway spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl identity-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl animal-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl incident-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl adoption-service spring-boot:run
.\mvnw.cmd -f backend/pom.xml -pl intelligence-service spring-boot:run
```

每个终端只执行其中一条服务启动命令。默认使用 Spring `local` 配置；需要验证 `dev` 配置时，可在服务启动命令中增加 `-Dspring-boot.run.profiles=dev`。可通过 `NACOS_SERVER_ADDR`、`MYSQL_HOST`、`MYSQL_PORT`、`MINIO_ENDPOINT` 和 `SERVER_PORT` 覆盖本地服务地址及端口。

## Phase 0 检查

访问 `http://localhost:8080/actuator/health/liveness`，以及 8081–8085 端口上的同一路径，检查六个服务是否存活。`/actuator/health` 显示依赖状态；五个业务服务还提供 `db` 和 `minio` 健康检查。首次启动时，Flyway 只执行 Phase 0 空迁移并创建迁移历史表。Nacos 中应能看到六个服务注册名。

Gateway 将 `/identity/**`、`/animal/**`、`/incident/**`、`/adoption/**` 和 `/intelligence/**` 分别转发到对应服务，并移除路径中的首段服务前缀。Gateway 会接收符合规则的 `X-Trace-Id`（1–64 位字母、数字、下划线或连字符），否则生成 UUID 并向下游传递。例如，访问 `/identity/actuator/health/liveness` 可检查路由是否可用。

本地启动不依赖 Nacos Config。Phase 0.1 使用 `identity-service.yaml` 中的无敏感信息配置验证读取链路；Data ID、Group、Namespace 和检查方式见 [infra/nacos/README.md](infra/nacos/README.md)。数据库密码和 MinIO 密钥通过本地环境变量提供，不放入 Nacos 测试配置。

MySQL 数据卷初始化后，再修改 `.env` 中的数据库密码不会自动修改已有数据库用户。需要显式轮换数据库用户密码，或在确认本地数据可丢弃后重新创建数据卷。
