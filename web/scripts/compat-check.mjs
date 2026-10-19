// 兼容底线守门脚本（browserslist 的 CSS 侧执行者，见 docs/compatibility.md）：
//   1. env(safe-area-inset-*) 必须带前置常量兜底 —— Firefox 136 才认识 env()，
//      115 ESR（Win7 底线）会把整条声明作废；同 dvh 的「双声明」house style。
//      规则：任何 `calc(<N>px + env(` 行，前一条非空行必须是同属性的纯常量声明。
//   2. 产物黑名单：把 115 不支持、Vite/esbuild 又不会转译的 CSS 特性挡在 CI。
//      JS 语法侧由同命令里的 es-check 负责（package.json 的 compat 脚本）。

import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const failures = [];

const nonBlank = (line) => line.trim() !== "";

for (const name of readdirSync(join(root, "src/styles"))) {
  if (!name.endsWith(".css")) continue;
  const lines = readFileSync(join(root, "src/styles", name), "utf8").split("\n");
  lines.forEach((line, i) => {
    const m = line.match(/^\s*([a-z-]+)\s*:\s*calc\((\d+)px\s*\+\s*env\(/);
    if (!m) return;
    const [, prop, px] = m;
    // 往前找最近一条非空行，必须是「同属性 : 同常量 px」的纯声明（兜底行）
    let j = i - 1;
    while (j >= 0 && !nonBlank(lines[j])) j--;
    const fallback = lines[j]?.match(/^\s*([a-z-]+)\s*:\s*(\d+)px\s*;\s*$/);
    if (!fallback || fallback[1] !== prop || fallback[2] !== px) {
      failures.push(
        `${name}:${i + 1}  ${line.trim()}\n  缺少前置兜底声明（应为「${prop}: ${px}px;」）`
      );
    }
  });
}

const BLACKLIST = [
  [/:has\(/, ":has()（Firefox 121+）"],
  [/text-wrap\s*:/, "text-wrap（Firefox 121+）"],
  [/popover=/, "popover 属性（Firefox 125+）"],
];
for (const name of readdirSync(join(root, "dist/assets"))) {
  if (!name.endsWith(".css")) continue;
  const css = readFileSync(join(root, "dist/assets", name), "utf8");
  for (const [re, label] of BLACKLIST) {
    if (re.test(css)) failures.push(`dist/assets/${name} 出现 ${label}`);
  }
}

if (failures.length) {
  console.error("兼容检查未通过：\n\n" + failures.join("\n\n"));
  process.exit(1);
}
console.log("compat-check: OK");
