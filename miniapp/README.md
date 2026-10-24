# AnimalLink 微信小程序 MINI-1A

本目录是原生微信小程序（TypeScript + WXML + WXSS）工程。当前阶段完成 Campus 选择、Animal 公共档案读取链路，以及 Campus Circle 社区 Feed、帖子详情与公开评论；Rescue、My 仍只提供诚实的后续扩展骨架。

## 运行模式

默认配置位于 `config/runtime.ts`。Animal 与 Community 使用独立开关，允许只把已审核的 Animal 读取切到真实 API，而让尚未开放的 Community 保持离线演示：

- `animalDataMode`：`offline-demo` 或 `api`。
- `communityDataMode`：`offline-demo` 或 `api`；当前 Gateway 未开放 Community 公共读取，默认保持 `offline-demo`。
- `gatewayBaseUrl`：`api` 模式使用的 Gateway 地址。

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
- `GET /animal/api/v1/public/animals`（`campusId` 必填，`page` 从 0 开始）
- `GET /animal/api/v1/public/animals/{animalId}`
- `GET /animal/api/v1/animals/{animalId}/timeline`（当前未匿名开放；详情仍显示 Animal，并明确提示“动态暂未开放”）
- `GET /animal/api/v1/campuses/{campusId}/feed`
- `GET /animal/api/v1/posts/{postId}`
- `GET /animal/api/v1/posts/{postId}/comments`

匿名 Animal DTO 不提供媒体对象、位置、自由文本、内部身份状态或合并映射；页面使用本地占位图，不拼接 MinIO 地址。Community 接口仍未在 Gateway 匿名开放。正式微信登录、社区发布、点赞、评论写入、救助上报、领养操作和个人身份均未实现。
