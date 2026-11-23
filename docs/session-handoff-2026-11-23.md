# 会话交接：v0.6.95 发布前（2026-11-23）

## 状态

- 分支 `main`，HEAD `52baf7b19956681802388bc5f2514aadfeb9c1b5`。
- 这是 v0.6.94 之后 60 个非合并提交的 `--no-ff` 合并（合并对象共 61 个）。
- **尚未推送、尚未打 `v0.6.95` tag、尚未发布 Release / 容器**；本轮只做本地提交。
- 工作区在发布审计时是干净的；本轮改动见下面的「本轮改动」。

```
52baf7b199 merge: web-shell → main（部署预检、反代真实客户端与种子预览极限档）
```

## P0：明文证书引导豁免（已决策：保留）

审计把 `DeveloperGuardFilter.java:126,181` 在「面板明文且 TLS 未启用」时放行
`/api/settings/{tls,certificates,acme,dns-credentials}*` 整族（读也绕过开发者第二因子）
定为 P0。

**维护者决定：保留，不收窄。** 理由：收紧会造成死锁——设置/确认开发者密码的提权只在
HTTPS 下接受，而启用 HTTPS 又要先过开发者门；已设开发者密码的远程明文面板还会被强制
提升到 `developer_guard=ALL`。可以接受放弃明文 HTTP 下这部分安全性。

本轮把该行为写成明确的安全契约，而不是修改代码：

- `docs/security.md` §2.10 新增「明文下的证书引导例外（TLS bootstrap）」：仍要求登录
  会话；明文下这些证书/ACME/DNS 凭据路由不受开发者第二因子保护；把面板以明文暴露给
  不可信网络等于接受该风险；启用 HTTPS 后完整门禁恢复。
- `docs/deployment.md` §2 新增「明文第一次启用 TLS 的 bootstrap 例外」，附可执行验收：
  明文冒烟通过（未提权 ACME 读取 200），HTTPS 冒烟通过（未提权 ACME 读取 403）。
- `web/scripts/security-smoke.mjs` 按新契约分支断言；明文分支额外断言其它 gated 设置
  仍 403，确认豁免范围没有扩大。HTTPS 分支保留原有更严格检查。

## 本轮改动

- 安全契约/文档：`docs/security.md`、`docs/deployment.md`、`README.md`、
  `scripts/README.md`、`scripts/security-smoke.sh` 注释。
- 安全冒烟：`web/scripts/security-smoke.mjs`。
- P1-3 限流漏改：
  - `src/main/java/org/jackhuang/hmcl/web/http/AcmeApiServlet.java`（保存 ACME 凭据前的
    面板口令确认）改用 `ClientAddresses.remote(request, config.network.trustedProxies)`。
  - `src/main/java/org/jackhuang/hmcl/web/brand/BrandApiServlet.java`（品牌管理员口令重置）
    同样改用真实客户端地址。
- `CHANGELOG.md`：新增 v0.6.95（8 个主题），并修掉 `v0.6.90## v0.6.90` 重复标题。
- 本文件。

## 验证（实际执行）

> 跑完后回填；以下命令均在仓库根目录执行。

- `./gradlew test --rerun-tasks`：**1687 例、0 失败、27 跳过**（2m34s）。
- `cd web && npm run typecheck && npm run build && npm run compat`：通过
  （compat-check OK；ES11 / ES5 检查通过）。
- `cd client/hdsl-tui && go test -count=1 ./...`：全部包通过。
- `./gradlew shadowJar -PreleaseVersion=0.6.95`：成功，manifest
  `Implementation-Version: 0.6.95`；`python3 scripts/api-smoke.py`：**43/43 PASS**。
- `./scripts/security-smoke.sh build/libs/hdsl-web-0.6.95.jar`（明文）：**PASS**，
  含「cleartext TLS bootstrap exception allows the gated ACME read」与
  「does not widen to other gated settings」。
- `HDSL_SMOKE_HTTPS=true ./scripts/security-smoke.sh build/libs/hdsl-web-0.6.95.jar`：
  **PASS**，含「gated ACME read is refused without elevation」与凭证 profile 写入。
- `git diff --check`：干净。
- jar：`build/libs/hdsl-web-0.6.95.jar`，sha256
  `538b3afa25fba8826fa9508e63e39f352ab171b7d724fc1e44fa4e637bae6cec`。

首次全量 `./gradlew test` 与 `npm run build` 并行跑时，`syncWebDist` 与 Vite 同时
改写/清理 `web/dist`，`PlainLegacyTest` 的 SSR 预渲染超时失败一次；串行重跑全量
测试通过（1687/0），单测 `PlainLegacyTest` 也单独通过。这不是代码回归，是构建
任务竞争。

## 仍未做

- 没有 push、没有 tag `v0.6.95`、没有合并其它分支、没有发布 Release/容器。
- 审计里的 P2 项（端口预留表、MinGW pin、BrandCatalog 等）未处理，本轮范围不含它们。
