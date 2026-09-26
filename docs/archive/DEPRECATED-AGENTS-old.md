# AnimalLink 工程规则

## 1. 项目定位与文档优先级

AnimalLink 连接动物长期资料与现实事件协作处理。后续开发以完整闭环、可追溯性和可靠规则为先，不以堆叠技术为目标。

发生冲突时，按以下优先级执行：

1. 最新 Technical Design Amendment / 明确标记为已冻结的决策。
2. 最新 PRD V1.2 的明确决策。
3. Technical Design V1。
4. 其他旧文档。

每个任务开始前，阅读与改动相关的正式设计，确认所属 Phase、范围、验收条件和依赖模块。设计缺失或正式文档存在无法判断的冲突时，记录并报告；不要自行重新设计。不要把已删除的旧 Prototype、截图、源码或 Git 历史作为需求或实现来源。

## 2. 技术与目录基线

采用 Monorepo 和模块化单体（Modular Monolith）：

```text
AnimalLink/
├── server/                 # Java 21、Spring Boot 3.x、JPA
├── miniapp/                # 原生微信小程序
└── web/
    ├── collaborator/       # Vue 3、TypeScript、Vite、Element Plus
    └── admin/              # Vue 3、TypeScript、Vite、Element Plus
```

- 三端共享一个 Spring Boot 后端；数据使用 PostgreSQL，对象存储使用 MinIO。
- Web 两端共享基础组件、API Client、认证和工程配置；不得复制出独立后端或割裂的数据模型。
- 保持模块边界和依赖方向。跨模块业务由 Application 层编排；不要从 Controller、Repository 或前端直接拼接跨模块流程。

## 3. 领域与状态规则

- `Animal` 是长期存在的动物主体；`Event` 是现实发生或被观察到的事实；`Case` 是多人、多步骤协作过程；`Timeline` 是带来源的 Animal 长期历史投影。
- `Event != Case`，且不是所有 Event 都创建 Case。`Timeline != Community Feed`，不得用 Timeline 覆盖或替代原始来源对象。
- `UNKNOWN` 是合法状态，身份不确定时保留 UNKNOWN，等待后续人工确认或治理；不得强行归属。
- 状态迁移、责任人变更、结果确认均由后端校验和执行。遵守已冻结的 Event、Case、LostCase 状态机；不得由前端绕过迁移规则。
- 关键写操作必须考虑幂等、并发冲突和审计。Claim 必须原子化，同一时刻只能有一个有效主责任人。Timeline 写入必须可追溯、可幂等，并与来源和关键状态变更保持一致。
- 治理 Merge 必须先预览冲突和影响范围；保留 source 的 MERGED 标记及审计，禁止物理删除历史来源。

## 4. AI、数据与事务

核心原则：**AI 负责理解现实，人负责确认，Java 负责执行现实。**

- AI 只能生成可编辑的 EventDraft、候选排序和解释；不能自动确认 Animal 身份、医疗结论、现场事实、Case 结果或 LostCase 找回。
- AI 的 confidence 是提取置信度，不是现实事实或身份概率。Candidate Matching 必须提供依据、允许全部拒绝，并保留 UNKNOWN 回退。
- AI 不可用、超时或 schema 失败时，保留用户输入并允许手动完成流程；外部 AI 调用不得放在数据库长事务或锁内。
- 对关键状态、责任人、Timeline 和审计的同一业务变更保持事务一致性；媒体由 MinIO 管理，访问权限、类型和大小由服务端控制。

## 5. 权限与隐私

- 所有登录态、角色、资源关系、业务状态和可见范围均由服务端校验。
- AnimalRelation 是资源权限的一部分；宠物主人不是独立系统角色。
- 协作者仅能在已审核服务范围内处理任务所需信息；治理人员的查看、干预、授权和 Merge 均需审计。
- 精确位置按最小必要原则开放；普通浏览显示粗粒度位置。用户拒绝定位或定位失败时必须可用手动地点完成任务。

## 6. 范围控制

V1 只保证两条主要闭环：

1. 校园动物异常事件协同。
2. 家庭宠物走失与目击线索协同。

`CITY_STRAY` 仅复用既有 Animal / Event / Case / Timeline 模型，不建设第三套大型业务系统。

未经新的正式设计变更，不引入微服务、Redis、MQ、Elasticsearch、Vector DB、PostGIS、分布式锁、Event Sourcing、复杂 Agent Framework、自训练 Re-ID、自建动物身份识别模型、区块链、IoT、数字孪生、知识图谱、IM / Live、完整医院系统、完整领养商城或外部救助机构依赖。

## 7. 测试与交付方式

- 优先测试状态机、Event Verification、并发 Claim、Timeline 幂等与来源追踪、AI schema failure / fallback、Candidate Matching，以及两条核心 E2E。
- 不为覆盖率堆砌没有业务价值的 CRUD 测试。修改后运行与变更相称的测试，并说明实际结果。
- 不跨 Phase 偷做后续功能，不自行扩大 Scope；优先复用现有模块能力。
- 完成任务时简要汇报修改内容、设计依据、验证结果和仍需人工决定的事项。
