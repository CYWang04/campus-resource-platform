# 本地运行与验证

本版为前期公共底座，完整业务范围仍见 REQUIREMENTS。所有命令从仓库根目录执行，先启动 Docker Desktop。

## 全套启动

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/init-local.ps1
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker compose --env-file .env -f infra/compose.yml up --build -d --wait
```

浏览器访问 http://127.0.0.1:8080 。演示账号为 `student`、`technician`、`manager`，共用公开测试密码 `CampusDemo2026!`。完整的组员操作清单见 [演示与验收指南](ACCEPTANCE.md)。邮件捕获界面为 http://127.0.0.1:8025 。它只证明开发邮件通路，不能替代真实 SMTP 实收验收。

`init-local.ps1` 仅首次生成本地配置，不覆盖已有配置；演示密码固定公开，数据库与服务密钥随机生成。已有旧环境可执行 `scripts/set-public-demo-password.ps1` 后重建 M2。MySQL 初始化只在空卷运行；后续表结构由各服务 Flyway 迁移维护。修改数据库密码不会修改已有数据库账号密码；不要用删除卷来修复包含重要数据的环境。

## 串行检查

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/maven.ps1 -B verify
Push-Location web
npm.cmd ci
npm.cmd run build
Pop-Location
node scripts/smoke.mjs
node scripts/resilience.mjs
```

Maven 脚本首次从官方 Maven Central 下载固定 3.9.9 并核验官方 SHA-512，缓存在 `.work/tools`，不全局安装。CI 使用预装 Maven。前端精确依赖及传递依赖由 package-lock.json 锁定。

自动验收会创建两张带“自动验收”标题的真实演示工单，完整验收关闭后保留历史；请在没有其他有效维修限制的演示教室上运行。检查真实服务、真实数据库和真实 HTTP，不使用模拟成功接口。多次运行可累计演示通知及审计。

resilience 检查还会创建并关闭恢复验收工单，短暂停止并恢复 M1/M2，验证依赖恢复、身份故障关闭、乱序版本、账号隔离及实际邮件捕获；测试期间请勿同时手动操作。脚本通过 finally 恢复被停止的服务，进程被强制终止时仍应手动检查 Compose 状态。

## 手动走通整教室审批链路

1. 学生登录，在“教室申请”选择未来整点时段并提交申请。
2. 管理员登录，先执行“院系通过”，再执行“资源通过”。刷新至“已批准”。
3. 在相同时段查询该教室座位，应显示不可预约；学生在开始前撤销后应重新可预约。
4. 若所选时段已有个人预约或维修限制，最终资源核验应进入“资源冲突”，不能抢占个人预约。

## 手动走通维修链路

1. 学生登录，在学习空间查看教室，再到物业报修提交问题。
2. 管理员登录，将工单派给维修人员；维修人员登录后开工。刷新至“维修中”，学习空间应显示维修限制。
3. 维修人员填写完工说明并提交；管理员可验收通过或退回继续维修。通过后刷新至“已关闭”，对应来源解除。
4. 学生登录后可对本人已关闭工单给出 1～5 星评价。
4. 同一教室同时维修两张工单，只验收其中一张，空间必须仍为维修限制中。
5. 同步中或资源服务失败时查看详情，不能把处理中理解为已生效。暂时停掉 M1 后开始维修，恢复 M1 后会重试原操作编号。

M1 已实现资源管理、院系和开放时间、插座筛选、未来 7 天整点预约、动态码签到、提醒、未签到违约、历史复用和统计。M2 已实现两级审批、禁止自审、24 小时超时、撤销以及 M1 时段占用同步。M3 已实现派单、职责校验、24/48 小时升级、完工、验收退回/通过和评价。幂等记录和已解除来源保留到数据库重置，不自动清除。

## 停止与诊断

```powershell
docker compose --env-file .env -f infra/compose.yml ps
docker compose --env-file .env -f infra/compose.yml logs --tail 80 m1 m2 m3
docker compose --env-file .env -f infra/compose.yml down
```

普通 down 保留数据库卷。内部接口和健康端点仅在容器网络开放；公网入口只绑定本机，服务账号仅获自己数据库权限。服务共享凭证仍为前期实现，M1 已开放 M2 整教室占用和 M3 维修两类来源；正式部署前需升级为独立调用方凭证并评审。

生产发布、CodeArts、真实邮件、完整业务可靠性矩阵及全组评审均未由本地实现替代。
