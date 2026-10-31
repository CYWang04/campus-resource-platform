# macOS 开发与运行

适用于 Apple Silicon Mac。2026-10-26 环境任务 ENV02：补齐本机工具、配置终端与 VS Code、提供原生 shell 入口，再验证构建和容器运行。业务规则、数据库迁移和跨模块接口不在本次修改范围。

## 本机工具

本次使用官方发行包安装到用户目录，工具清单和校验记录保存在忽略目录 `.work/environment-setup/`。安装和验收的最终结果见 [交接](HANDOFF.md)；以下表格是本次锁定的版本。

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| Amazon Corretto JDK | 21.0.13.0.10 | Java 编译、测试和运行 |
| Node.js / npm | 24.20.0 / 11.19.0 | 前端依赖与构建 |
| Maven | 3.9.9 | 三服务统一构建 |
| Docker CLI | 29.9.2 | 容器操作 |
| Docker Compose | 5.6.1 | 全套环境编排 |
| Docker Buildx | 0.38.1 | 容器镜像构建 |
| Colima / Lima | 0.10.3 / 2.2.1 | 本机 Linux 容器虚拟机 |

工具位于 `~/.local/opt/campus-m1/`，命令入口位于 `~/.local/bin/`。`~/.config/campus-m1/env.zsh` 设置 JAVA_HOME 和 PATH，由 `.zprofile`、`.zshrc` 加载。已有 shell 配置的备份保存在 `.work/environment-setup/shell-backups/`。

新终端会自动加载环境；已有终端可执行：

```sh
source ~/.config/campus-m1/env.zsh
```

已安装仓库推荐的 Java、Spring Boot、Vue、ESLint、Prettier VS Code 扩展。建议从配置完成后的终端执行 `code .`，让编辑器继承当前工具环境。环境检查任务已经增加 macOS/Linux 命令。

## 启动与检查

在仓库根目录执行：

```sh
colima start --profile campus-m1
docker context use colima-campus-m1
bash scripts/check-environment.sh --docker
```

本机项目虚拟机配置为 4 CPU、8 GiB 内存、60 GiB 可增长数据盘，使用 Apple Virtualization 和 virtiofs。Colima 通过 Docker context 提供 socket；MySQL、邮件捕获与 Nginx 均由项目 Compose 运行。

本次已验证 Docker Engine 29.5.2 可通过 `colima-campus-m1` context 访问，工具检查中的 Engine 项也已通过。

### 本机网络配置

当前网络直接访问 Docker Hub 会超时，配置复用了已有的本机 HTTP 代理 `127.0.0.1:7897`。`~/.local/bin/docker` 是为本次安装创建的启动脚本，给 Docker/Buildx 的宿主机身份校验设置代理，再调用 `~/.local/opt/campus-m1/docker/docker` 中保留的官方二进制；已有显式代理环境变量优先。

虚拟机通过 Lima 主机网关 `192.168.5.2:7897` 访问同一代理，Docker daemon 和构建进程均已配置。首次启动发现基础镜像的 `/etc/resolv.conf` 指向不存在的 systemd-resolved 文件，已恢复解析配置，并把修复和 Docker 进程代理写入该 Colima profile 的启动 provision。配置位于 `~/.colima/campus-m1/colima.yaml`，不进入项目仓库。

后续拉取或重新构建镜像时应保持该本机代理可用；代理端口变更时需同步更新 Docker 启动脚本和此 profile。

第一次生成本地配置：

```sh
bash scripts/init-local.sh
```

脚本为数据库和服务身份生成独立随机密钥，使用仅当前用户可读写的 `.env`；重复执行保留现有文件。`.env` 不进入版本管理。

## 构建与完整运行

```sh
bash scripts/maven.sh -B verify
npm --prefix web ci
npm --prefix web run build
COMPOSE_PARALLEL_LIMIT=1 docker compose --env-file .env -f infra/compose.yml up --build -d --wait
```

`maven.sh` 优先使用已安装 Maven；其他成员未安装时，从官方仓库下载固定 3.9.9 并验证 SHA-512 后缓存到 `.work/tools/`。

前端在宿主机开发时执行 `npm --prefix web run dev -- --host 127.0.0.1`。2026-10-30 按用户要求将 Vite 配置一并恢复原版，M1 预约/管理、M2 申请/目录等完整代理仍待前端负责人按 [接口交接](M1_FRONTEND_HANDOFF.md) 接入。8081/8082/8083 适用于在宿主机启动后端；Compose 未发布这些端口，完整 Compose 直接使用 8080 入口。

启动完成后打开 [校园资源平台](http://127.0.0.1:8080) 和 [开发邮件捕获](http://127.0.0.1:8025)。公开演示账号 `student`、`technician`、`manager` 的密码为 `CampusDemo2026!`。

查看状态：

```sh
docker compose --env-file .env -f infra/compose.yml ps
docker compose --env-file .env -f infra/compose.yml logs --tail 80 m1 m2 m3
```

自动检查会写入演示数据，故障恢复脚本还会短暂停止服务；具体范围见 [RUNNING](RUNNING.md)。当前基线的 smoke 时间数据问题另见 [M1-R01](M1_REVIEW.md)，不能把业务测试失败归为工具安装失败。

## 停止

```sh
docker compose --env-file .env -f infra/compose.yml down
colima stop --profile campus-m1
```

普通 `down` 保留数据库卷。日常停止不需要删除 Colima 虚拟机或数据库卷。

## 2026-10-26 本机验收结果

- 新终端 JDK/Node/npm/Maven/Docker/Compose/Buildx 与 Docker Engine 检查通过；Java 21 实际编译运行通过。
- 宿主机 Maven verify、前端严格类型检查和生产构建通过；Docker 镜像内构建及完整 Compose 启动通过。
- MySQL 8.4.11 空库初始化成功，三服务 Flyway 分别完成 4、2、3 条迁移；初始教室 2 间、演示用户 3 名。
- 平台与 Mailpit HTTP、三账号登录、三服务版本、基础数据读取、越权拒绝与注销共 22 项环境检查通过。
- 虚拟机停止/启动后，全套服务恢复，相同 22 项检查再次通过。平台保持运行。
- 此处验证环境可用性，未宣称完整业务 smoke/resilience 已通过。M1 业务待修问题继续按 [核查清单](M1_REVIEW.md) 推进。

## 官方安装依据

- [Amazon Corretto 21 官方发行文件与校验](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
- [Node.js 24.20.0 官方发行文件](https://nodejs.org/dist/v24.20.0/)
- [Maven 官方仓库](https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/)
- [Docker 官方 macOS CLI 发行目录](https://download.docker.com/mac/static/stable/aarch64/)
- [Docker Compose](https://github.com/docker/compose/releases/tag/v5.6.1)、[Buildx](https://github.com/docker/buildx/releases/tag/v0.38.1)
- [Colima 安装](https://github.com/abiosoft/colima/blob/main/docs/INSTALL.md)、[Lima 安装](https://lima-vm.io/docs/installation/)
