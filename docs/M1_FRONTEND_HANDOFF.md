# M1 后端完善：前端接口交接

更新日期：2026-10-30。范围：保留原前端界面，把 M1 后端完善所需的页面接入交给前端负责人；本文包含新增接口、已有接口变化、兼容影响和验收步骤。状态：已按当前后端源码核对；前端待接入、待人工验收，接口及迁移待受影响组评审。

用户已明确要求不改动原前端界面。本轮撤回此前对 `web/` 的改动，保留后端、迁移、网关和契约；后续前端接入先按本文评审，不直接修改页面。

## 1. 当前代码边界与优先事项

`web/` 已恢复到原始基线 `c153700`：Rooms.vue、Reservations.vue、M1Admin.vue、Applications.vue、main.ts 和 vite.config.ts 均无工作区差异。此前做过的前端路由懒加载也已撤回。布局、样式、导航及组件沿用原版，本文不规定新视觉方案。

后端完善保留在 M1/M2，数据库新增 M1 V5、M2 V3；Nginx 的新接口代理保留。用户已授权按 `M1 → main` 提交当前版本，提交与验证状态见 [HANDOFF](HANDOFF.md)。历史修复/测试记录见 [M1_ITERATION_1](M1_ITERATION_1.md)；机器可读接口见 [OpenAPI 0.4.0](../contracts/foundation.openapi.json)。

| 优先级 | 原页面当前情况 | 需要前端接入的内容 |
| --- | --- | --- |
| P0 | 学生端仍有“查看签到码”，调用会返回 403 | 移除学生自行取码逻辑，使用已有手动输入入口；管理员另按权限展示教室码。不能为兼容旧页面放宽后端权限 |
| P0 | 时长、预约范围在页面写死为 4 小时/7 天 | 从策略接口读取；政策修改后刷新，历史复用也使用新规则 |
| P0 | Rooms.vue 将所有 available=false 写成维修 | 按 availabilityReason 显示关闭、无空位、整室占用或维修等实际原因 |
| P1 | 历史只取前 100 条，已结束仍显示已签到 | 接分页参数和 displayStatus；保留原表格样式，由前端负责人决定分页控件位置 |
| P1 | 管理端以新增资源为主，缺少维护列表 | 接含停用资源的管理查询及已有 PUT 接口；处理资源保护冲突 |
| P1 | 申请页未知新终态 | 将 CANCELLED_MAINTENANCE 映射为“维修取消”，不提供恢复原申请动作 |
| P2 | 统计、审计、回调状态没有完整入口 | 使用本文接口，接入位置/布局由前端负责人确认；利用率与通知任务管理接口尚未实现 |

旧前端可构建不等于已兼容所有新行为。当前“签到”函数在没有已缓存编码时仍会弹出手动输入，后端签到接口保留；“查看签到码”按钮不再适用于学生。管理展示入口尚未接回原页面。

## 2. 通用调用约定

- 下文公共路径省略统一前缀 `/api/v1`；内部路径单独写全。
- 使用登录获得的 `Authorization: Bearer <当前会话>`；沿用 [api.ts](../web/src/api.ts) 的内存令牌和错误拦截，不把令牌写进交接或日志。
- 成功响应为 `{ "data": ..., "requestId": "..." }`。`read<T>()` 已解包到 data，`api.get/post/put()` 需取 `response.data.data`。字段均为 camelCase，不按数据库 snake_case 取值。
- 错误响应为 `{ "code", "message", "fieldErrors", "requestId" }`，按 code 处理行为，展示 message；保留 requestId 便于查问题。403 不提示为“网络失败”，503 不显示业务成功。
- 预约创建/取消/签到/复用沿用 POST 和 `Idempotency-Key`（非空、最多 100 字符）。同一动作网络重试保持键和请求体；用户改动业务内容时换键。不要超时后无条件换键重复创建。
- 时间上传带时区的 ISO 8601，内部 UTC，展示上海时区。示例 `2026-10-31T08:00:00+08:00` 等于 `2026-10-31T00:00:00Z`；示例日期联调时需改为合法未来时段。开始/结束须分、秒、毫秒均为 0，不是只把显示格式改成 `HH:00`。
- 个人预约区间为 `[startAt,endAt)`；开始必须晚于当前时刻且不超过当前时刻加 maxAdvanceDays 个 24 小时。完整时段须在同一上海自然日、符合教室开放时间；结束可以等于关闭时刻。

## 3. 本轮新增公共接口（6 个）

| 方法与路径 | 权限 | data 的结构 | 前端用途 |
| --- | --- | --- | --- |
| GET `/reservation-policy` | resources:read | Settings 对象 | 学生创建/复用及管理端加载当前策略 |
| PUT `/admin/reservation-policy` | resources:manage | 更新后的 Settings | 保存策略，携带当前 version |
| GET `/admin/rooms` | resources:manage | `{items: Room[], total}` | 维护列表，包含启用及停用教室；无 page/pageSize |
| GET `/admin/rooms/{id}/seats` | resources:manage | Seat[] | 查看该教室全部座位，包含停用座位 |
| GET `/admin/resource-audit-events` | audit:read | AuditEvent[]，最近 100 条 | M1 本地审计，只读 |
| GET `/admin/resource-sync-tasks` | resources:manage | SyncTask[]，最近 100 条 | 维修取消 M2 申请的回调进度，只读 |

### 3.1 预约策略

GET 响应示例：

```json
{
  "data": {"maxDurationHours": 4, "maxAdvanceDays": 7, "version": 1},
  "requestId": "example-request-id"
}
```

PUT 请求体示例（version 必须取刚读取的值，不能固定为 1）：

```json
{"maxDurationHours": 6, "maxAdvanceDays": 7, "version": 1}
```

| 字段 | 类型/范围 | 说明 |
| --- | --- | --- |
| maxDurationHours | integer，1～12 | 单次最长时长，默认 4；开始/结束整点，所以最短 1 小时 |
| maxAdvanceDays | integer，1～30 | 最晚可预约开始时刻，默认 7 天 |
| version | integer，至少 1 | 更新时做乐观锁；成功后返回递增版本 |

新建和历史复用采用当前配置，旧预约不追溯裁剪。保存遇到 `409 POLICY_VERSION_CONFLICT` 应重新读取并提示配置已变化，由用户重新确认；不能自动覆盖别人的修改。前端展示文案、时长选项、日期限制都从同一数据生成；最终规则仍以服务端为准。

### 3.2 管理资源查询

Room 至少包含 `id, name, building, departmentCode, openTime, closeTime, active, availabilityVersion`；`departmentCode` 可以为 null。管理查询没有公开查询的 available/effects/空位派生字段，不应把它当成可预约查询。

Seat 包含 `id, roomId, code, powerType, active`。管理接口用 active 显示启停；若要检查某时段能否预约，另用公开时段查询。

这里是全量管理列表及数组，没有服务端分页筛选参数。资源很多时的分页属于后续契约，不要发送参数后假定后端已经分页。

### 3.3 审计和维修取消回调

| 数据 | 字段 |
| --- | --- |
| AuditEvent | id、actorId、action、entityType、entityId、reason、requestId、createdAt |
| SyncTask | id、applicationId、roomId、status、attempts、lastError（可 null）、nextAttempt |

审计常见 action：ROOM_CREATED、ROOM_UPDATED、SEAT_CREATED、SEAT_UPDATED、POLICY_UPDATED、RESERVATION_CREATED、RESERVATION_CANCELLED、CHECKED_IN、NO_SHOW、MAINTENANCE_CANCELLED；来源操作还会记录 ACTIVE/RELEASED 等动作。entityType 区分 ROOM、SEAT、POLICY、M1_RESERVATION、M2_ROOM_APPLICATION、M3_MAINTENANCE。保留未知枚举的文本回退，reason 只作为普通文字展示。

SyncTask 的 PENDING 表示等待同步或失败后待重试，SENT 表示 M2 已确认。attempts 是失败次数，lastError 是安全摘要；不包含服务凭证。当前没有手动重投、删除、分页或筛选 API。此接口也不是个人预约提醒任务列表，不要把回调的 SENT 当作邮件已送达。

## 4. 已有接口的变化与接入细节

### 4.1 教室与座位可用性

| 已有接口 | 可用参数 | 本轮行为/字段变化 |
| --- | --- | --- |
| GET `/rooms` | building、powerType、startAt/endAt | 新增可选时段查询及空位/原因字段；仍只返回启用教室，按院系限制，管理员可看全部院系 |
| GET `/rooms/{id}/seats` | startAt/endAt、powerType | 用统一院系/启用/开放/占用规则；每个座位返回 available 和 unavailableReason；只返回启用座位 |
| GET `/rooms/{id}/availability` | 无时段参数 | 当前教室级开放/来源状态及 availabilityVersion；不检查个人预约的空座数量 |

building 为去空白后的精确匹配，非模糊搜索；powerType 是 FIXED（固定插座）、RAIL（移动导轨）、NONE（无插座）。startAt/endAt 必须成对提供；都不传查询当前时刻，提供则按当前个人预约策略验证合法未来时段。跨院系直接请求详情会 403，不是空数组。

`/rooms` 的 data 为 `{items,total,page,pageSize}`，当前实际返回全部匹配教室，page 固定 1，没有接收 page/pageSize 参数。新增/关键字段：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| available | boolean | 当前/所选时段内有至少一个符合筛选条件的座位，且教室规则与来源允许 |
| availabilityReason | string | 具体原因，见下表 |
| seatCount | integer | 启用且符合 powerType 筛选的座位数，非不受筛选影响的固定总容量 |
| availableSeats | integer | 在同一筛选/时段内可用数量；教室不可用时为 0 |
| effects | array | 重叠有效来源，含 sourceType、sourceId、reason、startAt、endAt；endAt 可 null |
| availabilityVersion | integer | 当前资源版本，M2 申请时仍需取得最新版本 |

座位 data 为数组，每项 `id, code, powerType, available, unavailableReason`。以下原因可以共用文案，但字段名不同：

| 原因 | 建议解释 |
| --- | --- |
| AVAILABLE | 可用 |
| RESOURCE_INACTIVE | 资源已停用 |
| OUTSIDE_OPEN_HOURS | 不在开放时段 |
| MAINTENANCE | 维修中 |
| ROOM_APPLICATION | 整教室占用 |
| NO_AVAILABLE_SEATS | 没有符合条件的空座（教室列表） |
| RESERVATION_CONFLICT | 该时段座位已预约（座位列表） |

查询结果只是选择参考；创建时服务端在锁内再次检查。如果提交发生冲突，刷新同一时段的座位列表，让用户重新选择，不显示假成功。楼宇/插座筛选及时间修改后的旧请求不能覆盖较新结果，前端接入时应处理异步响应先后顺序。

### 4.2 每日码与签到（权限变化，必须适配）

路径仍为 GET `/rooms/{id}/check-in-code`，权限由 resources:read 收紧为 **resources:manage**；学生返回 `403 FORBIDDEN`。data 为 `{roomId, date, code}`，date 为上海日期 `YYYY-MM-DD`，code 是保留前导零的六位字符串。

学生端不再自行取码、缓存或自动填码；输入现场码后调用已有 POST `/reservations/{id}/check-in`：

```json
{"code": "012345"}
```

请求需当前用户令牌、JSON 和 Idempotency-Key。服务端校验本人预约、状态、指定教室/日期的码，以及 `[startAt,startAt+15分钟)` 窗口。开始前、达到第 15 分钟返回 `409 CHECKIN_WINDOW_CLOSED`；码错误返回 `409 INVALID_CHECKIN_CODE`，非六位数字返回 400 校验错误。成功后重新读取记录。

管理员可以在受控展示场景读取该码，需按上海日期变化重新获取；目前没有独立屏幕角色、屏幕 token 或二维码专用接口。不要将管理员会话/服务密钥嵌到学生客户端。每日码可能被转发，不能宣传为绝对到场证明。

### 4.3 预约、历史及维修终态

POST `/reservations` 请求结构不变：`{seatId,startAt,endAt,purpose}`，purpose 必填且最多 200 字符。时长由策略控制；同一创建操作即使已到开始时间，仍可用原键/原内容重放已保存结果。重放是命令历史快照，随后 GET 详情/列表获取实际当前状态。

GET `/reservations?page=1&pageSize=20` 的 data 为 `{items,total,page,pageSize}`，page 从 1 开始，pageSize 为 1～100；按 startAt 倒序、id 排序。GET `/reservations/{id}` 返回同类单条视图，新增 `displayStatus`，保留原 `status`。

| status / displayStatus | 页面含义 | 操作注意 |
| --- | --- | --- |
| CONFIRMED | 待签到 | 开始前可取消；签到仅限窗口内 |
| CHECKED_IN | 已签到 | 不再提供签到/取消 |
| COMPLETED（仅 displayStatus） | 已结束 | 底层 status 仍为 CHECKED_IN；不能直接把数据库枚举改成 COMPLETED |
| CANCELLED | 用户取消 | 可用历史内容发起新预约 |
| NO_SHOW | 未签到违约 | 查看违约记录；重复扫描不重复罚 |
| CANCELLED_MAINTENANCE | 维修取消 | 未结束的待签到或已签到预约可能进入此状态；不记违约 |

展示优先使用 `displayStatus ?? status`，操作依据 status 和时间。保留未知枚举回退。详情还包含地点、用途、startAt、endAt、createdAt、cancelledAt、checkedInAt（后两者可 null），均沿用 camelCase。

POST `/reservations/{id}/repeat` 仍可用，body 为 `{startAt,endAt}`，复用原座位/用途并重新检查当前政策、院系、停用、开放与占用；也可沿用前端预填再创建的流程。不能因为是历史复用就跳过可用性核验。取消接口 POST `/reservations/{id}/cancel` 不变，开始前的 CONFIRMED 才可取消。

M2 的公开申请 GET 列表/详情新增可能状态 `CANCELLED_MAINTENANCE`；申请详情 operations 的 status 可能为 CANCELLED，表示旧同步任务已终止。维修解除后原申请不会重新批准，需用户重新提交。后台异步收敛期间允许短暂仍显示处理中/已批准，以刷新后的服务端状态为准。

### 4.4 资源登记、编辑与停用（已有写接口）

以下写接口原本已存在，本轮补规则保护和管理查询，不能写成新增接口：

| 接口 | 请求体 |
| --- | --- |
| POST `/admin/rooms`、PUT `/admin/rooms/{id}` | `{name,building,departmentCode,openTime,closeTime,active}` |
| POST `/admin/seats`、PUT `/admin/seats/{id}` | `{roomId,code,powerType,active}` |

全部要求 resources:manage，成功 data 为对应资源对象。PUT 是完整表单提交，带 active；不要只发送 `{active:false}`。时间使用 `HH:mm:ss`（如 `07:00:00`），关闭必须晚于开放，不支持跨夜开放。name/building 最多 100 字符，departmentCode 最多 40，座位 code 最多 32。

院系 null、空字符串或纯空白均归一为 null，表示全校；保存后用服务端结果更新表单。停用是 active=false，不调用 DELETE。

停用教室/座位、缩短开放时间、缩窄院系范围若影响未结束的有效个人预约或整室占用，返回 `409 ACTIVE_RESERVATIONS`，保持原资源并提示处理相关业务。座位 roomId 不允许变更，返回 `409 SEAT_ROOM_IMMUTABLE`；需新建座位并按规则停用旧座位。正常修改不是紧急取消预约入口。

### 4.5 统计（已有接口扩展）

GET `/admin/reservation-statistics`，权限 reservations:stats。新增可选 from、to（`YYYY-MM-DD`）和 roomId；可单独提供一个日期边界，from/to 同传时 to 不得早于 from。按预约 startAt 的上海自然日期归属，to 当日包含在内，不做跨日时长分摊。

data 结构：

```json
{
  "byStatus": [{"status": "COMPLETED", "count": 2}],
  "byRoom": [{"roomName": "示例教室", "count": 3, "reservationHours": 4.5}],
  "byStartHour": [{"hour": 8, "count": 3}],
  "violations": 0
}
```

示例数值仅说明字段形状，各数组是独立统计，不要求行数相同。byRoom.count、byStartHour.count 包含全部预约状态；reservationHours 只计 CONFIRMED/CHECKED_IN，排除取消/维修取消/违约，已结束的已签到也在其中。它是预约时长，不是出勤时长、使用率或利用率。byStartHour.hour 已转换为上海小时，不要再加 8。

## 5. 重要错误处理

| HTTP / code | 前端动作 |
| --- | --- |
| 400 WHOLE_HOUR_REQUIRED / INVALID_DURATION | 修正实际时间值/时长，读取当前策略；只修改显示格式无效 |
| 400 TIME_RANGE_REQUIRED / INVALID_POWER_TYPE / INVALID_DATE_RANGE / INVALID_PAGE | 修正查询参数；startAt/endAt 成对提交 |
| 403 FORBIDDEN / DEPARTMENT_FORBIDDEN | 提示权限/院系限制；不自动换身份重试 |
| 409 POLICY_VERSION_CONFLICT | 重新加载策略，保留用户输入并让其重新确认 |
| 409 START_IN_PAST / START_TOO_LATE | 重新选择合法未来时段 |
| 409 RESOURCE_INACTIVE / OUTSIDE_OPEN_HOURS | 刷新资源及开放信息，重新选择 |
| 409 RESERVATION_CONFLICT / ROOM_UNAVAILABLE | 刷新相同时段座位，重新选择，不能抢占 |
| 409 ACTIVE_RESERVATIONS / SEAT_ROOM_IMMUTABLE | 保留编辑现场，解释保护原因，不显示已保存 |
| 409 CHECKIN_WINDOW_CLOSED / INVALID_CHECKIN_CODE / INVALID_STATE | 按实际原因提示；不能绕过时间/状态判断 |
| 409 IDEMPOTENCY_CONFLICT / CONCURRENT_COMMAND / RESOURCE_BUSY | 核对键与内容；相同动作有限重试或先查结果，避免重复创建 |
| 401、503 或网络超时 | 401 重新登录；503 显示依赖不可用；结果未知时先查询/重放原动作，不直接显示失败后再创建一份 |

## 6. 内部新增接口：只交给服务端

新增 POST `/internal/v1/room-applications/{id}/maintenance-cancellations` 位于 M2，由 M1 的持久化任务通过服务凭证调用。body 为 `{eventId,roomId,sourceVersion,maintenanceSourceId}`；UUID 字段须合法，sourceVersion 至少 1。成功 data 为 `{applicationId,state}`，同事件同内容去重，不同内容 409。

M1 同事务取消占用并保存事件，M2 幂等接收、保护申请终态及生成通知。前端只读公开申请、通知及管理回调状态，不调用内部接口，也不获取 X-Service-Key。Nginx 拒绝 `/internal/` 是预期边界。

已有 PUT `/internal/v1/resource-effects/{sourceType}/{sourceId}` 调整了重放顺序及当前终态保护，供 M2/M3 接入；历史 APPLIED 不代表当前来源还有效。sourceState（历史）、currentSourceState（当前）、invalidatedBy（重放取消时的维修来源）详见 [INTERFACES](INTERFACES.md)。这些不是浏览器 API。

## 7. 代理与运行交接

[Nginx](../infra/nginx.conf) 已保留新公共接口至 M1 的代理，可从完整 Compose 的 `http://127.0.0.1:8080` 访问。源码恢复时也恢复了 [vite.config.ts](../web/vite.config.ts)；原代理仅单独路由 auth、rooms 和服务版本，其他 `/api/v1` 默认转 M3，单独 npm dev 尚不能覆盖完整新旧业务。

后续前端负责人接入时需在通用 `/api/v1` 前匹配：

| 目标 | 应匹配的公共前缀 |
| --- | --- |
| M1（宿主机服务端口示例 8081） | `/api/v1/rooms`、`/api/v1/reservations`、`/api/v1/reservation-policy`、`/api/v1/admin/rooms`、`/api/v1/admin/seats`、`/api/v1/admin/reservation-statistics`、`/api/v1/admin/reservation-policy`、`/api/v1/admin/resource-audit-events`、`/api/v1/admin/resource-sync-tasks` |
| M2（宿主机服务端口示例 8082） | `/api/v1/auth`、`/api/v1/directory`、`/api/v1/room-applications` |
| M3（宿主机服务端口示例 8083） | 其余已实现公共接口，沿用原默认规则 |

这些端口是宿主机单独运行后端时的约定，当前 Compose 的后端端口没有发布至宿主机。也可由前端负责人选择将开发 API 全部代理到已运行的 Nginx 8080；两种方式选一种并实际验收。当前没有代替前端修改 Vite。

## 8. 前端接手验收清单（待执行）

- [ ] 在不改变既有视觉布局的前提下，先处理 P0 接口兼容，再评审需要增加的控件与入口。
- [ ] 学生不能取码；管理员能展示对应上海日期/教室的六位字符串，学生手动输入成功；错码、开始前及第 15 分钟被拒绝。
- [ ] 策略读取和版本冲突正确；改为 2/6 小时后创建及历史复用均与服务端一致，已有预约保持原时段。
- [ ] 楼宇精确筛选、三种插座、成对时段参数可用；关闭/整室/维修/无空位原因不混淆；快速切换条件不会被旧响应覆盖。
- [ ] 并发造成的提交冲突能刷新选择；超时重放保持原键，同一创建不重复。
- [ ] 预约至少两页且超过 100 条时仍能访问后页；已结束和维修取消显示正确，不出现无效操作。
- [ ] 管理列表含停用资源，编辑提交完整表单；有效个人/整室预约保护及座位不可移动错误可理解。
- [ ] 维修取消 M2 申请后显示“维修取消”，后台重试/维修解除不使旧申请复活，站内通知可查。
- [ ] 统计日期/教室筛选、上海小时、小时数口径与文档一致；审计/回调状态只读且不越权。
- [ ] Compose 和选定的开发代理方式均联调；`npm run build` 通过，并记录实际人工验收，不把旧页面截图算作本次接入通过。

后端已有自动脚本为 [smoke](../scripts/smoke.mjs)、[M1 regression](../scripts/m1-regression.mjs)、[resilience](../scripts/resilience.mjs)；它们不替代上述页面验收，故障脚本会短暂停止服务，只在本地演示环境串行运行。后端依据：[ReservationPolicy](../backend/m1/src/main/java/cn/edu/fudan/campus/m1/ReservationPolicy.java)、[Resources](../backend/m1/src/main/java/cn/edu/fudan/campus/m1/Resources.java)、[Reservations](../backend/m1/src/main/java/cn/edu/fudan/campus/m1/Reservations.java)、[ResourceAudit](../backend/m1/src/main/java/cn/edu/fudan/campus/m1/ResourceAudit.java)。

## 9. 本轮实际验证（2026-10-30）

- `git diff HEAD -- web/` 为空，6 个恢复文件与 HEAD 原版一致；未保留上一轮前端功能或性能修改。恢复前的补丁/文件摘要仅存 `.work/frontend-restore-2026-10-30/`。
- `npm run build --prefix web` 的 Vue/TypeScript 检查和生产构建通过；原版主 JS 为 1018.06 kB/gzip 337.25 kB，仍有大包提示。本轮没有为消除提示再修改前端。
- `docker compose ... up --build --no-deps -d web` 成功，仅重建/替换本地 Web。实际 HTTP 提供的主 JS 与恢复后本机 dist 逐字节一致，原版界面文案存在，新版策略控件文案不存在。
- 11 项只读/鉴权 HTTP 检查通过：策略读取、管理教室/座位、审计/回调字段形状及 camelCase、管理员取码和学生 403 等；演示会话检查后已注销，没有修改预约、配置或资源数据。PUT 策略等写行为沿用 10-26 的历史验证，未在本轮重跑。
- OpenAPI 的 47 条路径、329 个内部引用可解析；README/docs 的 141 个仓库内链接可解析，`git diff --check` 通过。
- 没有执行新页面开发、浏览器端到端业务验收或完整后端 smoke/resilience；上节勾选项仍待前端负责人实际接入验收。没有提交、推送或生产发布。
