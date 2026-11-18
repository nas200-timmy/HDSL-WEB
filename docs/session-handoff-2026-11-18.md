# 会话交接：v0.6.93 证书管理上线与发布（2026-11-18）

> 本稿接住 **证书管理上线与 v0.6.93 发布**。前稿
> [`2026-11-17`](session-handoff-2026-11-17.md) 的聊天、legacy 表面与发布结论仍然有效；
> 本稿只写证书批次、验证与发布。
>
> **分支状态**：v0.6.92 已被重切到清理后的单提交历史（`e21eb8fc5`）。证书批次直接
> 落在 `main` 上（最后一个功能提交 `0817efa5e`），本稿所在提交是 v0.6.93 的发布提交。
> `main` 比 `origin/main` 多出这些提交；没有 `web-shell` 合并点，tag 直接打在发布提交上。
>
> **隐私约定（继续遵守）**：真实域名、内网地址、宿主机路径、口令、真实 API Key、
> DNS provider token 一律不写进仓库。

## 0. 当前状态

- 证书管理 v1 已落地：按主域名分组、多证书/SNI、ACME DNS-01、上传证书、迁移、
  staging 保护与证书记录。
- 注册表是 `<data>/certs/registry.json`（0600），每张证书的 PEM/私钥在
  `<data>/certs/<id>/`（0600）；DNS/CA 凭据仍在 `<data>/auth/acme.json`（0600 root）。
- 容器入口在 x86-64 + `avx512_vbmi` 主机上自动加 `-XX:UseAVX=2`，规避
  CVE-2026-114938（Java4Shell）；`HDSL_JAVA4SHELL_GUARD=0` 可关闭。
- 本地发布产物：`/tmp/release-0.6.93/`，内含 `hdsl-web-0.6.93.jar`、
  `SHA256SUMS.txt` 与 `notes.md`。

## 1. 这一版包含什么

- **证书管理**：主域名分组；一个主域名下多张证书（ACME/上传），每个主域名各选一张
  `active`；树状分组、覆盖域名查询、总览、证书 Subject/Issuer/SAN/到期/SHA-256 详情；
  申请/上传自动归组与手动指定主域名；子域名成员变更提示「待重新签发」。
- **SNI**：多个主域名同时各用各的证书；首次签发自动成为该主域名默认；不再有
  「PKCS#12 静默优先」的全局规则。
- **ACME**：DNS 凭据独立管理（Secret 不含 zone）；同一凭据可服务多个主域名；
  DNS-01 挑战/订单等待网络重试、60 秒 HTTP 超时；staging 证书不自动设默认，
  手动切换二次确认；网络失败 5 分钟后可重试。
- **迁移**：`https.acme` 变成一张 ACME 证书，`https.pkcs12_file` / `cert_file` 导出
  成一张上传证书；旧文件保留，后续续期只由注册表管理。
- **安全**：证书私钥/注册表/`auth/acme.json` 0600；ACME 只做出站；Java4Shell 容器兜底。

## 2. 分支与发布

- `v0.6.92` tag 指向清理后的 `e21eb8fc5`；证书批次在 `main` 上线性提交，没有再建
  `web-shell` 分支。
- 发布提交（本稿）打 `v0.6.93`；推送 `main` 与 tag，`build` 由 main push 触发，
  `client` / `docker` 由 tag 触发。
- 发布命令：

```bash
git tag -a v0.6.93 -m "v0.6.93 — 证书管理上线"
git -c credential.helper='!gh auth git-credential' \
  push https://github.com/nas200-timmy/HDSL-WEB.git main v0.6.93
gh release create v0.6.93 -R nas200-timmy/HDSL-WEB \
  --title "v0.6.93 — 证书管理上线" \
  --notes-file /tmp/release-0.6.93/notes.md \
  /tmp/release-0.6.93/hdsl-web-0.6.93.jar /tmp/release-0.6.93/SHA256SUMS.txt
```

## 3. 验证与本地产物

- `./gradlew test`：**1606 例、0 失败**（27 跳过）；证书注册表、迁移、签发服务与
  多主域名/SNI 单测通过。
- `npm run typecheck` / `compat`：通过；`go test ./...`：通过。
- `./gradlew shadowJar -PreleaseVersion=0.6.93`：成功，manifest
  `Implementation-Version: 0.6.93`；前端与 SSR bundle 已打包。
- `scripts/api-smoke.py build/libs/hdsl-web-0.6.93.jar`：43/43 PASS。
- jar：`/tmp/release-0.6.93/hdsl-web-0.6.93.jar`，校验和见 `SHA256SUMS.txt`。
- 入口脚本已按 Dockerfile 渲染后 `sh -n` 检查；Java4Shell guard 的四种 `JAVA_OPTS`
  输入（空、已含 0/2/3）逐一验证。
- **未验证**：本版没有新增真实 ACME/DNS 联调；真实签发沿用已有 staging 手工验证。
  Docker 镜像由 CI 构建，容器内 JDK 版本需在发布后 `docker exec … java -version` 核实。

## 4. 发布后核对

- `build`（JDK 17/21）、`client`（五平台 + Release 资产）、`docker`（amd64/arm64 →
  ghcr）三条流水线。
- Release 资产：jar、`SHA256SUMS.txt`、五个平台客户端、源码包与安装脚本。
- 如果 `client` 在 Release 创建前跑完，资产会留在 workflow artifact；Release 创建后
  重跑一次该 tag 的 `client` workflow 即可挂上。

## 5. 下次注意

- JDK 修到 `21.0.13.1+` 后可以去掉 `UseAVX=2` 兜底（或设 `HDSL_JAVA4SHELL_GUARD=0`）；
  裸 jar 用户仍需自己加参数或换 JDK。
- 真实 ACME 签发要能出站访问 CA 与 DNS provider API；staging 证书不要设为默认，
  锁死时用局域网或改 `certs/registry.json` 的 `active`。
- 注册表/私钥/`auth/acme.json` 都是敏感数据；备份 `/data` 时一并保护。
- v0.6.92 的 tag 已被重切一次；不要再移动已发布 tag，除非明确要同版本重发。
