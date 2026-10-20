# 发布与产物可信性

本项目的发布目标是：**只有从受信任的 GitHub Actions 构建、经过校验并带有构建来源证明的产物，才能进入 GitHub Release。**

## 发布流程

1. 改动先通过 PR 合入 `main`，并确保 `CI success` 检查通过。
2. 在 `main` 的最新提交上创建标签并推送：

   ```bash
   git checkout main
   git pull --ff-only
   git tag -a v1.0.1 -m "HMCL Region Fix v1.0.1"
   git push origin v1.0.1
   ```

3. `.github/workflows/release.yml` 自动执行：
   - 校验标签格式、标签提交是否在 `main` 上、标签中的发布工作流是否与 `main` 一致；
   - 从该标签提交重新构建，**不使用** PR 或普通 CI 运行产生的缓存/产物；
   - 校验 jar 结构与源码一致、Java 8 字节码、入口点、Agent 行为；
   - 生成 zip、`*.sha256` 与 `SHA256SUMS`；
   - 为产物生成 Sigstore 构建来源证明（GitHub artifact attestation）；
   - 先创建 **draft release**，逐个校验 checksum 与 provenance，确认资产列表完全符合预期后才发布。
4. 发布完成后，`Audit release` 工作流会在收到 `release: published` 事件时再次校验资产（由 `GITHUB_TOKEN` 触发的发布不会重复触发，但发布流程本身已经完成同等校验）。维护者也可以手动运行该工作流，审计任意已发布版本。

## 信任边界

发布流程依赖以下约束：

| 约束 | 作用 |
| --- | --- |
| 只在 `colorcard/hmcl-region-fix` 运行 | fork 无法触发正式发布 |
| 标签提交必须可从 `main` 到达 | 不能从任意分支/临时提交发布 |
| 标签中的 `release.yml` 必须与 `main` 一致 | 不能通过修改标签内工作流绕过审查 |
| 发布任务重新构建 | 不复用 PR/CI 产物，避免产物替换 |
| 发布任务只使用 `GITHUB_TOKEN` | 不依赖长期 PAT 或第三方 secrets；写权限仅限 `contents` |
| 所有 Action 按 commit SHA 固定 | 降低上游 Action 被劫持的风险 |
| 发布前校验源码一致性、checksum、attestation | 资产必须可追溯到本次标签构建 |
| 已发布 release 不允许覆盖 | 重跑只能继续未发布的 draft |

## 仓库设置建议

工作流无法替代仓库权限设置。建议维护者在 GitHub 上启用：

- **Branch protection / ruleset for `main`**：要求 PR、要求 `CI success` 通过、禁止 force push。
- **Tag protection for `v*`**：只允许发布负责人创建/移动标签。
- **Environment `release`**：配置 required reviewers，发布任务需要人工批准。如果启用了 deployment branch/tag 限制，请允许 `v*` 标签。
- **Immutable releases**（如果仓库可用）：发布后禁止替换资产。
- 限制拥有 `write` 权限的成员数量；拥有写权限的成员本身就可以修改代码和标签。

## 验证下载

每个 release 都附带 `SHA256SUMS`、`HMCL-Region-Fix.jar.sha256` 和 `HMCL-Region-Fix-vX.Y.Z.zip.sha256`。

```bash
# 校验文件完整性
sha256sum -c SHA256SUMS

# 校验构建来源（需要 GitHub CLI 2.49+）
gh attestation verify HMCL-Region-Fix.jar \
  --repo colorcard/hmcl-region-fix

gh attestation verify HMCL-Region-Fix-vX.Y.Z.zip \
  --repo colorcard/hmcl-region-fix
```

`gh attestation verify` 会检查 Sigstore 签名、签名工作流身份，以及产物是否由本仓库的 `release.yml` 在 GitHub 托管 runner 上构建。

## 本地复现构建

CI 使用 `SOURCE_DATE_EPOCH`（标签提交时间）和 `jar --date` 固定 jar 内条目时间戳。使用相同 JDK（Temurin 21）时可以尝试逐字节复现：

```bash
SOURCE_DATE_EPOCH=$(git log -1 --format=%ct) ./build.sh
shasum -a 256 build/HMCL-Region-Fix.jar
```

跨 JDK 版本时 `Created-By` 等元数据可能不同，因此复现结果应以 CI 使用的 JDK 为准。即使无法逐字节复现，attestation 仍能证明产物由哪个工作流、哪个提交构建。

## 禁止事项

- 不要手动向 release 上传文件；所有资产必须由 `release.yml` 生成。
- 不要复用或转发普通 CI 运行产生的 jar 作为正式发布资产。
- 不要在发布后覆盖已发布 release 的资产；如需修复，发布新版本。
- 不要把 `GITHUB_TOKEN`、PAT 或其他 secrets 暴露给 PR 工作流。本项目 PR 工作流只有 `contents: read` 权限。

## 旧版本说明

`v1.0.0` 在此 CI 流程建立之前手动发布，没有 GitHub Actions 构建来源证明。若需要同等级别的可信性，请用当前流程重新发布一个新版本。
