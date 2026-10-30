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

测试沿用现有 SQL 建表，用 H2 MySQL 模式运行真实 JDBC 与 Spring 事务；覆盖正常闭环、越权、非法状态、取消、评价、重复及事务回滚。它不替代真实 MySQL/Flyway 或跨模块 HTTP 联调。当前账号无 Docker socket 权限，不修改权限，不启动或中断共享服务。

实际结果在执行后补充。
