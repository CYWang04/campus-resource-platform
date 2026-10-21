# VS Code 开发环境准备

更新日期：2026-10-21。VS Code 可以承担本项目的前后端编辑、运行、调试、测试、Git 操作及远程服务器连接。运行环境需要另外准备，扩展不替代 JDK、Node 或容器引擎。

## 团队环境清单

| 软件 | 作用 | 准备方式 |
| --- | --- | --- |
| VS Code | 统一开发工具 | 打开本仓库根目录，安装下列扩展 |
| JDK 21 | 编译、运行 Java 服务 | 可选择 Eclipse Temurin 21，Windows x64 JDK；设置 JAVA_HOME 和命令路径，检查 java 与 javac |
| Node.js 24 + npm | Vue 开发构建、后续 TypeScript 插件 | 使用团队锁定的补丁版本，提交依赖锁文件 |
| Git | 分支、评审和版本管理 | 使用现有 Git，按团队流程操作 |
| Docker Desktop | 运行 MySQL、开发邮件捕获及后续整体容器环境 | Windows 推荐 WSL 2 后端；按官方要求准备虚拟化与 WSL，检查引擎实际可用 |
| 浏览器 | 页面操作、调试及验收 | 使用现有现代浏览器 |

本项目计划提供 Maven Wrapper；骨架加入 Wrapper 后可以由仓库脚本下载和运行固定 Maven，无需每人先全局安装 Maven。当前尚无 Wrapper 或启动命令。

MySQL 8.4、Nginx、邮件捕获服务计划由 Compose 管理，不要求在 Windows 各装一个服务。Compose 文件尚未创建，不能将计划当作已经可启动的环境。

## VS Code 扩展

在扩展面板搜索并核对发布者：

| 扩展 | 发布者/用途 | 优先级 |
| --- | --- | --- |
| Extension Pack for Java | Microsoft，Java 编辑、调试、测试和 Maven 支持 | Java 开发需要 |
| Spring Boot Extension Pack | VMware，Spring Boot 工具、初始化及应用管理 | 后端建议安装 |
| Vue - Official | Vue，Vue 单文件组件与 TypeScript 支持 | 前端需要 |
| ESLint | Microsoft，按项目规则检查前端代码 | 前端规范需要 |
| Prettier - Code formatter | Prettier，按项目配置格式化 | 前端规范建议 |

扩展版本与项目配置的兼容性在骨架阶段确认。格式化及 lint 以仓库配置为准；成员克隆文档不会自动安装扩展或改动个人编辑器设置。发起人本机的授权安装结果见后文。

仓库提供 [扩展推荐](../.vscode/extensions.json)，成员可据此安装相同扩展。下文安装结果仅代表发起人本机，不代表成员克隆后自动安装完成。

## 推荐开发方式

先在 Windows 上用 VS Code 运行 Vue 和三个 Java 服务，便于断点调试；Docker 运行数据库等依赖。之后用 Compose 验证整套系统的部署。WSL 2 可以先只作为 Docker 后端，不要求立即把仓库迁移到 Linux。

## 安装后检查

在新的 VS Code 终端中检查：

```powershell
java -version
javac -version
node --version
npm.cmd --version
git --version
docker version
docker compose version
```

java/javac 应使用 JDK 21，Node 使用团队确定的 24 系列版本；Docker 需同时能连接服务端，仅有客户端版本不代表引擎已运行。系统功能启用和安装可能要求管理员权限及重启，按安装器说明执行。

## 发起人本机只读检查快照

以下仅对应 2026-10-21 的发起人电脑，不代表每位成员的环境，也不是持续自动检测结果。

这是执行安装前的历史快照，当前安装状态见下一节。

- VS Code：1.142.0；Node：24.20.0；npm：11.19.0；Git：2.56.0.windows.2，命令均可用。
- java、javac、docker、mvn 不在当前 PATH；已检查的常见卸载注册表项中未发现 JDK 或 Docker Desktop。不能据此排除某处存在便携安装。
- `wsl.exe --status` 明确返回尚未安装 WSL；此次未验证硬件虚拟化或其他 Docker 系统条件。
- 当前默认 VS Code 扩展列表没有上表五项；已有 Remote-SSH。
- 本次只读取环境并整理文档，未安装软件、扩展，未改系统配置或重启。

## 2026-10-21 实际安装结果

用户随后明确授权安装，已完成以下操作：

| 项目 | 实际结果 |
| --- | --- |
| JDK | Temurin 21.0.12.1+1，用户目录安装，配置用户 JAVA_HOME 和 PATH；实际编译并运行 Java 21 程序通过 |
| WSL | 2.7.13.0，默认版本 2；通过微软官方 MSI 安装成功，退出码 0 |
| Windows 功能 | VirtualMachinePlatform 和 Microsoft-Windows-Subsystem-Linux 已启用；用户已重启，HypervisorPresent=true |
| Docker Desktop | 4.96.0，用户级安装、WSL 2 后端，安装退出码 0 |
| Docker 命令 | CLI 29.9.1、Compose v5.6.0，版本检查通过，用户 PATH 已配置 |
| Docker 引擎 | 重启后已启动，Linux 引擎 29.9.1；完整工具检查及 `hello-world` 容器运行通过 |
| Compose/MySQL | 临时 MySQL 8.4.11 启动、健康检查、建表/增查改删通过，临时容器、网络和卷已清理 |
| VS Code 扩展 | 五项推荐均已安装，包含 Java、Spring Boot 及其依赖、Vue、ESLint、Prettier |
| Node/npm/Git/VS Code | 沿用既有版本；npm 从官方仓库读取 Vue 元数据成功 |

JDK/Docker/WSL 安装包均核对 SHA-256；Docker 安装包数字签名有效。WSL 在线安装遇到 403 后，改用官方发布 MSI。下载、安装日志和用户环境变量备份保存在仓库忽略的 `.work/environment-setup/` 中，不纳入团队版本管理。

Java 实际编译运行、模拟新进程的 PATH、Windows PowerShell 下的工具/扩展检查均通过。安装结束时系统曾要求重启，由用户自行重启后，已完成 Docker 服务端、容器和 MySQL 检查。尚未验证 Testcontainers、JDBC、业务应用或正式数据库迁移，因为工程骨架尚未创建。

## 日常启动与一键检查

1. 发起人已完成首次重启。其他成员首次安装时若系统提示需要重启，先保存工作并完成重启。
2. 开发前启动 Docker Desktop，等待引擎就绪；在 VS Code 打开本仓库根目录。
3. 在 VS Code 的“终端 → 运行任务”中选择 `Environment: Check tools and Docker Engine`。
4. 检查全部工具和 Docker Engine 均为 OK。启动阶段 API 可能暂未就绪，稍后再检查；工具检查成功不代表应用骨架已经存在，后续仍按 TASKS 推进。

[环境检查脚本](../scripts/check-environment.ps1) 和 [VS Code 任务](../.vscode/tasks.json) 已加入仓库；仅检查工具、不自动安装、不启动服务、不改系统设置。也可在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-environment.ps1 -CheckDockerEngine
```

省略 `-CheckDockerEngine` 只检查工具及五项扩展，不能证明 Docker 服务端可用。检查失败返回退出码 1。任务使用进程级执行选项运行可信仓库脚本，此次未修改用户或机器的全局 PowerShell 执行策略；Windows PowerShell 中 npm 可使用 `npm.cmd`。

## 重启后实际运行验证

2026-10-21：WSL 2.7.13.0 正常，Docker Desktop 首次初始化 Linux 数据盘后进入运行状态，当前上下文为 desktop-linux。带引擎检查的仓库脚本全部通过；从官方仓库拉取并运行 `hello-world` 成功。

随后使用 `.work/environment-setup/compose-smoke.yaml` 在随机独立 Compose 项目中启动 `mysql:8.4`。健康检查使用真实 SQL；结果为 MySQL 8.4.11、插入后查询计数 1、更新后计数 1、删除后计数 0。没有发布数据库端口。随机测试密码仅保存在忽略的本地文件中。

检查后按该独立项目执行 down，并删除该测试卷和网络，核对无测试资源残留；MySQL 镜像保留缓存。该临时文件不是项目正式 Compose，临时库也不代表三模块数据库已建立。详细本机记录见忽略的 `.work/environment-setup/post-reboot-summary.json` 与 SQL 结果日志。

## 官方参考

- [VS Code Java 开发](https://code.visualstudio.com/docs/java/java-tutorial)
- [VS Code Spring Boot 支持](https://code.visualstudio.com/docs/java/java-spring-boot)
- [Vue 快速开始](https://vuejs.org/guide/quick-start.html)
- [Temurin JDK 下载](https://adoptium.net/temurin/releases/?version=21)
- [Docker Desktop Windows 安装](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Windows WSL 安装](https://learn.microsoft.com/en-us/windows/wsl/install)
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)
