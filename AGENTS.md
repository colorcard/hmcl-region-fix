# AGENTS.md

## 项目速览

- HMCL Region Fix：Java Agent + Swing GUI，修正 HMCL 对亚太用户的“中国大陆”误判。
- 同一个 jar 既是 GUI（`Main-Class`）又是 agent（`Premain-Class`）：`RegionFixGui` 负责查找/启动 HMCL，`RegionFixAgent` 在目标 JVM 内存中修改地区判断。
- 产品边界：只修复“设备/网络实际不在中国大陆”的误判。不要添加绕过地区限制的功能，不要删除首次使用确认或免责声明。

## 构建与测试

需要 JDK 17+ 编译，但字节码必须是 Java 8（`--release 8`），不要使用 Java 9+ API。项目没有 Gradle/Maven/测试框架，只有 `javac`、`jar` 和 POSIX shell。

```bash
./build.sh
./scripts/verify-jar.sh
./tests/run.sh build/HMCL-Region-Fix.jar
```

- 顺序很重要：先构建，再校验/测试。`verify-jar.sh` 会用当前 `src/` 重新编译，并逐字节比对 jar 内的 class；改完源码不重新构建就会失败。
- `tests/run.sh` 只有 3 个 JVM 场景：无 Agent 负向对照、反射路径、字节码回退路径。没有单测框架；定位单条场景时，复制脚本中对应的 `java` 命令。
- Windows 构建用 `build.bat`；headless smoke test：`java -Djava.awt.headless=true -jar build\HMCL-Region-Fix.jar --scan`。
- `SOURCE_DATE_EPOCH` 只有在 `jar --date` 可用时才会固定时间戳（JDK 19+；CI 使用 Temurin 21）。JDK 17 可以构建，但不能字节级复现 CI 产物。

发布级本地校验（`0.0.0` 只是 CI 用来检查包布局的占位版本）：

```bash
SOURCE_DATE_EPOCH=$(git log -1 --format=%ct) ./build.sh
./scripts/verify-jar.sh
./tests/run.sh build/HMCL-Region-Fix.jar
./scripts/package-release.sh 0.0.0 build/HMCL-Region-Fix.jar dist
./scripts/verify-package.sh dist/HMCL-Region-Fix-v0.0.0.zip 0.0.0
```

## 实现要点

- `RegionFixAgent.premain` 先反射把 `RegionUtils.isChinaMainlandNetwork` 置为 `false`；失败时才注册 transformer，把 `Asia/Shanghai` 改成 `Asia/ShanghaX`、`CN` 改成 `XX`、`bipush 45` 改成 `0`。这些“奇怪”的字符串/字节码补丁是兼容回退，不要清理；`tests/` 同时覆盖反射和回退路径。
- 绝不修改 HMCL 的 jar，也不要把补丁改成写盘/重打包；只在当前 JVM 内存生效，否则会破坏 HMCL 自校验、自动更新和登录。
- 保留 `IS_CHINA_MAINLAND_MACHINE` 的值；它用于国内镜像选择，不要和网络地区标志一起置 false。
- `RegionFixGui` 的 `JFrame` 必须在 `createAndShow()` 中创建。`--scan` 和 `--launch` 允许 headless 运行，提前初始化 `JFrame` 会抛 `HeadlessException`。
- CLI 入口：`--scan` 打印候选 HMCL，`--launch <path>` 直接启动。`verify-jar.sh` 的 smoke test 依赖 `--scan`。

## 仓库约定与容易踩的坑

- `verify-jar.sh` 只允许 manifest 和 `io/github/colorcard/hmcl/regionfix/*.class`。新增资源、嵌套 jar 或签名文件前，要同步修改它的白名单。
- release zip 固定包含 6 个文件：jar、`LICENSE`、`README.md`、3 个启动脚本。`verify-package.sh` 会与仓库文件逐字节比较；增删文件要同步修改 `package-release.sh`、`verify-package.sh` 和 `release-audit.yml` 的期望列表。
- 启动脚本必须保留可执行位。`.gitattributes` 强制 `.bat` 为 CRLF、`.sh`/`.command`/`.yml` 为 LF；不要全局 normalize 行尾。
- 所有 GitHub Actions 都按 commit SHA 固定；通过 Dependabot 更新，不要改成 `@vN` 浮动标签。
- `README.md` 是中英双语（中文在前），修改用户文档时两边同步。
- `build/`、`dist/`、`*.jar` 都已忽略；不要提交构建产物或 release 资产。
- `.github/workflows/release.yml` 是发布关键文件（CODEOWNERS 覆盖）。发布 guard 会要求 tag 内的 `release.yml` 与 `main` 完全一致。

## 发布流程

详细策略和验证命令见 `docs/RELEASING.md`。不要手动创建 release 或上传/替换 release 资产。

1. 通过 PR 合入 `main`，等待 `CI success`。
2. 从最新 `main` 创建 annotated tag 并推送：

   ```bash
   git checkout main
   git pull --ff-only
   git tag -a vX.Y.Z -m "HMCL Region Fix vX.Y.Z"
   git push origin vX.Y.Z
   ```

3. 发布任务只有在 canonical 仓库、tag 可从 `main` 到达、tag 内 `release.yml` 与 `main` 一致时才继续。它会从 tag 提交重新构建、测试、打包、生成 Sigstore attestation，先创建 draft，全部校验通过后发布。如果 `release` environment 配置了 required reviewers，publish job 会暂停等待人工批准。
4. 发布前失败可以重跑同一个 run。但如果 `main` 上的 `release.yml` 已经改动，旧 tag 的重跑会被 guard 拒绝；此时应从最新 `main` 打新 tag。
5. 验证 release：`gh release view vX.Y.Z`，或用 `gh attestation verify ...` 校验资产；也可以手动运行审计：

   ```bash
   gh workflow run release-audit.yml -f tag=vX.Y.Z
   ```

6. `v1.0.0` 是旧的手工发布，没有 attestation，不要把它当作可信发布基线。

## Commit 与 PR 格式

- 提交信息使用英文祈使句，首字母大写，不超过 72 字符，不加句号，不使用 `feat:`/`fix:` 前缀（与现有历史一致）。
- 空行后写正文，说明 why 和影响；可引用 issue（如 `Refs #12`）。一个提交只做一件事。
- 示例：`Fix headless --scan crash when no display is available`
- PR 标题使用与主提交相同的祈使句，合并前必须通过 `CI success`。
