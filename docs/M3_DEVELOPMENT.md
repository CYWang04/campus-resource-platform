# M3 本地开发与第一批变更

日期：2026-10-30。分支：`m3`；基线：`c153700`。本页只记录 M3 工作。

## 本地环境

沿用上游 Java 21、Maven 3.9.9、Node 24、Spring Boot 3.5.0 和已有依赖锁文件。Linux x64 工具固定为 Temurin 21.0.13+9、Maven 3.9.9、Node 24.22.2；JDK/Node 补丁与历史 Windows 记录不同，不改项目依赖版本。

工具解压在 `.work/tools/`，Maven/npm 缓存在 `.work/cache/`，不提交；没有修改系统 Java、全局 PATH、shell profile 或 Docker 权限。使用命令包装器仅在子进程选择工具和缓存：

```sh
bash scripts/m3-dev.sh
bash scripts/m3-dev.sh mvn -B -f backend/pom.xml -pl m3 -am verify
```

新机器准备工具时，从下面的官方地址下载归档及校验和，校验一致后用 `tar -xf <归档> -C .work/tools` 解压；包装器不自动下载或安装软件。

| 工具 | 官方归档 | 校验文件 | 解压后目录 |
| --- | --- | --- | --- |
| JDK | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz | 归档地址追加 `.sha256.txt` | `jdk-21.0.13+9` |
| Maven | https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz | 归档地址追加 `.sha512` | `apache-maven-3.9.9` |
| Node | https://nodejs.org/dist/v24.22.2/node-v24.22.2-linux-x64.tar.xz | 同目录 `SHASUMS256.txt` | `node-v24.22.2-linux-x64` |

使用 `sha256sum`（JDK/Node）或 `sha512sum`（Maven）计算归档摘要，与对应官方记录比较。当前机器已执行下载及校验；不依赖 `/home` 缓存。Node 供后续前端开发使用，本批验收针对 M3 后端。

## 第一批范围、方案与验收

关联 B03。先补现有工单状态、职责与事务回归测试，再修复完工命令幂等。改动限定 M3 后端、专项测试和本模块记录，不改变业务接口路径、数据库迁移或其他模块实现。

完工命令应将操作、工单 ID 和说明一起纳入指纹；相同请求重试返回当前工单，不再次写入说明、完工时间或审计。同键不同说明返回 409。旧无说明 `/complete` 路径继续兼容。不同用户的幂等键仍各自隔离。

测试从现有 SQL 构造表，用 H2 MySQL 模式运行真实 JDBC 与 Spring 事务；V2 中 MySQL 的多列 ALTER 和 AFTER 语法仅在测试夹具中转换，不修改生产迁移。覆盖正常闭环、越权、非法状态、取消、评价、重复及事务回滚。它不替代真实 MySQL/Flyway 或跨模块 HTTP 联调。当前账号无 Docker socket 权限，不修改权限，不启动或中断共享服务。

兼容边界：保留原无说明 `/complete` 接口及其指纹；带说明 `/complete-with-note` 使用包含说明的新指纹。升级前已保存的带说明命令没有请求体指纹，无法可靠验证重放内容，重用其旧键会返回 `IDEMPOTENCY_CONFLICT`，客户端应查询工单确认结果；不得自动换键重复执行。无需数据迁移。新增调用方继续使用原请求路径和字段。

## 实际验证

- 本地工具解压与官方校验和检查通过，版本入口及 `bash -n scripts/m3-dev.sh` 通过。
- 首次测试因 H2 不支持 MySQL 多列 ALTER 语法失败；按上文限定转换测试夹具后，原实现的 10 项基线测试通过。
- 加入幂等回归后，在原业务实现上实际出现 3 项失败：重复更新时间、同键变更说明未拒绝、旧完工请求覆盖第二次维修说明。此轮失败日志保留在 `.work/m3/completion-red.log`。
- 修复仅修改 M3 的带说明完工方法：先锁工单、再读命令，状态/说明/时间/命令/审计在同一事务保存；重复请求返回当前工单。新增双线程同键竞争、完工事务回滚和旧命令键兼容检查。
- 最终执行 `bash scripts/m3-dev.sh mvn -B -f backend/pom.xml -pl m3 -am verify` 成功：父工程/common/M3 构建通过，17 项测试通过（15 项数据库测试、2 项原有测试），0 失败、0 错误、0 跳过。`git diff --check` 通过。
- 未执行真实 MySQL/Flyway、完整平台 HTTP、页面或远端 CI 验收；本批不代表整个 M3 已完成。

运行证据保存在忽略目录 `.work/m3/`，不提交依赖、工具归档或原始日志。下一批优先处理派单人员校验及超时升级事务一致性。
