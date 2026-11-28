// 兼容底线守门脚本（browserslist 的 CSS 侧执行者，见 docs/compatibility.md）：
// 把 Firefox 115 不支持、Vite/esbuild 又不会转译的 CSS 特性挡在 CI。
// JS 语法侧由同命令里的 es-check 负责（package.json 的 compat 脚本）；
// 语法检查抓不到「语法合法但运行时 API 缺失」——JS API 黑名单补这一层
// （2026-11 审计：AbortSignal.timeout 击穿 Safari 15.6 底线，全站 API 不可用）。

import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const assets = join(root, "dist/assets");
const failures = [];

const BLACKLIST = [
  [/:has\(/, ":has()（Firefox 121+）"],
  [/text-wrap\s*:/, "text-wrap（Firefox 121+）"],
  [/\[popover\]/, "[popover] 选择器（Firefox 125+）"],
];
for (const name of readdirSync(assets)) {
  if (!name.endsWith(".css")) continue;
  const css = readFileSync(join(assets, name), "utf8");
  for (const [re, label] of BLACKLIST) {
    if (re.test(css)) failures.push(`dist/assets/${name} 出现 ${label}`);
  }
}

// minify:false 保留标识符，可以直接匹配。URLSearchParams.size 这类成员表达式
// 打包后与 Map/Set.size 无法区分，正则抓不准——那一类靠审查（源头已修）。
const JS_BLACKLIST = [
  [/AbortSignal\.timeout/, "AbortSignal.timeout（Safari 16+，底线 15.6）"],
];
for (const name of readdirSync(assets)) {
  if (!name.endsWith(".js")) continue;
  const js = readFileSync(join(assets, name), "utf8");
  for (const [re, label] of JS_BLACKLIST) {
    if (re.test(js)) failures.push(`dist/assets/${name} 出现 ${label}`);
  }
}

if (failures.length) {
  console.error("兼容检查未通过：\n\n" + failures.join("\n\n"));
  process.exit(1);
}
console.log("compat-check: OK");
