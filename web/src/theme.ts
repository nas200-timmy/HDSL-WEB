// ---------------------------------------------------------------------------
// 主题（Monet / Material You）
//
// 机制与桌面版一致：种子 #5C6BC0、ColorStyle FIDELITY、Contrast DEFAULT、Spec 2025。
// 启动时用 @material/material-color-utilities 生成亮/暗两套调色板，写入
// `--monet-*` CSS 自定义属性（浅色 = :root，暗色 = [data-theme="dark"]）。
// src/styles/monet.css 里的默认值与桌面版 assets/css/blue.css 逐字一致（浅色）、
// 与本文件生成结果一致（暗色），保证 JS 未运行时页面也正确。
// ---------------------------------------------------------------------------

import { useSyncExternalStore } from "react";
import {
  Hct,
  SchemeFidelity,
  argbFromHex,
  hexFromArgb,
  type DynamicScheme,
} from "@material/material-color-utilities";

const SEED = "#5C6BC0";
const STORAGE_KEY = "hdsl.theme.mode";

/** 导出为 --monet-<kebab> 的角色（与桌面版 blue.css 的变量名一一对应） */
const ROLES = [
  "primary",
  "onPrimary",
  "primaryContainer",
  "onPrimaryContainer",
  "primaryFixed",
  "primaryFixedDim",
  "onPrimaryFixed",
  "onPrimaryFixedVariant",
  "secondary",
  "onSecondary",
  "secondaryContainer",
  "onSecondaryContainer",
  "secondaryFixed",
  "secondaryFixedDim",
  "onSecondaryFixed",
  "onSecondaryFixedVariant",
  "tertiary",
  "onTertiary",
  "tertiaryContainer",
  "onTertiaryContainer",
  "tertiaryFixed",
  "tertiaryFixedDim",
  "onTertiaryFixed",
  "onTertiaryFixedVariant",
  "error",
  "onError",
  "errorContainer",
  "onErrorContainer",
  "surface",
  "onSurface",
  "surfaceDim",
  "surfaceBright",
  "surfaceContainerLowest",
  "surfaceContainerLow",
  "surfaceContainer",
  "surfaceContainerHigh",
  "surfaceContainerHighest",
  "surfaceVariant",
  "onSurfaceVariant",
  "background",
  "onBackground",
  "outline",
  "outlineVariant",
  "shadow",
  "scrim",
  "inverseSurface",
  "inverseOnSurface",
  "inversePrimary",
  "surfaceTint",
] as const;

type Role = (typeof ROLES)[number];
type Palette = Partial<Record<Role, string>>;

function kebab(role: Role): string {
  return role.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase());
}

/** 透明度派生：主色/次色容器/surface/inverse-surface 的 -50/-80 变体（与 blue.css 同名同规则） */
function transparentDerivatives(p: Palette): Record<string, string> {
  const d: Record<string, string> = {};
  const put = (name: string, color: string | undefined, alpha: string) => {
    if (color) d[name] = `${color}${alpha}`;
  };
  put("primary-transparent-50", p.primary, "80");
  put("primary-transparent-80", p.primary, "CC");
  put("secondary-container-transparent-50", p.secondaryContainer, "80");
  put("secondary-container-transparent-80", p.secondaryContainer, "CC");
  put("surface-transparent-50", p.surface, "80");
  put("surface-transparent-80", p.surface, "CC");
  put("surface-container-low-transparent-80", p.surfaceContainerLow, "CC");
  put("inverse-surface-transparent-80", p.inverseSurface, "CC");
  put("on-surface-variant-transparent-38", p.onSurfaceVariant, "61");
  return d;
}

function generatePalette(isDark: boolean): Palette {
  const seed = Hct.fromInt(argbFromHex(SEED));
  // FIDELITY 风格（桌面版 HSLCToner 对应）、Contrast 0、Spec 2025
  const scheme: DynamicScheme = new SchemeFidelity(seed, isDark, 0, "2025");
  const colors = (scheme as unknown as { colors: Record<string, () => { getArgb(s: DynamicScheme): number }> })
    .colors;
  const palette: Palette = {};
  for (const role of ROLES) {
    const accessor = colors[role];
    if (typeof accessor === "function") {
      palette[role] = hexFromArgb(accessor().getArgb(scheme)).toUpperCase();
    }
  }
  return palette;
}

function declarations(p: Palette): string {
  const lines = ROLES.filter((r) => p[r]).map((r) => `--monet-${kebab(r)}: ${p[r]};`);
  for (const [name, value] of Object.entries(transparentDerivatives(p))) {
    lines.push(`--monet-${name}: ${value};`);
  }
  return lines.join("\n  ");
}

/** 启动时生成亮/暗两套调色板并写入样式表（覆盖 monet.css 中的同名字段） */
export function initTheme(): void {
  try {
    const light = generatePalette(false);
    const dark = generatePalette(true);
    let style = document.getElementById("monet-runtime");
    if (!style) {
      style = document.createElement("style");
      style.id = "monet-runtime";
      document.head.appendChild(style);
    }
    style.textContent = `:root {\n  ${declarations(light)}\n}\n[data-theme="dark"] {\n  ${declarations(dark)}\n}`;
  } catch {
    // 生成失败时回落到 monet.css 内嵌的默认值（与库输出一致）
  }
  applyResolvedTheme();
}

// ---------------------------------------------------------------------------
// 主题模式：auto（跟随系统）/ light / dark，localStorage 持久化
// ---------------------------------------------------------------------------

export type ThemeMode = "auto" | "light" | "dark";

export function getThemeMode(): ThemeMode {
  const v = localStorage.getItem(STORAGE_KEY);
  return v === "light" || v === "dark" ? v : "auto";
}

export function setThemeMode(mode: ThemeMode): void {
  localStorage.setItem(STORAGE_KEY, mode);
  applyResolvedTheme();
  emitModeChange();
}

function systemDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function applyResolvedTheme(): void {
  const mode = getThemeMode();
  const resolved = mode === "auto" ? (systemDark() ? "dark" : "light") : mode;
  document.documentElement.dataset.theme = resolved;
}

const modeListeners = new Set<() => void>();

function emitModeChange(): void {
  for (const l of [...modeListeners]) l();
}

function subscribeMode(listener: () => void): () => void {
  modeListeners.add(listener);
  return () => {
    modeListeners.delete(listener);
  };
}

/** 订阅当前主题模式（设置-外观页切换用） */
export function useThemeMode(): ThemeMode {
  return useSyncExternalStore(subscribeMode, getThemeMode);
}

/** 安装系统主题跟随监听；返回清理函数 */
export function bindSystemThemeListener(): () => void {
  const mq = window.matchMedia("(prefers-color-scheme: dark)");
  const onChange = () => {
    if (getThemeMode() === "auto") applyResolvedTheme();
  };
  mq.addEventListener("change", onChange);
  return () => mq.removeEventListener("change", onChange);
}
