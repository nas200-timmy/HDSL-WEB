import type { BrandId } from "./types";

/// Pure-TUI brands: instances only have a PTY. They have no web process, no
/// port and no `/i/<id>/` mount.
export const TUI_BRANDS: ReadonlySet<BrandId> = new Set(["qwen", "mimo", "minimax"]);

export function isTuiBrand(brand: string): boolean {
  return TUI_BRANDS.has(brand as BrandId);
}
