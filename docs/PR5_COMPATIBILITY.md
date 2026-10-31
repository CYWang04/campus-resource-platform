# PR #5 主干兼容整合

日期：2026-10-31。状态：本机验证通过，随本次整合提交更新 PR #5，待远端 CI 和人工/受影响组评审。用户明确要求根据其交接说明处理当前 PR 冲突，本轮在此授权范围内整合；不合并到 main、不联系团队成员、不重做前端。

## 范围、约束与方案

- PR 源版本 `fc590f8`；说明中的 main 为 `6e2a8a9`，实际已更新至 `d99137e`（再合入 PR #3）。以实际主干为准，保留 PR #2 的 M2 迁移/测试、PR #4 的前端和 PR #3 的 M3 成果。
- M2 沿用主干 V3__maintenance_cancellations.sql 的原始内容、application_cancellation_events 与 application_notification_tasks，不保留 PR 中另一份 V3，不重复建表。M1 V5 保留。
- 取消契约统一为单数 maintenance-cancellation，并提供稳定 reason；生产端、接收端、测试和 OpenAPI/交接同时核对。
- 以 main 的取消逻辑及 SUPERSEDED 任务状态为基础，接入 M1 的 currentSourceState 判断；回调或同步响应触发取消均复用同一本地事务逻辑，先锁申请再锁任务，通知补齐 application_id，仅保留主干独立通知 Worker。
- 前端与此次 main 基线逐字节一致；需要页面适配的内容继续写 M1_FRONTEND_HANDOFF，不改页面。既有 M2/M3 测试不删除。
- 已应用 PR 旧 M2 V3 的本机库不能直接视为主干 V3。保留旧容器/卷和迁移历史，不 repair、不改历史、不删共享数据；使用隔离 Compose/数据库分别验证空库与主干库升级。

## 验收计划

1. 后端全部现有测试、补充的取消先后顺序/通知唯一性测试、前端构建及契约/链接检查。
2. 隔离空库启动、重复启动，验证迁移版本唯一且不重复建表。
3. 先用 main 初始化另一套隔离库，再升级到整合版，保留申请/通知及 Flyway 历史并核对数据。
4. 整合版完整 smoke、M1 regression、resilience，验证正常批准/撤销、重放、离线恢复、单事件幂等、取消终态、多来源解除、通知及审计。
5. 验证通过后向 M1 新增包含主干父提交的整合提交，更新现有 PR #5；保留原提交，不强推，不宣称本机结果等于 GitHub CI 或联合验收。

## 实际结果

| 检查 | 实际结果 |
| --- | --- |
| 全后端 `scripts/maven.sh -B verify` | M1 10、M2 23、M3 45，共 78 项通过；M2 原 16 项场景保留，新增 7 项覆盖两种到达顺序、唯一通知/审计、陈旧任务、已解除来源、撤销交错、未知状态及同步取消回滚 |
| `node --test scripts/test-slots.test.mjs` | 4 项通过 |
| `npm run build --prefix web` | 类型检查及生产构建通过；JS 1022.69 kB / gzip 338.74 kB，保留现有大包提示 |
| main 库升级 | 使用 d99137e 先初始化 campus-pr5-main-upgrade，真实 API 建立已批准/已取消申请和通知；升级后 M2 Flyway 历史完全相同，M1 旧历史保留且只新增一次 V5，申请/回执/原通知保留，回调重放、动态策略和正常撤销通过；重复启动后再次通过 |
| 空库初始化/重复启动 | campus-pr5-clean 六服务健康；M1 V1～V5、M2 V1～V3、M3 V1～V3，版本唯一；再次启动后的迁移及 checksum 完全相同 |
| `TZ=UTC ... node scripts/smoke.mjs` | 178 次 HTTP/断言检查通过，含正常批准/撤销、个人预约/签到/违约、M3 维修闭环、通知审计和入口隔离 |
| `... node scripts/m1-regression.mjs` | 94 次 HTTP/故障检查通过，含 M2 离线恢复、重放/同事件异内容、取消终态、迟到任务、多维修来源与失效提醒 |
| `... node scripts/resilience.mjs` | 依赖恢复、身份故障关闭、重启持久化、历史/当前来源、旧版本拒绝、数据库账号隔离及开发邮件捕获通过；结束后六服务健康 |
| 契约和源码 | OpenAPI 0.5.0，47 路径、328 个内部引用可解析；单数路径及请求 schema 与 main 一致。main 的 M2 V3、M3 全部源码/测试、web 全部文件逐字节保留 |
| 浏览器必要页面 | 演示学生登录、空间/预约/申请/通知页可读；维修取消通知可见，申请页仍显示原始 CANCELLED_MAINTENANCE，预约页仍写死 7 天/4 小时；差距按 M1_FRONTEND_HANDOFF 保留待办。检查后注销，不算完整人工业务验收 |
| 运行前端比对 | Nginx 提供的 CSS/JS 与本机 main 前端构建逐字节一致 |

HTTP 计数包含轮询，不等于独立用例数量。原始证据留忽略目录 `.work/pr5-compat-2026-10-31/`，不提交运行日志、库快照、本机配置或凭证。

### 复验入口与本机数据边界

主干升级检查脚本为 [m2-upgrade-check.mjs](../scripts/m2-upgrade-check.mjs)，只允许显式的 campus-pr5-* 项目。先用 d99137e 源码和独立 Compose 项目初始化 main 库，再在仓库根运行 `COMPOSE_PROJECT_NAME=campus-pr5-main-upgrade node scripts/m2-upgrade-check.mjs prepare`；该步骤保存迁移和业务数据基线且拒绝覆盖已有证据。停止/升级时沿用同一个隔离项目名和数据库卷，用整合源码 `up --build -d --wait` 完成后执行相同前缀的 `node scripts/m2-upgrade-check.mjs verify`；再次 `up -d --wait` 后可重复 verify。prepare 的取消回调是对接收端的模拟输入，verify 会释放对应的 M1 测试来源；真实 M1 事件生产由 M1 regression 覆盖。

空库及三份完整集成脚本均显式使用 `COMPOSE_PROJECT_NAME=campus-pr5-clean`，等待 Compose `--wait` 完成后串行运行，不能与升级环境同时占用 8080/8025。当前平台 [8080](http://127.0.0.1:8080)、开发邮件 [8025](http://127.0.0.1:8025) 来自 campus-pr5-clean；旧 campus-resource 和升级验证项目均已停止并保留数据库卷。日常操作此运行实例也需带 `-p campus-pr5-clean` 或同名环境变量；直接启动默认旧项目会碰到旧 V3 不兼容及端口占用。

旧 PR V3 库未做数据兼容转换；不要直接 repair 或套用主干 V3。需保留的旧数据应由环境负责人制定转换方案后单独验收。完整 RBAC、真实 SMTP、利用率、Agent、页面接入、CodeArts/云发布及团队评审继续待办；本次不扩大范围。
