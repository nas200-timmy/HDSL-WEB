# 浏览器兼容底线

面板 SPA 的兼容底线：**Firefox 115 ESR**——Windows 7/8/8.1 上最后一个受维护的浏览器版本
（Mozilla 对旧平台的 115 ESR 延续安全更新）。同伴底线：Chrome/Edge 109（Chromium 系在
Win7 的最后一版）、Safari/iOS 15.6。基准声明在 `web/package.json` 的 `browserslist`。

## 责任边界

- **面板自身**（登录页、实例/品牌/下载/账户/设置各页）：由本文档的底线覆盖。
- **`/i/{id}/` 反代里的 dsh web、Kimi Code、OpenCode、Claude Web、ZCode 页面**：是上游的
  构建产物，兼容性归上游管。底线只保证「能在面板里正常管理这些实例」，不保证它们的
  网页界面在老浏览器里可用。

## 现状结论（2026-10-19 排查）

- **JS 语法**：Vite `build.target: es2020` 经 esbuild 转译，ES2022+ 语法到不了产物。
- **JS API**：全库未使用 Firefox 115 缺失的 API（`Array.fromAsync`、`Object.groupBy`、
  `Promise.withResolvers`、正则 `v` 标志/lookbehind 等）。当前用到的最新 API 是
  `AbortSignal.timeout`（FF 100+）与 `navigator.clipboard.writeText`（FF 63+，仅 HTTPS，
  调用处已兜底）。
- **CSS**：唯一缺口是 `env(safe-area-inset-*)`——**Firefox 136 才支持**，115 会把含
  `env()` 的整条声明作废。已按 house style 修复（见下）。其余（`:has()`、CSS 嵌套、
  `text-wrap`、`popover`、container queries 之外的新特性）全库未使用；`dvh`（FF 101+）
  与 `color-mix`（FF 113+）在 115 支持范围内。

## `env()` 的兜底写法（house style）

`mobile.css` 里凡是 `calc(常量 + env(...))` 的声明，前面必须有一条同属性同常量的纯声明：

```css
padding-top: 12px;
padding-top: calc(12px + env(safe-area-inset-top));
```

不识别 `env()` 的浏览器应用前一条，识别的浏览器用后一条覆盖——与文件中 `100vh` /
`100dvh` 双声明是同一模式。**新增 `env()` 时必须成对写**，CI 的配对检查会拦住漏写的。

纯 `env(...)`（无常量）的声明不需要兜底：作废即回落为 0，恰是无刘海设备的正确值。

## 守门体系（CI，`build.yml` 的 Compatibility gate / `npm run compat`）

1. **JS 语法**：`es-check es2020` 扫描产物，超纲语法直接红。
2. **CSS 黑名单**（`web/scripts/compat-check.mjs`）：`:has()`、`text-wrap`、`popover`
   出现在产物即红（这些 esbuild/Vite 不会转译）。
3. **env() 配对**：源码里每条 `calc(Npx + env(` 必须紧挨前置兜底声明。
4. **typecheck**：`tsc --noEmit` 已并入 SPA 构建步骤。

引入新依赖或新 API 时，先对照 caniuse 的 Firefox 115 支持矩阵；确实需要 115 没有的 API
时，按需引入 `core-js` 的对应模块，并把例外记在这里。

## 手测清单（改动涉及渲染层时过一遍）

- [ ] 窄窗口（≤760px）触发移动版式：抽屉开合正常、贴底启动栏不遮内容、弹窗不贴边
- [ ] 横屏矮窗（≤520px）同样走移动版式
- [ ] 亮/暗色切换正常
- [ ] 登录 → 建实例 → 启动 → 停止 全链路无 console error
- [ ] 现代浏览器（当前 FF/Chrome）无回归
