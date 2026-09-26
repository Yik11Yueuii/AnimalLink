# AnimalLink

AnimalLink 是一个**基于多模态大模型的校园动物事件协同平台**，连接动物观察、救助协作、领养流转与长期数字档案。平台围绕同一只 Animal 的长期身份，持续记录校园生活、异常救助、公益支持、领养过程及领养后的生活动态。AI 用于辅助理解、生成草稿和提供候选，由人确认，正式业务状态由 Java 服务执行。

当前仓库已完成 Phase 0 工程与基础设施基线、Phase 1A 的身份与校园基础、Phase 1B 的 Animal 核心长期档案，以及 Phase 1C 的 Campus Circle 基础社区与关注能力。Event、Case、Adoption 正式流程和 AI 等能力仍按后续阶段实施。项目范围以正式的[产品需求文档 V2.1](docs/product/AnimalLink-PRD-V2.1.docx)、[技术设计 V1.0](docs/technical/AnimalLink-Technical-Design-V1.0.docx)及[项目规则](AGENTS.md)为准。

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

## Phase 1A 身份与校园能力

Phase 1A 的正式业务归属为 `identity-service`，已实现：

- User 基础账号主体与最小全局角色 `USER` / `GOVERNANCE_ADMIN`；校园身份不属于 SystemRole。
- 可公开读取、搜索的 Campus 列表与详情，供客户端建立当前浏览的 Campus Context；浏览校园不会创建 Membership。
- User 与 Campus 之间独立、长期的 CampusMembership，支持 `STUDENT`、`ALUMNI` 以及必要的有效性状态。
- CampusVerification 申请、本人查询、治理管理员人工批准/拒绝。状态仅为 `PENDING_REVIEW`、`APPROVED`、`REJECTED`。
- 审核通过与 CampusMembership 创建/更新位于同一 MySQL 本地事务中，并通过行锁、条件更新和唯一约束防止重复或并发审批。

Phase 1A 不包含正式微信登录、治理 Web 页面、材料上传、OCR/AI 预审、VolunteerMembership，也没有引入消息队列或分布式事务。

### 本地开发身份

在 `local`、`dev` 和自动化测试配置中，业务请求通过 `X-User-Id` 请求头选择已存在的演示用户。Controller 不直接解析该请求头，而是统一通过 CurrentUser Provider 获取当前用户。其他运行配置不会信任此请求头；正式认证提供方尚未接入时，受保护接口会返回 `401`。

本地演示身份：

| 用途 | X-User-Id |
| --- | --- |
| 普通用户 | `00000000-0000-0000-0000-000000000001` |
| 治理管理员 | `00000000-0000-0000-0000-000000000002` |

本地演示 Campus：

| 名称 | Campus ID |
| --- | --- |
| 示例大学东校区 | `10000000-0000-0000-0000-000000000001` |
| 示例大学西校区 | `10000000-0000-0000-0000-000000000002` |
| 演示学院 | `10000000-0000-0000-0000-000000000003` |

演示数据只通过 `local` / `dev` 的 Flyway demo location 加载，不会进入默认生产迁移位置。

### Phase 1A API

下表是 identity-service 内部路径。通过 Gateway 访问时，在路径前增加 `/identity`，例如 `GET /identity/api/v1/campuses`。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| GET | `/api/v1/users/me` | 当前用户基础信息 | 当前用户 |
| GET | `/api/v1/users/me/campus-memberships` | 当前用户的校园身份 | 当前用户 |
| GET | `/api/v1/campuses` | Campus 列表与基础搜索 | 公开 |
| GET | `/api/v1/campuses/{campusId}` | Campus 详情 | 公开 |
| POST | `/api/v1/campus-verifications` | 提交校园身份申请 | 当前用户 |
| GET | `/api/v1/campus-verifications` | 查看本人的申请列表 | 当前用户 |
| GET | `/api/v1/campus-verifications/{verificationId}` | 查看本人的申请详情 | 当前用户 |
| GET | `/api/v1/admin/campus-verifications` | 按状态查看审核列表 | 治理管理员 |
| GET | `/api/v1/admin/campus-verifications/{verificationId}` | 查看审核详情 | 治理管理员 |
| POST | `/api/v1/admin/campus-verifications/{verificationId}/approve` | 批准申请并创建/更新 Membership | 治理管理员 |
| POST | `/api/v1/admin/campus-verifications/{verificationId}/reject` | 拒绝申请 | 治理管理员 |

服务启动后，可通过 Gateway 验证本地身份和 Campus：

```powershell
$userHeaders = @{ 'X-User-Id' = '00000000-0000-0000-0000-000000000001' }
Invoke-RestMethod http://localhost:8080/identity/api/v1/users/me -Headers $userHeaders
Invoke-RestMethod 'http://localhost:8080/identity/api/v1/campuses?q=示例'
```

## Phase 1B Animal 核心长期档案

Animal 是系统中稳定、长期存在的主体。帖子、救助、领养及领养后动态在后续阶段都应关联同一个 Animal ID，而不能为一次业务过程重新创建 Animal。暂未识别的动物也不会创建名为“UNKNOWN”的公共档案；未来相关记录通过可空的 `animalId` 表达待确认关系。

Phase 1B 的正式业务归属为 `animal-service`，已实现：

- `animal` 核心主档，独立保存身份状态、领养投影状态和当前生活场景，避免单一超级状态枚举。
- `animal_media` 媒体元数据与公开查询基础；对象内容仍由 MinIO 保存，本阶段不包含上传流程。
- `timeline_entry` 最小只读投影模型，使用来源类型和来源 ID 保证未来投影幂等；Timeline 不是 Event、Case 或 Adoption 的事实源。
- 按 Campus 浏览、名称/外观关键词搜索、物种筛选、稳定排序和分页，以及公开详情和 Timeline 查询。
- 治理管理员创建、修正和归档主档；归档不会物理删除数据。
- 创建 Animal 时由 `animal-service` 同步调用 `identity-service` 验证 Campus，且治理权限也由 identity-service 返回的当前用户事实判定。客户端提交角色请求头不能授予管理员权限，identity-service 不可用时敏感写操作返回 `503`。

Phase 1B 不包含正式媒体上传、AI、Event、Case、Adoption 流程、Animal Merge、RabbitMQ 或管理端页面。

### Phase 1B API

下表是 animal-service 内部路径。通过 Gateway 访问时在路径前增加 `/animal`。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| GET | `/api/v1/animals?campusId=...&q=...&species=CAT&page=0&size=20` | 按 Campus 分页浏览与搜索 ACTIVE Animal | 公开 |
| GET | `/api/v1/animals/{animalId}` | 查看公开主档与公开媒体元数据 | 公开 |
| GET | `/api/v1/animals/{animalId}/timeline?page=0&size=20` | 按时间倒序查看公开 Timeline | 公开 |
| POST | `/api/v1/admin/animals` | 创建并绑定已启用 Campus 的 Animal | 治理管理员 |
| PATCH | `/api/v1/admin/animals/{animalId}` | 修正允许修改的核心资料 | 治理管理员 |
| POST | `/api/v1/admin/animals/{animalId}/archive` | 将 ACTIVE Animal 归档 | 治理管理员 |

`local` / `dev` 环境会初始化三只示例 Animal：东校区的“小橘”“墨墨”和西校区的“阿黄”，并为其中两只加入少量 Timeline 示例。示例数据只保存 MinIO objectKey 语义，不写入外部随机图片 URL，也不会进入 production 默认 migration。

服务启动后，可通过 Gateway 查询与创建：

```powershell
Invoke-RestMethod 'http://localhost:8080/animal/api/v1/animals?campusId=10000000-0000-0000-0000-000000000001&page=0&size=20'

$adminHeaders = @{ 'X-User-Id' = '00000000-0000-0000-0000-000000000002' }
$body = @{
  campusId = '10000000-0000-0000-0000-000000000001'
  displayName = '新校园成员'
  species = 'CAT'
  sex = 'UNKNOWN'
} | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/animal/api/v1/admin/animals' -Method Post -Headers $adminHeaders -ContentType 'application/json' -Body $body
```

## Phase 1C Campus Circle 基础社区与关注

Campus Circle 是校园内动物近况的主浏览面。Phase 1C 由 `animal-service` 负责，新增 `post`、`post_media`、`comment`、`post_like` 和 `animal_follow` 五张表，实现按 Campus 隔离的公开 Feed、帖子详情与评论读取。Post 可关联同 Campus 的 ACTIVE Animal，也允许 `animalId = null`，但不会因此创建虚假的 UNKNOWN Animal。

发布 Post 和评论必须具备目标 Campus 的 ACTIVE `STUDENT` 或 `ALUMNI` CampusMembership；点赞和关注只要求有效登录。治理管理员可以隐藏 Post。作者昵称通过 identity-service 批量查询，Feed 的点赞数和评论数由 MySQL 聚合，不引入 Redis。Campus Feed 是社区内容，不会写入 `timeline_entry`；Timeline 仍只接受未来正式业务流程产生、带来源的投影。

### Phase 1C API

下表是 animal-service 内部路径；通过 Gateway 访问时在路径前增加 `/animal`。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| GET | `/api/v1/campuses/{campusId}/feed?page=0&size=20` | 按创建时间倒序读取公开 Campus Feed | 公开 |
| GET | `/api/v1/posts/{postId}` | 读取公开 Post 详情 | 公开 |
| GET | `/api/v1/posts/{postId}/comments?page=0&size=50` | 读取公开评论 | 公开 |
| POST | `/api/v1/posts` | 发布 Campus Post | 目标 Campus 有效学生/校友成员 |
| POST | `/api/v1/posts/{postId}/comments` | 评论公开 Post | Post 所属 Campus 有效学生/校友成员 |
| POST / DELETE | `/api/v1/posts/{postId}/like` | 点赞 / 取消点赞 | 当前用户 |
| POST / DELETE | `/api/v1/animals/{animalId}/follow` | 关注 / 取消关注 Animal | 当前用户 |
| GET | `/api/v1/users/me/animal-follows?page=0&size=20` | 分页读取我的 ACTIVE Animal 关注 | 当前用户 |
| POST | `/api/v1/admin/posts/{postId}/hide` | 隐藏 Post，不物理删除 | 治理管理员 |

本地普通示例用户已具有东校区 ACTIVE STUDENT Membership，可直接发布和评论：

```powershell
$userHeaders = @{ 'X-User-Id' = '00000000-0000-0000-0000-000000000001' }
Invoke-RestMethod 'http://localhost:8080/animal/api/v1/campuses/10000000-0000-0000-0000-000000000001/feed'

$postBody = @{
  campusId = '10000000-0000-0000-0000-000000000001'
  animalId = '20000000-0000-0000-0000-000000000001'
  textContent = '今天在教学楼旁看到小橘。'
  media = @()
} | ConvertTo-Json
Invoke-RestMethod 'http://localhost:8080/animal/api/v1/posts' -Method Post -Headers $userHeaders -ContentType 'application/json' -Body $postBody
```

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

## 基础设施与 Phase 0 检查

访问 `http://localhost:8080/actuator/health/liveness`，以及 8081–8085 端口上的同一路径，检查六个服务是否存活。`/actuator/health` 显示依赖状态；五个业务服务还提供 `db` 和 `minio` 健康检查。identity-service 首次启动会依次执行 Phase 0 基线迁移和 Phase 1A 身份模型迁移；`local` / `dev` 还会加载可重复执行的演示数据。Nacos 中应能看到六个服务注册名。

Gateway 将 `/identity/**`、`/animal/**`、`/incident/**`、`/adoption/**` 和 `/intelligence/**` 分别转发到对应服务，并移除路径中的首段服务前缀。Gateway 会接收符合规则的 `X-Trace-Id`（1–64 位字母、数字、下划线或连字符），否则生成 UUID 并向下游传递。例如，访问 `/identity/actuator/health/liveness` 可检查路由是否可用。

本地启动不依赖 Nacos Config。Phase 0.1 使用 `identity-service.yaml` 中的无敏感信息配置验证读取链路；Data ID、Group、Namespace 和检查方式见 [infra/nacos/README.md](infra/nacos/README.md)。数据库密码和 MinIO 密钥通过本地环境变量提供，不放入 Nacos 测试配置。

MySQL 数据卷初始化后，再修改 `.env` 中的数据库密码不会自动修改已有数据库用户。需要显式轮换数据库用户密码，或在确认本地数据可丢弃后重新创建数据卷。

## 自动化测试

完整回归：

```powershell
. ./scripts/load-local-env.ps1
.\mvnw.cmd clean verify
```

只运行 Phase 1A identity-service 测试：

```powershell
.\mvnw.cmd -f backend/pom.xml -pl identity-service test
```

只运行 Phase 1B/1C animal-service 测试：

```powershell
.\mvnw.cmd -f backend/pom.xml -pl animal-service test
```

identity-service 与 animal-service 集成测试使用 Testcontainers 启动隔离的 MySQL 8.0.41。测试覆盖真实 Flyway 迁移、查询/分页、治理权限、客户端角色伪造、Campus 与 Membership 跨服务验证、Animal 生命周期、Campus Feed 隔离、发布、评论、点赞、关注、隐藏、Timeline 边界和数据库约束，因此运行测试前必须启动 Docker Desktop。
