# 面板与 agent：第二把钥匙

> 状态：面板侧、设置页（开发者 → agent 访问）与授权列表均已落地。agent 侧的桥插件已移除，
> 票由 agent 用 **agent 密码**自己换（`/api/developer/agent-auth`），之后用
> `Authorization: Bearer` 调 `/api`。面板不提供「生成票」的按钮或端点。

## 1. 要解决的问题

agent 能做的事从来不是问题——它本来就能用 shell 起一个 Minecraft 服务端。问题是它**那么
做之后，面板以为的世界和真实的世界不一样了**：端口被占、面板显示 STOPPED、两份日志写同
一个 world、会话记录里多出一个面板没启动过的实例。

所以这套东西的目标不是"给 agent 能力"，是**让正确做法比旁路更省事**：

1. 模型知道自己在面板里（`systemPrompt.context`）；
2. 需要访问时走面板的 API（`/api`），票由自己用 agent 密码换；
3. 出了问题能查到是谁干的（审计里的 `agent:<label>`）。

## 2. 两把锁，两扇门

```
开发者密码  ──/api/developer/elevate──▶  会话提权（30 分钟，可延一次到 3 小时）——人用

agent 密码  ──/api/developer/agent-auth──▶  直接发票，不需要会话（curl 可用）
```

**两个密码存在两个文件里**（`auth/developer.json` / `auth/agent.json`），由两个不同的入口
校验。这个分离是刻意的：

- 网页的提权表单永远**不会**接受 agent 密码——把较小的那串密码输进浏览器里，得到的是
  "密码错误"，而不是提权；
- agent 那扇门也**不会**接受开发者密码。

### 借来的权力：agent 密码必须先有人提权

agent 密码**不是**一条独立的路。用它换票之前，必须已经有人在本网页面上用开发者密码提权过；
没人处在这个窗口里时，接口直接拒绝：

```
409 agent_password_not_elevated
```

而且票的寿命**被那个窗口截断**：操作员提权 30 分钟，agent 就算要 3 小时，拿到的也是到
提权结束为止。这样"提权了才能用"不是一句口头约束，是算出来的。

**用票的时候也查一遍。** 窗口可以提前结束——「停止提权」、登出、改或清开发者密码。任何
一种发生，手里那张票立刻失效，不等它自己的到期时间。最后一个人结束提权后，
`/api/developer/agent-auth` 也换不出新票。

面板上的「停止提权」也会**掐掉这次提权发出去的全部票**——把借来的权力还回去，不能只还掉
自己那张。

因此 agent 密码泄漏的后果是有限的：它单独存在时什么都打不开。

### 为什么 agent 密码只在 3、4 档有效

| 守卫档 | 覆盖 | agent 密码 |
|---|---|---|
| `off` | 只守开发者路由 | **拒绝**（409 `agent_password_not_applicable`） |
| `proxy` | 只守 `/i/*` 反代 | **拒绝** |
| `instance` | 外加实例的变更类接口 | 可用 |
| `all` | 全部 `/api/*` | 可用 |

前两档里，一个会话本身就能访问 `/api/*`，再发一张票不会多出任何能力，只会多一个可能泄漏
的凭据。所以那两档直接拒绝，并说明原因。

## 3. 范围（scope）在服务端定，不在客户端里

`AgentScopes` 把面板的接口分成五类：

| scope | 覆盖 |
|---|---|
| `read` | `/api/*` 的 GET（凭据类路由除外，见下） |
| `mc` | `/api/mc/*` 的启停与控制台 |
| `instances` | 建/删/启停**别的**实例 |
| `config` | 账号、模型、TLS、网络、Node 运行时、壁纸 |
| `plugins` | 装/卸插件、整合包、技能 |

四条额外规则，都在 `AuthFilter` 里：

- **未分类的路由一律拒绝**（默认关闭），新接口不会因为没人记得加规则而对 agent 敞开；
- **自杀类操作一律拒绝**（409 `self_operation_refused`）：停自己所在的实例、开自己的终端
  （开终端会先停 web 运行时）。这类操作如果放行，工具调用永远拿不到返回——进程正是被调用
  方自己杀掉的。**拒绝比排队诚实**：排队等于承诺一个已经不存在的执行者。
- **只在 API 边界内**：`/i/*`（反代）与 `/ws/*`（事件流）是浏览器通道，任何 scope 都不放行。
  `read` 不等于"所有 GET"——它只覆盖 `/api/*`。agent 要实例状态就问 API，面板因此记得住
  是谁问的。
- **凭据不出面板**：`/api/brands/*/admin-password`、`/api/exports/*`、`*/open`，以及整个
  `/developer/*`、`/auth/*`，都在网关拒绝，与 scope 无关。字段也要摘：ZCode 的 `token`、
  dsh 实例的 `url`（query 里是访问 token）对 agent 不输出。

判断用的是 **servlet 实际路由的那份解码路径**，不是原始 URI：`%61dmin-password` 在原始
URI 里不叫这个名字，经容器解码后就是它。读原始 URI 的检查等于检查了另一个字符串。

路由自己的提权检查（`requireElevation`）对 agent 一律让路：人的门是开发者密码，agent 的门
是 scope，而 scope 已经在过滤器里验过。要求 agent 输一个它永远没有的密码，等于把已经授权
的范围变成死路。`/api/mc/*/console/input` 之前就是这么坏的。

反过来，**agent 票不是开发者令牌**：两者都以 `Bearer` 出现，但 `DeveloperElevation#requireToken`
只认人那张。否则一张 `read` 票在守卫过滤器里就成了完整提权。

客户端里的白名单是装饰品。agent 与面板同一台机器、同一个 OS 用户，能改任何客户端代码；
真正的边界在 root 进程里。

## 4. 票的生命周期

- 票由面板内存持有，**不落盘**；面板重启即失效。
- **必须先有人提权，而且用的时候还得有人提权。** 发票时到期时间被提权窗口截断；每次请求
  还会再查一次「此刻有没有人提权」。出借人走了（登出、「停止提权」、改或清开发者密码），
  票当场失效，不用等它自己的到期时间。
- **不滑动**：人那张会随使用滑动，agent 这张钉死在发票时给出的时间上。操作员看到"剩 2:47"
  就是真的 2:47。
- 上限 3 小时（与 `extend` 的封顶一致），请求更长的会被**截断而不是拒绝**，设置页显示实际
  拿到的时间。
- 面板列出的是**此刻能用的**票（`GET /api/developer/agent-grants`），可以**单张掐掉**
  （`DELETE /api/developer/agent-grants/{id}`），立即生效。没人提权时列表为空——票已经
  不能用，不该还显示成在用。
- 改或清 agent 密码，会清掉由它发出的**全部**票，但不会影响操作员自己的提权。

## 5. 审计

每张票带着 label 进场，`AuthFilter` 把请求伪装成一个 `agent:<label>` 的会话，所以**所有
既有的 `actorOf(request)` 调用点都不用改**，`audit.jsonl` 里直接就能分出人干的还是模型干的：

```jsonl
{"channel":"web","action":"developer.agent","object":"AbC123","outcome":"mint","actor":"nas200"}
{"channel":"terminal","action":"mc.instance.start","object":"vanilla-1.14.4","outcome":"success","actor":"agent:dsh-0.2.1-alpha.1"}
```

## 6. 已知的边界

- **给开发者密码就没办法了。** 操作员若把开发者密码交给 agent，上面所有限制都可以被绕过
  ——它可以直接提权，再自己发票。这套设计只保证"不需要给开发者密码也能让 agent 干活"。
- **票据泄漏的后果被 scope 兜住。** agent 与面板同一用户，理论上能读到自己进程里的票；但
  即使拿到票，能碰的也只有操作员授权的那几类，且随时可以在面板上掐掉。
- **无人值守没有面板权限。** 这是设计选择，不是缺陷：票到期就得有人重新授权。
- **日志不是脱敏的。** `read` 能读日志，而日志里可能印着 URL、端口、模型回复。面板摘掉了
  结构化凭据（`token`、`url` 字段），但不做日志正文的脱敏——那是运维数据，agent 读得到是
  `read` 的本意。

## 7. 接口一览

| 方法 | 路径 | 需要 | 作用 |
|---|---|---|---|
| `POST` | `/api/developer/agent-auth` | 仅 agent 密码（**免会话**），且必须有人已提权 | 换一张受 cap 限制的票 |
| `GET` | `/api/developer/agent-grants` | 已提权 | 列出当前所有票 |
| `DELETE` | `/api/developer/agent-grants/{id}` | 已提权 | 掐掉一张 |
| `GET/POST` | `/api/developer/agent-password[/clear]` | 已提权 | 设置/查看/清除 agent 密码与它的 cap |

`curl` 一把梭：

```sh
TOKEN=$(curl -sk https://127.0.0.1:3080/api/developer/agent-auth \
  -H 'Content-Type: application/json' \
  -d '{"password":"<agent password>","label":"my-script","scopes":["read","mc"]}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -sk https://127.0.0.1:3080/api/mc/instances \
  -H "Authorization: Bearer $TOKEN"
```

## 8. 还没做的

- 无人值守：票到期仍要有人重新授权（见 §6）。
- 日志正文的脱敏：`read` 能读到日志，面板不替他做内容过滤（见 §6）。
