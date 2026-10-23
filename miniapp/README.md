# AnimalLink 微信小程序 MINI-1A

本目录是原生微信小程序（TypeScript + WXML + WXSS）工程。当前阶段完成 Campus 选择、Animal 公共档案读取链路，以及 Campus Circle 社区 Feed、帖子详情与公开评论；Rescue、My 仍只提供诚实的后续扩展骨架。

## 运行模式

默认配置位于 `config/runtime.ts`：

- `offline-demo`：只使用本目录中的明确标注模拟数据，不访问网络；页面顶部始终显示“本地离线演示”。
- `api`：通过配置的 Gateway Base URL 调用现有公共读取接口。切换此模式前，需要把 `gatewayBaseUrl` 设置为开发环境实际地址，并在微信开发者工具中按开发环境要求配置合法域名。

代码不会发送 `X-User-Id`、`X-Internal-Service` 或伪造认证信息。离线模式也不会静默回退：API 模式请求失败时直接显示错误与重试。

## 导入与验证

```powershell
cd miniapp
npm install
npm run typecheck
npm test
```

在微信开发者工具中导入 `miniapp` 目录。仓库中的 `touristappid` 只用于无真实 AppID 的本地工具预览；真实项目 AppID 应放在开发者本地配置中，不要提交私密项目配置。

## 接口边界

- `GET /identity/api/v1/campuses`
- `GET /animal/api/v1/animals`（`campusId` 必填，`page` 从 0 开始）
- `GET /animal/api/v1/animals/{animalId}`
- `GET /animal/api/v1/animals/{animalId}/timeline`
- `GET /animal/api/v1/campuses/{campusId}/feed`
- `GET /animal/api/v1/posts/{postId}`
- `GET /animal/api/v1/posts/{postId}/comments`

当前公共 Animal 与 Community DTO 只提供媒体对象元数据，没有可信的公开媒体访问 URL。页面因此使用本地占位图，不拼接 MinIO 地址。正式微信登录、社区发布、点赞、评论写入、救助上报、领养操作和个人身份均未实现。
