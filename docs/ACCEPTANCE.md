# 校园资源服务平台：从零启动、演示与验收指南

本文面向第一次接触项目的组员和验收人员。按顺序操作，可以在一台新的 Windows 电脑上完成环境安装、代码获取、本地配置、构建、启动、演示和自动验收。

本文以 **Windows 10/11、PowerShell、VS Code、Docker Desktop** 为标准环境。除特别说明外，命令均在仓库根目录 `campus-resource-platform` 中执行。

macOS 用户从 [MACOS](MACOS.md) 的 shell/Colima 路径启动，业务演示相同。2026-10-30 按用户要求恢复原前端，后端完善保留且尚未提交。本文中动态配置、管理员展示码、分页、资源编辑启停等是前端接入后的目标步骤，当前原版页面尚无完整入口；旧学生取码会 403。接口、兼容项和待执行页面验收见 [M1_FRONTEND_HANDOFF](M1_FRONTEND_HANDOFF.md)，10-26 记录只说明当时版本。

## 1. 当前版本能演示什么

后端已具备三个业务模块的核心闭环；以下包含需要前端后续接入的能力，不能据此认定原版页面全部可演示：

- 一个 Vue 门户和三个 Spring Boot 服务；
- 三个独立 MySQL 数据库及 Flyway 迁移；
- 登录、注销、会话校验和角色权限；
- 教室、座位及指定时段可用性查询；
- 默认未来 7 天、单次 4 小时且可配置的整点预约，本人分页查询和开始前取消；
- 管理员展示每日教室码、学生手动输入，开始至第 15 分钟之前签到及开始前/迟到站内提醒；
- 未签到自动取消、座位释放、唯一违约记录、历史再次预约和运营统计；
- 管理员登记/编辑/启停教室座位、设置院系和开放时间，学生按楼宇和插座类型筛选；
- 整教室申请、院系与资源两级审批、驳回、超时、撤销和时段占用；
- 学生报修、管理员派单、指定维修人员开工和完工、验收退回或通过、取消与评价；
- 维修对教室的限制、个人及整室预约取消与通知、可靠回调和终态保护；
- 持久化同步任务、重试、幂等与乱序版本保护；
- 站内通知、邮件捕获和审计记录；
- 自动主链路与故障恢复检查。

尚未完成的主要内容是完整可配置 RBAC、通知运维入口、利用率、审计汇总、完整可靠性矩阵、云发布及生产运维入口。真实 SMTP 和自然语言 Agent 按当前开发安排暂不处理。

## 2. 运行组成和本机端口

| 组件 | 作用 | 本机访问方式 |
| --- | --- | --- |
| Web 门户 | 唯一业务入口及 API 网关 | http://127.0.0.1:8080 |
| Mailpit | 查看本地测试邮件 | http://127.0.0.1:8025 |
| M1 | 教室、座位、个人预约与按来源资源占用 | 仅容器内部访问 |
| M2 | 身份、权限、整教室申请与两级审批 | 仅容器内部访问 |
| M3 | 报修、派单、维修、验收评价、通知和审计 | 仅容器内部访问 |
| MySQL | 三个服务的独立数据库 | 仅容器内部访问 |

只需确保本机的 `8080` 和 `8025` 端口未被占用。后端服务、数据库和内部接口不直接暴露到宿主机。

## 3. 新电脑环境搭建

### 3.1 系统准备

1. 使用 64 位 Windows 10 或 Windows 11，并完成系统更新。
2. 在 BIOS/UEFI 中启用 CPU 虚拟化；任务管理器“性能 → CPU”页面应显示“虚拟化：已启用”。
3. 确保系统盘和 Docker 数据盘有足够空间下载开发依赖、镜像和构建产物。
4. 安装软件或修改环境变量后，重新打开 PowerShell 和 VS Code。

### 3.2 安装 WSL 2

以管理员身份打开 PowerShell，执行：

```powershell
wsl --install
```

按系统提示重启电脑。重启后检查：

```powershell
wsl --status
wsl --set-default-version 2
```

如果第一条命令提示已安装，可直接检查状态。Docker Desktop 使用 WSL 2 后端，因此 WSL 或虚拟化异常会导致整个容器环境无法启动。

参考：[Microsoft WSL 安装说明](https://learn.microsoft.com/windows/wsl/install)

### 3.3 安装 Git

从 [Git for Windows](https://git-scm.com/download/win) 下载并安装，默认选项即可。验证：

```powershell
git --version
```

### 3.4 安装 VS Code 和扩展

从 [Visual Studio Code](https://code.visualstudio.com/download) 下载并安装，建议在安装程序中勾选“添加到 PATH”。项目推荐以下扩展：

- Extension Pack for Java：`vscjava.vscode-java-pack`
- Spring Boot Extension Pack：`vmware.vscode-boot-dev-pack`
- Vue - Official：`Vue.volar`
- ESLint：`dbaeumer.vscode-eslint`
- Prettier：`esbenp.prettier-vscode`

克隆项目后，VS Code 会根据 `.vscode/extensions.json` 提示安装。也可以执行：

```powershell
code --install-extension vscjava.vscode-java-pack
code --install-extension vmware.vscode-boot-dev-pack
code --install-extension Vue.volar
code --install-extension dbaeumer.vscode-eslint
code --install-extension esbenp.prettier-vscode
```

### 3.5 安装 JDK 21

从 [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21) 安装 **JDK 21**。安装时启用 `JAVA_HOME` 和 `PATH` 配置选项。验证：

```powershell
java -version
javac -version
$env:JAVA_HOME
```

`java` 和 `javac` 的主版本都应为 21。项目不要求全局安装 Maven；仓库中的 `scripts/maven.ps1` 会按需下载 Maven 3.9.9、校验官方 SHA-512 并复用本地缓存。

### 3.6 安装 Node.js 24

从 [Node.js 官网](https://nodejs.org/en/download) 安装 **Node.js 24**，安装包会一并安装 npm。验证：

```powershell
node --version
npm.cmd --version
```

Node.js 主版本应为 24。本文使用 `npm.cmd`，可避开部分 PowerShell 执行策略对 `npm.ps1` 的限制。

### 3.7 安装 Docker Desktop

从 [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/) 安装 Docker Desktop，并使用 WSL 2 后端。安装后启动 Docker Desktop，等待 Docker Engine 就绪，再执行：

```powershell
docker version
docker compose version
docker run --rm hello-world
```

`docker version` 应同时显示 Client 和 Server，`hello-world` 应成功运行。只有 Docker 命令存在、但 Docker Engine 未启动时，项目仍无法运行。

### 3.8 已验证的环境基线

当前版本已在下列组合上通过构建和本地验收。小版本不必完全一致，但主版本建议保持一致。

| 工具 | 已验证版本 |
| --- | --- |
| Eclipse Temurin JDK | 21.0.12.1+1 |
| Node.js | 24.20.0 |
| npm | 11.19.0 |
| Git for Windows | 2.56.0.windows.2 |
| VS Code | 1.142.0 |
| WSL | 2.7.13.0 |
| Docker Desktop | 4.96.0 |
| Docker CLI | 29.9.1 |
| Docker Compose | 5.6.0 |

## 4. 获取项目

在准备存放代码的目录打开 PowerShell。将占位内容替换成团队 GitHub 仓库页面提供的 HTTPS 克隆地址：

```powershell
git clone <团队 GitHub 仓库 HTTPS 地址>
Set-Location .\campus-resource-platform
```

当前开发分支为 `M1`，基于 main 的 `c153700`；本轮修复仍在工作区，尚未提交上传。维护者提交并推送后，验收人员可执行：

```powershell
git fetch --all --prune
git switch M1
git status
git rev-parse --short HEAD
```

正式验收时记录分支和提交号，确保不同组员运行的是同一版本。若分支尚未上传，应先由维护者完成上传。

用 VS Code 打开项目：

```powershell
code .
```

## 5. 一键检查环境

在仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-environment.ps1 -CheckDockerEngine
```

脚本会检查 Java 21、Node.js 24、npm、Git、VS Code、Docker CLI、Docker Compose、五个推荐扩展和 Docker Engine。出现失败项时，先按输出修复，再继续初始化。

## 6. 首次配置

确保 Docker Desktop 已启动，然后执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\init-local.ps1
```

脚本会检查主要工具，根据 `.env.example` 生成本机 `.env`，随机生成数据库和服务调用密钥，设置公开演示密码，并校验 Compose 配置。

`.env` 已被 Git 忽略。每位组员都应在自己的电脑上运行初始化脚本，不需要互相发送 `.env`。仓库中的 `.env.example` 只说明配置项，不应直接作为运行配置。已有 `.env` 时，初始化脚本不会覆盖。

三个演示账号共用以下公开测试密码：

```text
CampusDemo2026!
```

| 角色 | 用户名 | 密码 | 主要操作 |
| --- | --- | --- | --- |
| 学生 | `student` | `CampusDemo2026!` | 座位预约、教室申请、报修、撤销与评价 |
| 维修人员 | `technician` | `CampusDemo2026!` | 处理已派工单、开工、提交完工 |
| 管理员 | `manager` | `CampusDemo2026!` | 两级审批、派单、验收、查看审计 |

账号密码仅用于本地测试，可以随代码公开。数据库和服务密钥仍由每台电脑随机生成。

旧环境需要恢复公开密码时执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-public-demo-password.ps1
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker compose --env-file .env -f .\infra\compose.yml up --build -d --wait m2
```

## 7. 构建项目

首次启动前分别构建后端和前端，可以提前发现依赖或编译问题。

### 7.1 后端

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\maven.ps1 -B verify
```

首次执行会下载 Maven 3.9.9 和后端依赖。最终应显示 `BUILD SUCCESS`。

### 7.2 前端

```powershell
Push-Location .\web
npm.cmd ci
npm.cmd run build
Pop-Location
```

最终应通过 Vue/TypeScript 检查并生成生产构建产物。

## 8. 启动完整项目

```powershell
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker compose --env-file .env -f .\infra\compose.yml up --build -d --wait
```

限制并行数可以减少同时构建多个 Java 服务造成的内存和网络压力。第一次运行需要下载 MySQL、Mailpit、Java 和 Nginx 等镜像，通常比后续启动慢。

检查状态：

```powershell
docker compose --env-file .env -f .\infra\compose.yml ps
```

预期 `mysql`、`m1`、`m2`、`m3` 显示 `healthy`，`mailpit` 和 `web` 保持运行，没有容器反复重启或退出。随后访问：

- 平台入口：http://127.0.0.1:8080
- 测试邮件：http://127.0.0.1:8025
- 已实现接口契约：[`contracts/foundation.openapi.json`](../contracts/foundation.openapi.json)

## 9. 五分钟主链路演示

### 9.1 学生预约（动态配置等按前端交接验收）

1. 使用 `student / CampusDemo2026!` 登录并打开“座位预约”。
2. 选择教室、上海日期/未来整点、当前配置允许的时长和可用座位，填写用途后提交；默认上限 4 小时。
3. 在“我的预约”确认地点、时间和“待签到”状态。
4. 点击取消，确认状态变为“已取消”，相同时段座位重新可选。
5. 对一条历史预约点击“再次预约”，确认座位和用途被复用，但新时段仍重新校验冲突。

### 9.2 签到、提醒与违约（管理员展示入口待前端接入）

1. 管理员在“资源管理”对教室点击“屏幕签到码”进行现场展示。到预约开始时间后，学生在自己的记录点击“输入编码签到”，手动输入现场码，状态变为“已签到”。学生不能自行取码。
2. 同一动态码只对应当天和指定教室；错误码、开始前或达到开始后第 15 分钟应被拒绝。每日码可被转发，不能当作绝对到场证明。
3. 自动脚本会把测试预约调整到提醒/逾期窗口，核对开始前提醒、开始后 10 分钟提醒和开始后 15 分钟自动取消。
4. 未签到取消后座位应释放，“违约记录”增加且重复任务不会重复处罚。
5. 管理员在“资源管理”修改预约配置，刷新学生页确认一致；可登记、编辑或停用资源。有有效预约/整室占用时，破坏规则的调整被拒绝。
6. 统计可筛选日期/教室，小时按上海时间展示；有效预约时长不含取消/违约，不是利用率。资源审计与维修取消同步可查。

### 9.3 整教室审批

1. 学生进入“教室申请”，选择未来整点时段、人数和用途后提交。
2. 管理员登录，对同一申请依次执行“院系通过”和“资源通过”，刷新至“已批准”。
3. 查询该时段座位应显示不可预约；学生在开始前撤销后应恢复可预约。
4. 可先创建同时间段个人预约再审批整教室申请，最终状态应为“资源冲突”，个人预约保持有效。

### 9.4 维修链路

1. 打开 http://127.0.0.1:8080 ，使用 `student / CampusDemo2026!` 登录。
2. 在“学习空间”查看演示教室及座位。
3. 进入“物业报修”，选择教室，填写问题标题和描述并提交。
4. 使用 `manager / CampusDemo2026!` 登录，将工单派给演示维修人员。
5. 使用 `technician / CampusDemo2026!` 登录并开始维修；刷新到“维修中”后，学习空间应显示维修限制。
6. 维修人员填写完工说明并提交。
7. 管理员验收通过；也可先演示退回，维修人员再次提交后再通过。
8. 学生对已关闭工单给出 1～5 星评价，并查看通知、审计和 Mailpit 中的测试邮件。

可追加演示“同一教室有两张维修工单”：只关闭其中一张时，教室仍应保持维修限制；两张全部验收关闭后才恢复可用。

后台会将派单后 24 小时未开工、开工后 48 小时未完工的工单标记为升级；常规五分钟演示不等待真实时钟，可通过专项测试数据验收。

## 10. 自动验收

完整项目运行后，依次执行：

```powershell
node .\scripts\smoke.mjs
node .\scripts\m1-regression.mjs
node .\scripts\resilience.mjs
```

`smoke.mjs` 验证真实服务、数据库和 HTTP 链路，包括：

- 三类账号登录、注销和角色权限；
- 教室、座位和可用性；
- 整教室申请、两级审批、撤销、幂等和个人预约冲突；
- 报修、派单、维修、验收、取消和评价；
- 两个维修来源的隔离与解除；
- 通知、审计和网关隔离。

2026-10-26 本机 smoke 通过 **180 项断言/HTTP 检查**，M1 专项通过 **94 项**。轮询请求数随任务收敛速度小幅变化，验收以各脚本最终 `PASS` 为准。另执行 `node --test scripts/test-slots.test.mjs`，4 项时间夹具测试应通过。

`resilience.mjs` 会短暂停止并恢复 M1、M2，继续检查依赖恢复、身份故障关闭、乱序版本、数据库账号隔离、持久化和实际邮件捕获。脚本运行期间不要同时手动操作。正常结束时会恢复被停止的服务；若强制终止脚本，应手动检查 Compose 状态。

`m1-regression.mjs` 使用专用资源与数据库时间夹具，临时变更后恢复策略、停止后恢复 M2/M3，覆盖并发抢座、维修取消回调、旧任务重放、失效提醒和资源保护。结束时停用专用资源，保留历史。只在本地演示环境串行运行。

自动验收会创建真实演示工单、通知和审计记录，并保留已完成历史。它应在没有其他有效维修限制的演示教室上执行。

## 11. 当前可靠性行为的人工观察

- M1 暂时不可用时，维修人员开始维修会进入待同步状态；M1 恢复后系统使用原操作编号继续重试，最终收敛为维修中。
- M2 暂时不可用时，依赖身份验证的业务请求返回服务不可用，采用故障关闭策略；M2 恢复后原会话可继续使用。
- 同步中或资源服务失败时，应查看工单详情和操作状态，不能把“处理中”理解为已经生效。
- 同一教室存在多个维修来源时，解除一个来源不会错误解除其他仍有效的限制。

上述完整自动流程由 `scripts/resilience.mjs` 执行。需要人工演示时，优先根据脚本和 [`docs/RUNNING.md`](./RUNNING.md) 操作，避免留下未恢复的服务。

## 12. 停止、更新和重置

### 查看状态和日志

```powershell
docker compose --env-file .env -f .\infra\compose.yml ps
docker compose --env-file .env -f .\infra\compose.yml logs --tail 100 m1 m2 m3
```

### 普通停止

```powershell
docker compose --env-file .env -f .\infra\compose.yml down
```

普通停止保留 MySQL 数据卷，下次启动仍能看到已有测试数据。

### 更新代码后重建

先确认没有未提交的个人修改：

```powershell
git status
git pull --ff-only
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker compose --env-file .env -f .\infra\compose.yml up --build -d --wait
```

### 完全清空本地测试数据

仅在明确要丢弃本机全部测试数据时执行：

```powershell
docker compose --env-file .env -f .\infra\compose.yml down --volumes
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker compose --env-file .env -f .\infra\compose.yml up --build -d --wait
```

`down --volumes` 会删除本项目的 MySQL 数据卷，已有工单、通知和审计记录无法恢复。不要用删除卷来修复包含重要数据的环境。

## 13. 常见故障

### Docker 命令存在，但项目无法启动

- 打开 Docker Desktop，等待 Docker Engine 完全启动；
- 执行 `docker version`，确认输出包含 Server；
- 执行 `wsl --status`，确认默认版本为 2；
- 新安装 WSL、虚拟化组件或 Docker Desktop 后重启电脑。

### Java 或 Node.js 版本不正确

```powershell
Get-Command java
Get-Command javac
Get-Command node
```

检查命令实际来源，确认 `JAVA_HOME` 指向 JDK 21，重新打开终端，再执行环境检查脚本。

### PowerShell 禁止运行 npm.ps1

使用本文中的 `npm.cmd`，不需要修改系统级执行策略。

### 8080 或 8025 端口被占用

```powershell
Get-NetTCPConnection -State Listen | Where-Object LocalPort -In 8080,8025
```

关闭占用端口的旧程序或旧容器，再启动本项目。

### 服务不健康

```powershell
docker compose --env-file .env -f .\infra\compose.yml ps
docker compose --env-file .env -f .\infra\compose.yml logs --tail 200 <服务名>
```

先查看最早失败的依赖服务。`<服务名>` 可替换为 `mysql`、`m2`、`m1`、`m3`、`mailpit` 或 `web`。

### 测试账号无法登录

1. 核对用户名和第 6 节的密码；
2. 执行 `scripts/set-public-demo-password.ps1`；
3. 重新构建并启动 M2；
4. 刷新登录页后重试。

### 镜像或依赖下载失败

- 确认网络可访问 Docker Hub、Maven Central 和 npm；
- 保持 Docker Desktop 运行后重试失败命令；
- 不要反复删除已经成功下载的缓存；
- 如团队网络需要代理，只在本机对应工具中配置，不要提交个人代理地址。

## 14. 验收记录模板

```text
验收日期：
验收人员：
操作系统：
分支：
提交号：
JDK / Node / Docker 版本：

[ ] 环境检查脚本通过
[ ] 后端 Maven verify 通过
[ ] 前端 npm build 通过
[ ] Compose 全部服务正常
[ ] student 登录并提交报修成功
[ ] technician 开始维修并提交完工成功
[ ] 维修中状态限制教室可用性
[ ] manager 验收关闭成功
[ ] 全部维修来源解除后教室恢复可用
[ ] 通知、审计和测试邮件可查
[ ] student 创建、查询并取消座位预约成功
[ ] 同座位重叠预约和维修限制被拒绝
[ ] 每日动态码正确，错误码和窗口外签到被拒绝
[ ] student 无法自行读取签到码，manager 展示码后学生手动输入成功
[ ] 配置上限后学生页面与服务端一致，已有预约保留原时段
[ ] 资源编辑启停受到有效个人预约和整室占用保护
[ ] 签到成功后不产生违约
[ ] 未签到自动取消、释放座位且只记录一次违约
[ ] 历史预约可再次预约并重新检查当前冲突
[ ] manager 可登记资源并查看预约统计
[ ] student 提交整教室申请，manager 完成两级审批
[ ] 已批准整教室时段不可预约，撤销后恢复
[ ] 个人预约冲突时整教室申请不抢占
[ ] 维修取消个人/整室预约且不记违约，回调失败恢复后收敛
[ ] 解除维修或重放旧批准任务不会恢复被取消的申请
[ ] manager 派单，technician 开工并提交完工
[ ] manager 可退回或通过验收，student 可评价
[ ] 报修人在开工前可取消本人工单
[ ] smoke 按脚本当前基线全部通过
[ ] m1-regression 通过
[ ] resilience 通过
[ ] 服务在可靠性检查后全部恢复

异常与处理：
最终结论：通过 / 有条件通过 / 不通过
```

## 15. 相关文档

- [项目总览](../README.md)
- [开发环境补充说明](./DEVELOPMENT.md)
- [本地运行与验证](./RUNNING.md)
- [当前进展与交接](./HANDOFF.md)
- [实现范围与验证证据](./IMPLEMENTATION.md)
- [总体架构](./ARCHITECTURE.md)
- [接口设计](./INTERFACES.md)
- [当前 OpenAPI 契约](../contracts/foundation.openapi.json)
