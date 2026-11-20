import { useEffect, useMemo, useRef, useState } from "react";
import { PopupMenu } from "../../components/PopupMenu";
import { AddIcon, CheckIcon, KeyboardArrowDownIcon, SearchIcon, SettingsIcon } from "../../components/icons";
import type { ChatModelInfo } from "../../types";
import { ModelCaps } from "./ModelCaps";

export interface ModelGroup {
  name: string;
  label: string;
  vendor: string;
  models: ChatModelInfo[];
}

/// The source and family are kept in localStorage: a 100-model Alibaba account otherwise turns
/// this popup into a scroll marathon every time the page is opened.
const COLLAPSED_KEY = "chat.modelPicker.collapsed";
const HIDDEN_KEY = "chat.modelPicker.hidden";

const FAMILY_ORDER = [
  "Qwen", "DeepSeek", "Kimi", "GLM", "MiniMax", "Claude", "Gemini", "OpenAI",
  "Grok", "Llama", "Mistral", "Doubao", "豆包", "其他",
];

function readSet(key: string): Set<string> {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "[]") as unknown;
    return new Set(Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []);
  } catch {
    return new Set();
  }
}

function hasStored(key: string): boolean {
  try {
    return localStorage.getItem(key) !== null;
  } catch {
    return false;
  }
}

function writeSet(key: string, value: Set<string>) {
  try {
    localStorage.setItem(key, JSON.stringify([...value]));
  } catch {
    // A blocked storage must not make the picker unusable.
  }
}

/// Model families are inferred from the id, not from another directory: the account page may be
/// offline, and the ids are stable enough — `qwen3.8-max`, `glm-5.3`, `kimi-k3`, `MiniMax-M3`.
export function modelFamily(id: string): string {
  const leaf = id.split("/").pop()?.toLowerCase() ?? id.toLowerCase();
  if (leaf.includes("qwen") || leaf.includes("qwq")) return "Qwen";
  if (leaf.includes("deepseek")) return "DeepSeek";
  if (leaf.includes("kimi") || leaf.includes("moonshot")) return "Kimi";
  if (leaf.includes("glm") || leaf.includes("zhipu")) return "GLM";
  if (leaf.includes("minimax")) return "MiniMax";
  if (leaf.includes("claude")) return "Claude";
  if (leaf.includes("gemini") || leaf.includes("gemma")) return "Gemini";
  if (/^(gpt|o[1-9]|chatgpt)/.test(leaf)) return "OpenAI";
  if (leaf.includes("grok")) return "Grok";
  if (leaf.includes("llama")) return "Llama";
  if (leaf.includes("mistral") || leaf.includes("mixtral")) return "Mistral";
  if (leaf.includes("doubao")) return "豆包";
  return "其他";
}

function familiesOf(group: ModelGroup): Array<{ name: string; models: ChatModelInfo[] }> {
  const map = new Map<string, ChatModelInfo[]>();
  for (const model of group.models) {
    const family = modelFamily(model.id);
    const list = map.get(family);
    if (list) list.push(model);
    else map.set(family, [model]);
  }
  return [...map.entries()]
    .map(([name, models]) => ({ name, models }))
    .sort((a, b) => {
      const left = FAMILY_ORDER.indexOf(a.name);
      const right = FAMILY_ORDER.indexOf(b.name);
      const rank = (left < 0 ? FAMILY_ORDER.length : left)
          - (right < 0 ? FAMILY_ORDER.length : right);
      return rank !== 0 ? rank : a.name.localeCompare(b.name, "zh-CN");
    });
}

/** 模型选择器：按来源 + 家族分组，可折叠、可隐藏来源、可搜索；选中后同时切换会话的 account/model。 */
export function ModelPicker({
  groups, account, model, disabled, onPick, onManage, onAdd,
}: {
  groups: ModelGroup[];
  account: string | null;
  model: string | null;
  disabled: boolean;
  onPick: (account: string, model: string) => void;
  onManage: () => void;
  onAdd: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState<Set<string>>(() => readSet(COLLAPSED_KEY));
  const [hidden, setHidden] = useState<Set<string>>(() => readSet(HIDDEN_KEY));
  const [showHidden, setShowHidden] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);
  const search = useRef<HTMLInputElement | null>(null);
  const seeded = useRef(hasStored(COLLAPSED_KEY));

  useEffect(() => {
    if (seeded.current || groups.length === 0) return;
    seeded.current = true;
    // Every source starts collapsed, including the current one. The button already shows the
    // selected model; opening the menu should be a source list, not 191 rows of scroll.
    setCollapsed(new Set(groups.map((group) => group.name)));
  }, [groups, account]);

  useEffect(() => {
    writeSet(COLLAPSED_KEY, collapsed);
  }, [collapsed]);

  useEffect(() => {
    writeSet(HIDDEN_KEY, hidden);
  }, [hidden]);

  // 触屏上不自动聚焦搜索框：否则一点「切换模型」就弹出输入法，键盘又把列表顶掉。
  useEffect(() => {
    if (!open) {
      setQuery("");
      return;
    }
    if (!window.matchMedia("(pointer: fine)").matches) return;
    const frame = requestAnimationFrame(() => requestAnimationFrame(() => search.current?.focus()));
    return () => cancelAnimationFrame(frame);
  }, [open]);

  const currentGroup = groups.find((group) => group.name === account) ?? null;
  const current = currentGroup?.models.find((item) => item.id === model)
    ?? groups.flatMap((group) => group.models).find((item) => item.id === model)
    ?? null;

  const q = query.trim().toLowerCase();
  const filtered = useMemo(() => {
    return groups
      .map((group) => {
        if (!q) return group;
        if (group.label.toLowerCase().includes(q) || group.vendor.toLowerCase().includes(q)) {
          return group;
        }
        return {
          ...group,
          models: group.models.filter((item) =>
            item.id.toLowerCase().includes(q)
            || modelFamily(item.id).toLowerCase().includes(q)),
        };
      })
      .filter((group) => group.models.length > 0);
  }, [groups, q]);

  const hiddenGroups = useMemo(
    () => filtered.filter((group) => hidden.has(group.name)),
    [filtered, hidden],
  );
  const visibleGroups = useMemo(
    () => filtered.filter((group) => !hidden.has(group.name)),
    [filtered, hidden],
  );

  const toggleCollapsed = (name: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const hide = (name: string) => {
    setHidden((current) => new Set(current).add(name));
    setCollapsed((current) => new Set(current).add(name));
  };

  const show = (name: string) => {
    setHidden((current) => {
      const next = new Set(current);
      next.delete(name);
      return next;
    });
  };

  return (
    <>
      <button
        ref={anchor}
        className="chat-model-button"
        disabled={disabled}
        onClick={() => {
          if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
          setOpen((value) => !value);
        }}
        title={current ? `${current.id} · 上下文 ${current.capabilities.maxContext}` : "选择模型"}
      >
        <span className="chat-model-button-main">
          <span className="chat-model-button-label">{current?.id ?? "选择模型"}</span>
          {currentGroup && <span className="chat-model-button-provider">{currentGroup.label}</span>}
        </span>
        <KeyboardArrowDownIcon size={14} />
      </button>

      <PopupMenu
        open={open}
        anchor={anchor.current}
        onClose={() => setOpen(false)}
        className="chat-model-popup"
      >
        <div className="chat-model-popup-head">
          <SearchIcon size={14} />
          <input
            ref={search}
            value={query}
            placeholder="搜索模型、家族或服务"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="chat-model-popup-list">
          {visibleGroups.length === 0 ? (
            <div className="chat-model-popup-empty">
              {groups.length === 0
                ? "还没有可用的模型服务"
                : hiddenGroups.length > 0 ? "匹配的来源都已隐藏" : "没有匹配的模型"}
            </div>
          ) : visibleGroups.map((group) => {
            const isCollapsed = !q && collapsed.has(group.name);
            const families = familiesOf(group);
            return (
              <div className="chat-model-popup-group" key={group.name}>
                <div className="chat-model-popup-provider">
                  <button
                    className="chat-model-popup-provider-main"
                    onClick={() => toggleCollapsed(group.name)}
                    title={isCollapsed ? "展开这个来源" : "收起这个来源"}
                  >
                    <KeyboardArrowDownIcon
                      size={14}
                      className={`chat-model-popup-chevron${isCollapsed ? " collapsed" : ""}`}
                    />
                    <span className="chat-model-popup-provider-name">{group.label}</span>
                    <span className="chat-model-popup-count">{group.models.length}</span>
                  </button>
                  <button
                    className="chat-model-popup-provider-hide"
                    onClick={() => hide(group.name)}
                    title="隐藏这个来源"
                  >
                    隐藏
                  </button>
                </div>
                {!isCollapsed && families.map((family) => (
                  <div key={family.name} className="chat-model-popup-family-block">
                    {families.length > 1 && (
                      <div className="chat-model-popup-family">
                        <span>{family.name}</span>
                        <span className="chat-model-popup-count">{family.models.length}</span>
                      </div>
                    )}
                    {family.models.map((item) => {
                      const selected = group.name === account && item.id === model;
                      return (
                        <button
                          key={`${group.name}\u0000${item.id}`}
                          className={`chat-model-popup-item${selected ? " selected" : ""}`}
                          onClick={() => {
                            onPick(group.name, item.id);
                            setOpen(false);
                          }}
                        >
                          <span className="chat-model-popup-id">{item.id}</span>
                          <span className="chat-model-popup-caps">
                            <ModelCaps caps={item.capabilities} />
                          </span>
                          {selected && <CheckIcon size={14} />}
                        </button>
                      );
                    })}
                  </div>
                ))}
              </div>
            );
          })}
          {hiddenGroups.length > 0 && (
            <div className="chat-model-popup-hidden">
              <button className="chat-model-popup-hidden-toggle" onClick={() => setShowHidden((value) => !value)}>
                {showHidden ? "收起已隐藏来源" : `已隐藏 ${hiddenGroups.length} 个来源`}
              </button>
              {showHidden && hiddenGroups.map((group) => (
                <div className="chat-model-popup-hidden-row" key={group.name}>
                  <span>{group.label}</span>
                  <button onClick={() => show(group.name)}>显示</button>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="chat-model-popup-foot">
          <button
            onClick={() => {
              setOpen(false);
              onAdd();
            }}
          >
            <AddIcon size={14} />
            添加模型服务
          </button>
          <button
            onClick={() => {
              setOpen(false);
              onManage();
            }}
          >
            <SettingsIcon size={14} />
            管理模型服务
          </button>
        </div>
      </PopupMenu>
    </>
  );
}