# 接口与跨模块规则草案

## 2026-10-31 取消接口统一说明

本地整合以 POST `/internal/v1/room-applications/{id}/maintenance-cancellations` 为主路径，原单数路径为兼容别名，均由同一控制器/事务处理。M1 原四字段载荷保持可用，reason 可省略并归一为“教室紧急维修”，显式空白拒绝；两路径共用事件 ID 指纹和去重范围。回应 data 包含 eventId、applicationId、state。详见 [整合与迁移说明](M2_RECONCILIATION.md)，人工验收与相关组评审仍待完成。

更新日期：2026-10-26。本文是完整目标设计提案，尚未冻结。已实现子集见 [OpenAPI](../contracts/foundation.openapi.json) 和 [实现选择](DECISIONS.md)；本轮增量见末节。未列入子集的端点不能视为已实现。F01/F04 仍需团队评审。

2026-10-30：后端接口保留，前端已按用户要求恢复原版，新增能力尚待页面接入；新增/已有接口差异、字段示例及兼容项统一交给 [M1_FRONTEND_HANDOFF](M1_FRONTEND_HANDOFF.md)。

已确认业务政策见 [DECISIONS](DECISIONS.md) U06～U08；下列具体接口方案仍属 P05/P11。课程原要求见 [REQUIREMENTS](REQUIREMENTS.md)。

2026-10-29 M2 增量：已编写维修取消接收端及机器可读契约，见 [M2 接手任务](M2_BACKEND.md)。M1 事件生产、永久失效与投递尚待接入，不能认为下文整条可靠性链路已完成。新增接口及 schema 仍待相关组评审。

## 统一约定提案

| 项目 | 约定 |
| --- | --- |
| 地址 | 公共 `/api/v1/`，服务内部 `/internal/v1/`；Nginx 按资源组路由，内部不对外暴露 |
| 类型 | JSON、camelCase、UUID 字符串 ID、固定英文业务枚举 |
| 时间 | ISO 8601 携带时区，内部 UTC、页面上海时区；区间 `[startAt,endAt)`，相邻时段不冲突 |
| 分页 | page 从 1 开始，pageSize 默认 20、最大 100 |
| 成功 | `{data, requestId}`；列表 data 为 `{items,total,page,pageSize}` |
| 错误 | `{code,message,fieldErrors,requestId}`；程序按 code 判断，文案给人阅读 |
| 状态码 | 400 参数、401 登录、403 权限、404 不存在、409 业务/版本冲突、503 依赖不可用 |
| 异步 | 202 返回 operationId 和查询入口，状态显示处理中，不能提前显示业务已完成 |
| 幂等 | 创建/状态变更使用 Idempotency-Key，同键同内容返回原结果，同键不同内容返回 409 |
| 追踪 | requestId 贯穿来源操作、HTTP、审计和后台任务，不记录密码和令牌 |
| 版本 | 兼容新增保留 v1；破坏性变更经受影响组评审并同步调用方 |

资源版本冲突、业务时间冲突和网络超时要能区分。OpenAPI 需提供正常、无权限、冲突、处理中及失败样例；模拟数据按契约生成。仅写 Markdown 路径不能标记契约任务完成。

## 外部接口分组

以下路径是设计清单，未实现；F04 决定具体动作、请求体、返回类型和权限。

| 模块 | 核心路径/动作 | 权限要点 |
| --- | --- | --- |
| M2 身份 | POST `/auth/login`、POST `/auth/logout`、GET `/auth/me` | 当前身份由会话读取 |
| M2 管理 | `/admin/users`、`/admin/roles`、`/admin/permissions`、`/departments` | 权限管理及数据范围服务端校验 |
| M1 资源 | `/rooms`、`/rooms/{id}/seats`、`/rooms/{id}/availability`、管理资源接口 | 院系资格；可用性返回资源版本 |
| M1 预约 | `/reservations`、详情、取消、签到、历史复用、统计 | 本人预约；管理操作按权限与范围 |
| M2 申请 | `/room-applications`、详情、审批、驳回、撤销 | 申请资格、审批层级与禁止自审 |
| M3 工单 | `/work-orders`、派单、开始维修、完工、验收、评价 | 操作者角色、状态及管理范围 |
| M3 通知/审计 | `/notifications`、标记已读、`/admin/audit-events` | 本人通知；审计查询受权限限制 |
| 操作结果 | 每服务的操作查询资源 | 路由和归属需在 OpenAPI 固定，避免三个服务抢同一外部路径 |

路径均相对于 `/api/v1`。前期只实现最小链路所需部分，其余明确标注 planned，不能用空成功响应冒充实现。

## 内部接口边界

- M2 提供令牌校验、必要用户信息查询和维修取消申请事件接收。
- M1 提供创建/解除来源限制和按操作编号查询资源变更结果。
- M3 提供通知事件、审计事件的幂等接收。
- 内部请求需服务身份；M2 仅可操作 M2 来源，M3 仅可操作 M3 来源。不能信任前端任意传来的 actorId/sourceType。

资源接口提案：`PUT /internal/v1/resource-effects/{sourceType}/{sourceId}`。

| 字段 | 含义/边界 |
| --- | --- |
| operationId | 同一次动作稳定的全局唯一编号，重试不换号 |
| sourceVersion | 同来源业务动作单调递增版本 |
| roomId | 受影响教室，来源范围保持可追溯 |
| startAt/endAt | 限制时段；维修结束未知时允许开放结束时间，校验规则在契约中固定 |
| desiredState | ACTIVE / RELEASED |
| expectedAvailabilityVersion | M2 新增占用的资源版本前置条件；具体适用动作待契约明确 |
| reason | 业务解释及审计原因，不包含敏感凭证 |

个人预约来源由 M1 内部维护。调用结果需同时说明该操作的历史结果与来源当前是否仍有效；不能只返回旧 APPLIED 就让已被维修取消的申请恢复通过。

## 已确认的业务边界

1. M1 是资源是否可用的最终判断方；M2/M3 不直接修改其数据库。
2. 审批不抢占个人预约。资源冲突时不能同时批准并让学生预约继续有效。
3. 维修优先取消受影响个人预约及已批准整室申请，通知用户且不记违约。解除维修后申请需重提。
4. 座位状态记录来源，解除一个来源只影响该来源，不能直接覆盖为“可用”。

## 待评审的业务细节

| 规则 | 当前建议默认 |
| --- | --- |
| 待审批 | 不预占；最终占用成功后才显示审批通过 |
| 审批冲突 | 本次申请以资源冲突结束，修改后使用新申请 ID |
| 审批时限 | 两级，每级 24 小时且不晚于预约开始，禁止自审 |
| 预约窗口 | 未来 7 天、受开放时间与院系资格约束；单次默认最多 4 小时可配 |
| 签到 | 每日教室关联码；开始至开始后 15 分钟之前 |
| 维修时段 | M1 接受时开始，至验收通过；结束未知，影响当前及所有未来重叠预约/占用 |
| 取消后恢复 | 同一申请 ID 不恢复；重提新 ID |
| 工单角色与时限 | 资源管理员验收、不自验；未开工 24 小时/未完工 48 小时升级 |
| 普通资源调整 | 影响有效预约时拒绝；紧急限制走维修 |
| 签到故障 | 记录中断与受影响预约，转人工核查，不补记自动违约 |

其中维修影响“全部未来预约”的解释必须在 F01 明确认可。不能把这些默认值写成学校规定。

## 可靠性目标与实现样例要求

拟采用本地事务+待同步任务（outbox）+幂等 HTTP，不引入消息中间件。发送方先落库业务处理中状态和任务；接收方在事务内处理资源、操作结果、审计和后续事件；发送方确认后收敛终态。

网络超时意味着结果未知，查询或重试同一个 operationId；明确冲突意味着失败，不自动换版本重试到成功。前期至少实现并验证一次重复请求和按来源解除；完整异常矩阵在分工后继续补齐。

并发方案为 M1 对同教室使用统一事务锁顺序，串行预约、整室占用、维修和相关状态修改。锁内事务不等待外部 HTTP，跨服务用已落库任务异步衔接。不同教室可独立执行，死锁/锁超时须有明确结果与有限重试。

### 维修与审批交错的终态保护

- M1 在同一本地事务内添加维修限制、取消受影响预约、让已生效整室占用失效，并保存向 M2 发送取消事件的任务。
- M2 取消接收也处理“审批同步中”的申请，事务内事件去重、条件更新、审计和通知任务落库后确认。
- M1 对维修失效来源保留原因和终态；更大的 sourceVersion 不能恢复同一个申请。
- M2 已因维修取消后，迟到的审批成功响应不能覆盖取消状态，更新必须检查当前状态/版本。
- 解除先到时保存来源版本与解除记录，迟到旧添加不能激活。
- M2 新增占用固定发起时的资源版本；维修开始/解除、资源规则变更推进版本。维修前发出而验收后到达的旧请求必须冲突，不能悄悄重新占用。
- M1 生效后即决定真实可用性，M2 展示允许短暂滞后；取消回调可靠重试、失败可查，不宣称三服务实时原子一致。

## 后续契约任务必须补齐

F04 至少固定：来源枚举、资源版本推进点、幂等键作用域及保存周期、操作结果查询的路由与权限、校验字段、错误码、状态机终态、服务调用凭证格式，以及正常/冲突/重试/乱序示例。详细契约须受影响组评审，当前草案不能替代评审。

## 2026-10-26 已实现增量（OpenAPI 0.4.0，待评审）

| 接口 | 权限与行为 |
| --- | --- |
| GET `/api/v1/reservation-policy` | `resources:read`；返回 maxDurationHours、maxAdvanceDays、version |
| PUT `/api/v1/admin/reservation-policy` | `resources:manage`；携带当前 version，旧版本返回 POLICY_VERSION_CONFLICT；已有预约不追溯变更 |
| GET `/api/v1/admin/rooms`、`/admin/rooms/{id}/seats` | `resources:manage`；含停用资源，供维护使用 |
| GET `/api/v1/rooms/{id}/check-in-code` | `resources:manage`；学生返回 403；仅返回指定教室上海日期的每日码 |
| GET `/api/v1/admin/resource-audit-events` | `audit:read`；最近 100 条 M1 资源/预约事件 |
| GET `/api/v1/admin/resource-sync-tasks` | `resources:manage`；最近 100 条维修取消回调状态及安全错误摘要 |
| POST `/internal/v1/room-applications/{id}/maintenance-cancellations` | 服务密钥；eventId、roomId、sourceVersion、maintenanceSourceId；同事件同内容去重，不同内容 409 |

资源公开查询支持楼宇、插座和成对 startAt/endAt；有时段时按当前策略校验。未传时段查询当前状态。列表、座位详情和创建共用院系/启用/开放规则；实际创建在锁内再次核验。院系空值表示全校，资源管理员可查看全部院系。资源编辑的 ACTIVE_RESERVATIONS 冲突包含有效整室占用；移动座位返回 SEAT_ROOM_IMMUTABLE。

内部资源操作结果同时返回历史 `sourceState` 与实际 `currentSourceState`。例如旧 ACTIVE 操作成功后被维修取消，再次重放仍返回历史 ACTIVE，但 currentSourceState=CANCELLED 并带 invalidatedBy。M2 必须以当前状态收敛，不能凭历史 APPLIED 恢复批准。CANCELLED 来源收到新的 ACTIVE 返回 SOURCE_TERMINAL，RELEASED 不抹除取消终态。来源范围不可改换教室。

M1 的 resource_cancellation_tasks 和 M2 的 application_resource_events/application_notification_tasks 构成取消闭环。接收方先锁申请、后锁同步任务；HTTP 位于本地事务之外，连接/读取超时为 3/5 秒，失败持久化后递增重试间隔，最长 60 秒。并不保证三个服务同一时刻原子提交。

预约列表新增 displayStatus；已结束的已签到记录展示 COMPLETED，原 status 仍为 CHECKED_IN。统计支持 from/to（含首尾的上海日期，按 startAt 归属）及 roomId；byStartHour 为上海小时，byRoom.reservationHours 排除取消和违约。利用率、审计汇总和完整筛选仍未实现。

上文“内部身份按来源隔离”仍是目标，本地实现尚为共享服务密钥。完整错误/示例、服务身份升级及团队冻结继续属于 F04。
