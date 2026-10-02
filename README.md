# AnimalLink

AnimalLink 是一个**基于多模态大模型的校园动物事件协同平台**，连接动物观察、救助协作、领养流转与长期数字档案。平台围绕同一只 Animal 的长期身份，持续记录校园生活、异常救助、公益支持、领养过程及领养后的生活动态。AI 用于辅助理解、生成草稿和提供候选，由人确认，正式业务状态由 Java 服务执行。

当前仓库已完成 Phase 0 工程与基础设施基线、Phase 1A 的身份与校园基础、Phase 1B 的 Animal 核心长期档案、Phase 1C 的 Campus Circle 基础社区与关注能力，以及 Phase 2A–2C 的多模态解析、Animal Candidate Matching、人工身份确认与正式观察 Post 闭环。项目范围以正式的[产品需求文档 V2.1](docs/product/AnimalLink-PRD-V2.1.docx)、[技术设计 V1.0](docs/technical/AnimalLink-Technical-Design-V1.0.docx)及[项目规则](AGENTS.md)为准。

## Phase 3A 事件与证据基础

Phase 3A 由 `incident-service` 负责，完成了 `EventDraft → 人工确认 → Event → Rescue List / Detail → Evidence → Media → Governance` 的正式事件闭环。`EventDraft` 不是 Event 状态；AI 或低置信候选不能自动创建 Event。

已完成能力：

- EventDraft 的创建、读取、更新和提交；提交会正式化为初始 `REPORTED` Event，并保证重复提交返回同一 Event。
- AI Task、MatchingRecord、Post 来源校验；Animal 可为空，支持 UNKNOWN-compatible 报告流程。
- 按 Campus 隔离的救助列表、Event 详情、稳定分页与筛选；默认救助列表仅显示 `REPORTED`、`VERIFIED`。
- 服务端位置隐私：公开/非相关用户仅见公开位置；Reporter 与治理管理员按既定权限看到精确位置和坐标。
- Evidence 的追加、分页、位置隐私与正式媒体；仅 `REPORTED`、`VERIFIED` Event 接受 Evidence。
- EventDraft、Event 与 Evidence 媒体均使用私有 MinIO object；正式化路径确定，读取仅提供短期签名 URL。
- 严格 Event 状态机：`REPORTED → VERIFIED → ARCHIVED`，以及从 `REPORTED` 到 `REJECTED` / `DUPLICATE` 的受控治理操作。
- MySQL + Flyway 集成测试覆盖 Draft、来源、Animal、Event、Evidence、媒体、隐私、状态机、幂等性与约束；真实 Gateway runtime smoke 已验证六个服务、MinIO 上传/正式化及完整 Phase 3A 链路。

`Event != Case`：Phase 3B-a 已完成 Campus-scoped VolunteerMembership 授权基础；Case collaboration、CaseAction、Support、Timeline result projection 和 Adoption 仍未实现。Case 不会由当前代码自动创建。

## Phase 3B-a 志愿者授权基础

`identity-service` 已支持有效 STUDENT / ALUMNI CampusMembership 申请志愿者身份，治理管理员审核或撤销，以及志愿者本人暂停、恢复和退出。VolunteerMembership 与 SystemRole、CampusMembership 及后续 CaseParticipation 保持独立；本阶段未实现 Case 或认领流程。

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

## Phase 2A 多模态观察解析基础

Phase 2A 由 `intelligence-service` 负责。用户提交私有 MinIO objectKey、文字、Campus，以及可选的位置描述和发生时间；服务验证当前用户在目标 Campus 的有效学生或校友身份后，同步调用可替换的多模态模型客户端，生成可编辑的结构化观察草稿。

模型原始响应和经 Java 严格 Schema 校验后的原始草稿保存在 `ai_result`，用户确认或编辑后的版本单独保存在 `ai_confirmation`，不会覆盖模型原文，也不会自动创建 Animal、Post、Event 或其他正式业务记录。任务状态只允许 `PENDING → RUNNING → SUCCEEDED/FAILED`。解析失败会保留错误码并允许用户改为手工填写；不确定信息必须使用 `UNKNOWN` 或空值。

当前结构化草稿包括物种、性别、毛色、显著特征、可见状态、行为、估计数量、可见异常标记、用户提供的位置与时间、总体/字段置信度、警告和未知字段。提示词固定为 `animal-observation-v1`，明确禁止疾病诊断、治疗建议、危险等级断言和从图片推断精确位置。

### Phase 2A API

下表是 intelligence-service 内部路径；通过 Gateway 访问时在路径前增加 `/intelligence`。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| POST | `/api/v1/ai/animal-observation/parse` | 解析媒体与文字，创建结构化观察草稿 | 目标 Campus 有效学生/校友成员 |
| GET | `/api/v1/ai/tasks/{taskId}` | 查看本人 AI 任务、原始草稿及确认结果 | 任务所有者 |
| POST | `/api/v1/ai/tasks/{taskId}/confirm` | 确认或编辑成功的草稿；不创建正式业务实体 | 任务所有者 |

本地默认使用确定性的 `mock` provider，不需要外部模型凭据。`local` / `dev` 启动时会尝试在 intelligence 私有 bucket 中创建 `ai-input/demo-observation.png` 演示对象。可通过 Gateway 验证完整流程：

```powershell
$userHeaders = @{ 'X-User-Id' = '00000000-0000-0000-0000-000000000001' }
$parseBody = @{
  campusId = '10000000-0000-0000-0000-000000000001'
  mediaObjectKeys = @('ai-input/demo-observation.png')
  text = '教学楼东侧看到一只橘白猫，走路似乎不太自然。'
  locationDescription = '教学楼东侧'
  occurredAt = (Get-Date).ToUniversalTime().ToString('o')
} | ConvertTo-Json
$task = Invoke-RestMethod 'http://localhost:8080/intelligence/api/v1/ai/animal-observation/parse' -Method Post -Headers $userHeaders -ContentType 'application/json' -Body $parseBody
Invoke-RestMethod "http://localhost:8080/intelligence/api/v1/ai/tasks/$($task.taskId)" -Headers $userHeaders
```

真实 OpenAI-compatible provider 通过环境变量启用：设置 `AI_PROVIDER=openai-compatible`、`AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL_NAME`、`AI_CONNECT_TIMEOUT_MS` 和 `AI_READ_TIMEOUT_MS`。真实密钥只放在未提交的 `infra/.env` 或部署环境中；代码、日志和数据库都不会记录 API Key。当前自动化测试和本地默认流程不依赖外部模型服务。

## Phase 2B Animal Candidate Matching

Phase 2B 由 `intelligence-service` 负责匹配编排、评分、Embedding 缓存和运行记录，`animal-service` 只通过一个内部批量接口提供最小候选快照。匹配只能从 Phase 2A 已成功且已由任务所有者确认的草稿启动，不接受客户端任意拼装特征，也不会自动绑定、创建或合并 Animal。

匹配采用两阶段设计：第一阶段由 animal-service 按 Campus、ACTIVE 状态和已知物种召回小规模候选；物种为 `UNKNOWN` 时跳过物种硬过滤。第二阶段在 intelligence-service 中计算图片、外观特征、地理区域、时间/历史四类分数，仅对当前有效且实验组启用的维度重新归一化权重，稳定排序后返回 Top-K。候选卡片同时返回各维分数、解释原因和缺失维度；没有候选时返回空列表，最高分不足时返回 `lowConfidence=true` 和 `NO_STRONG_MATCH`，始终保留“都不是/不确定”的产品选择。

Animal 图片 Embedding 仅从公开且受信任的 AnimalMedia 图片生成，以 Animal、媒体、模型名和模型版本为唯一缓存键；观察图片按匹配请求即时计算。默认 `mock` provider 是确定性的本地实现。真实 provider 使用独立的图像 Embedding `/embeddings` 适配器，不复用多模态聊天接口，需由所选供应商明确支持图像输入。

### Phase 2B API

下表是服务内部路径；公开调用通过 Gateway 时分别增加 `/intelligence` 或 `/animal` 前缀。animal-service 的内部候选接口只供受控服务间调用，不是客户端搜索接口。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| POST | `/api/v1/ai/tasks/{taskId}/match-candidates` | 基于已确认任务创建一次独立匹配记录，`topK` 默认 3、范围 1–10，实验组默认 D | 任务所有者 |
| GET | `/api/v1/ai/matches/{matchingRecordId}` | 查看本人某次匹配快照、分数和解释 | 匹配记录所有者 |
| POST | `/internal/v1/animals/candidates` | 按 Campus、ACTIVE 和可选物种批量读取最小候选快照 | intelligence-service 内部调用 |

实验组 A/B/C/D 分别启用“图片”“图片 + 外观”“图片 + 外观 + 地理”“图片 + 外观 + 地理 + 时间/历史”。每次匹配都在 `matching_record` 中保存算法版本、权重版本、实验组和完整权重，不会覆盖历史运行。`matching_candidate` 保存返回时的 Animal 名称、物种、封面 objectKey 和各维评分快照；`animal_embedding` 使用 MySQL JSON 保存向量并由 Java 计算余弦相似度，本阶段不引入向量数据库。

完成 Phase 2A 的确认后，可继续通过 Gateway 请求匹配：

```powershell
$matchBody = @{ topK = 3; experimentCode = 'D' } | ConvertTo-Json
$match = Invoke-RestMethod "http://localhost:8080/intelligence/api/v1/ai/tasks/$($task.taskId)/match-candidates" -Method Post -Headers $userHeaders -ContentType 'application/json' -Body $matchBody
Invoke-RestMethod "http://localhost:8080/intelligence/api/v1/ai/matches/$($match.matchingRecordId)" -Headers $userHeaders
```

Embedding 配置使用 `EMBEDDING_PROVIDER`、`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL_NAME`、`EMBEDDING_MODEL_VERSION`、`EMBEDDING_CONNECT_TIMEOUT_MS` 和 `EMBEDDING_READ_TIMEOUT_MS`。召回上限通过 `MATCHING_RECALL_LIMIT` 配置，允许 20–100，默认 50；A/B/C/D 各维权重通过 `MATCHING_WEIGHT_<实验组>_<维度>` 配置，默认值见 `infra/.env.example`。未配置真实凭据时保持 `EMBEDDING_PROVIDER=mock`。

## Phase 2C 人工身份确认与正式观察记录

Phase 2C 完成 `解析 → 人工确认草稿 → Candidate Matching → 人工身份决策 → 正式 Post` 闭环。身份决策由 `intelligence-service` 编排并保存不可变事实，正式 Post、永久媒体以及待治理的新身份建议由数据所有者 `animal-service` 在本地事务中创建。AI 只提供候选，不会自动绑定、合并或创建 Animal；`lowConfidence=true` 也不会替用户决定。

三种决策具有明确且不同的结果：

- `SELECT_EXISTING`：只能选择本次 Top-K 快照中的候选。系统再次确认 Animal 为同 Campus 的 ACTIVE 主档，创建绑定该 Animal 的正式 Post，并把输入媒体复制到 animal-service 的永久对象路径。
- `NO_MATCH`：创建 `animalId = null` 的正式 Post，同时创建 `PENDING_REVIEW` 的 `AnimalIdentityProposal`。普通用户不会因此获得创建 Animal 的能力。
- `UNSURE`：只保存“不确定”的匹配决策，不创建 Post，也不创建 Proposal，用户可结束本次识别而不污染正式业务数据。

每个 matching record 最多拥有一条决策。相同请求重试返回同一结果，不同决策重试返回冲突。animal-service 另以来源 matching record 和决策摘要进行幂等保护；跨服务不使用分布式事务或消息队列。媒体先复制到确定性永久 objectKey，随后 Post、PostMedia、Proposal 和幂等记录在同一 MySQL 本地事务中写入，因此数据库失败时不会留下部分业务记录，重试也不会生成重复媒体路径。

治理管理员可以审核 NO_MATCH 产生的 Proposal：批准并创建新 Animal、关联同 Campus 的现有 ACTIVE Animal，或拒绝建议。批准或关联时，Proposal 状态更新和原 Post 的 `animalId` 回填在 animal-service 同一事务中完成，并通过行锁与条件更新保证并发审核只有一个成功。此流程不创建 Event、Case 或 TimelineEntry。

匹配决策同时保存实验评估 ground truth：`SELECT_EXISTING` 记录所选 Animal、候选排名和分数，并计算 `hitAt1`、`hitAt3`、`hitAtK`；`NO_MATCH` 和 `UNSURE` 保留决策标签，其命中指标为空。历史匹配快照不会被覆盖。

### Phase 2C API

公开 API 通过 Gateway 访问时分别增加 `/intelligence` 或 `/animal` 前缀。以 `/internal` 开头的接口仅供服务间调用，Gateway 会拒绝客户端访问。

| Method | Path | 用途 | 认证 |
| --- | --- | --- | --- |
| POST | `/api/v1/ai/matches/{matchingRecordId}/finalize` | 提交 SELECT_EXISTING、NO_MATCH 或 UNSURE 人工决策 | 匹配记录所有者且具备目标 Campus 有效成员身份 |
| GET | `/api/v1/ai/matches/{matchingRecordId}/decision` | 查看本人的不可变决策及实验命中指标 | 匹配记录所有者 |
| POST | `/internal/v1/observation-finalizations` | 创建幂等正式 Post、永久媒体及可选 Proposal | intelligence-service 内部调用 |
| GET | `/api/v1/admin/animal-identity-proposals` | 分页查看 Proposal，可按状态筛选 | 治理管理员 |
| GET | `/api/v1/admin/animal-identity-proposals/{proposalId}` | 查看 Proposal、来源 Post 和媒体详情 | 治理管理员 |
| POST | `/api/v1/admin/animal-identity-proposals/{proposalId}/approve-create` | 批准建议，创建 Animal 并回填来源 Post | 治理管理员 |
| POST | `/api/v1/admin/animal-identity-proposals/{proposalId}/link-existing` | 关联同 Campus ACTIVE Animal 并回填来源 Post | 治理管理员 |
| POST | `/api/v1/admin/animal-identity-proposals/{proposalId}/reject` | 拒绝新身份建议，保留未绑定 Post | 治理管理员 |

例如，在 Phase 2B 获得 `$match` 后选择候选：

```powershell
$finalizeBody = @{
  decisionType = 'SELECT_EXISTING'
  selectedAnimalId = $match.candidates[0].animalId
  postText = '今天在教学楼旁再次看到这只猫。'
} | ConvertTo-Json
$decision = Invoke-RestMethod "http://localhost:8080/intelligence/api/v1/ai/matches/$($match.matchingRecordId)/finalize" -Method Post -Headers $userHeaders -ContentType 'application/json' -Body $finalizeBody
Invoke-RestMethod "http://localhost:8080/intelligence/api/v1/ai/matches/$($match.matchingRecordId)/decision" -Headers $userHeaders
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

只运行 Phase 1B/1C/2C animal-service 测试：

```powershell
.\mvnw.cmd -f backend/pom.xml -pl animal-service test
```

只运行 Phase 2A/2B/2C intelligence-service 测试：

```powershell
.\mvnw.cmd -f backend/pom.xml -pl intelligence-service test
```

identity-service、animal-service 与 intelligence-service 集成测试使用 Testcontainers 启动隔离的 MySQL 8.0.41。除既有身份、Animal、Campus Circle 和 Phase 2A 回归外，Phase 2B 测试覆盖 Campus/ACTIVE/物种召回、UNKNOWN、空候选、四维评分、缺失维度重归一化、稳定 Top-K、A/B/C/D 实验记录、Embedding 模型版本缓存与并发唯一保护、运行历史、所有者隔离，以及依赖不可用、超时和非法向量错误。Phase 2C 测试覆盖三种人工决策、候选与 Campus/状态校验、权限失败关闭、跨服务错误、幂等冲突、永久媒体、Proposal 三种治理结果、并发审核、事务回滚和实验 ground truth。因此运行测试前必须启动 Docker Desktop。
