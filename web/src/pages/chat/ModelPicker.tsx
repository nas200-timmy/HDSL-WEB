import { useEffect, useMemo, useRef, useState } from "react";
import { PopupMenu } from "../../components/PopupMenu";
import {
  AddIcon, CheckIcon, FolderIcon, KeyboardArrowDownIcon, SearchIcon, SettingsIcon, StorageIcon,
} from "../../components/icons";
import type { ChatModelInfo } from "../../types";
import { ModelCaps } from "./ModelCaps";

export interface ModelGroup {
  name: string;
  label: string;
  vendor: string;
  models: ChatModelInfo[];
}

/// Expanded state is kept in localStorage: a 100-model Alibaba account otherwise turns this popup
/// into a scroll marathon every time the page is opened. Empty means every branch is collapsed.
const EXPANDED_SOURCES_KEY = "chat.modelPicker.expandedSources";
const EXPANDED_FAMILIES_KEY = "chat.modelPicker.expandedFamilies";
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

function familyKey(source: string, family: string): string {
  return `${source}\u0000${family}`;
}

/** 模型选择器：资源管理器式树，来源（盘符）→ 品牌（文件夹）→ 模型（文件）。 */
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
  const [expandedSources, setExpandedSources] = useState<Set<string>>(() => readSet(EXPANDED_SOURCES_KEY));
  const [expandedFamilies, setExpandedFamilies] = useState<Set<string>>(() => readSet(EXPANDED_FAMILIES_KEY));
  const [hidden, setHidden] = useState<Set<string>>(() => readSet(HIDDEN_KEY));
  const [showHidden, setShowHidden] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);
  const search = useRef<HTMLInputElement | null>(null);

  useEffect(() => writeSet(EXPANDED_SOURCES_KEY, expandedSources), [expandedSources]);
  useEffect(() => writeSet(EXPANDED_FAMILIES_KEY, expandedFamilies), [expandedFamilies]);
  useEffect(() => writeSet(HIDDEN_KEY, hidden), [hidden]);

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

  const layoutKey = useMemo(() => [
    [...expandedSources].sort().join(","),
    [...expandedFamilies].sort().join(","),
    [...hidden].sort().join(","),
    query,
  ].join("|"), [expandedSources, expandedFamilies, hidden, query]);

  const currentGroup = groups.find((group) => group.name === account) ?? null;
  const current = currentGroup?.models.find((item) => item.id === model)
    ?? groups.flatMap((group) => group.models).find((item) => item.id === model)
    ?? null;

  const q = query.trim().toLowerCase();
  const filtered = useMemo(() => {
    return groups
      .map((group) => {
        const groupMatches = !q
          || group.label.toLowerCase().includes(q)
          || group.vendor.toLowerCase().includes(q);
        return {
          group,
          groupMatches,
          models: groupMatches ? group.models : group.models.filter((item) =>
            item.id.toLowerCase().includes(q)
            || modelFamily(item.id).toLowerCase().includes(q)),
        };
      })
      .filter((entry) => entry.models.length > 0);
  }, [groups, q]);

  const hiddenGroups = useMemo(
    () => filtered.filter((entry) => hidden.has(entry.group.name)).map((entry) => entry.group),
    [filtered, hidden],
  );
  const visible = useMemo(
    () => filtered.filter((entry) => !hidden.has(entry.group.name)),
    [filtered, hidden],
  );

  const toggleSource = (name: string) => {
    setExpandedSources((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const toggleFamily = (source: string, family: string) => {
    const key = familyKey(source, family);
    setExpandedFamilies((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const hide = (name: string) => {
    setHidden((current) => new Set(current).add(name));
    setExpandedSources((current) => {
      const next = new Set(current);
      next.delete(name);
      return next;
    });
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
        repositionKey={layoutKey}
      >
        <div className="chat-model-popup-head">
          <SearchIcon size={14} />
          <input
            ref={search}
            value={query}
            placeholder="搜索模型、品牌或来源"
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="chat-model-popup-list">
          {visible.length === 0 ? (
            <div className="chat-model-popup-empty">
              {groups.length === 0
                ? "还没有可用的模型服务"
                : hiddenGroups.length > 0 ? "匹配的来源都已隐藏" : "没有匹配的模型"}
            </div>
          ) : visible.map(({ group, groupMatches, models }) => {
            const source = group.name;
            const families = familiesOf({ ...group, models });
            const sourceOpen = q ? true : expandedSources.has(source);
            const brandLevel = families.length > 1;
            return (
              <div className="chat-model-popup-tree" key={source}>
                <div className="chat-model-tree-row chat-model-tree-source">
                  <button
                    className="chat-model-tree-toggle"
                    onClick={() => toggleSource(source)}
                    title={sourceOpen ? "收起这个来源" : "展开这个来源"}
                  >
                    <KeyboardArrowDownIcon
                      size={14}
                      className={`chat-model-popup-chevron${sourceOpen ? "" : " collapsed"}`}
                    />
                    <StorageIcon size={15} />
                    <span className="chat-model-tree-label">{group.label}</span>
                    <span className="chat-model-popup-count">{models.length}</span>
                  </button>
                  <button
                    className="chat-model-popup-provider-hide"
                    onClick={() => hide(source)}
                    title="隐藏这个来源"
                  >
                    隐藏
                  </button>
                </div>

                {sourceOpen && (brandLevel ? families.map((family) => {
                  const key = familyKey(source, family.name);
                  const familyOpen = q ? (groupMatches ? false : true) : expandedFamilies.has(key);
                  return (
                    <div key={family.name} className="chat-model-popup-tree">
                      <div className="chat-model-tree-row chat-model-tree-family">
                        <button
                          className="chat-model-tree-toggle"
                          onClick={() => toggleFamily(source, family.name)}
                          title={familyOpen ? "收起这个品牌" : "展开这个品牌"}
                        >
                          <KeyboardArrowDownIcon
                            size={14}
                            className={`chat-model-popup-chevron${familyOpen ? "" : " collapsed"}`}
                          />
                          <FolderIcon size={14} />
                          <span className="chat-model-tree-label">{family.name}</span>
                          <span className="chat-model-popup-count">{family.models.length}</span>
                        </button>
                      </div>
                      {familyOpen && family.models.map((item) => (
                        <ModelRow
                          key={`${source}\u0000${item.id}`}
                          item={item}
                          selected={source === account && item.id === model}
                          depth={3}
                          onPick={() => {
                            onPick(source, item.id);
                            setOpen(false);
                          }}
                        />
                      ))}
                    </div>
                  );
                }) : models.map((item) => (
                  <ModelRow
                    key={`${source}\u0000${item.id}`}
                    item={item}
                    selected={source === account && item.id === model}
                    depth={2}
                    onPick={() => {
                      onPick(source, item.id);
                      setOpen(false);
                    }}
                  />
                )))}
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

function ModelRow({
  item, selected, depth, onPick,
}: {
  item: ChatModelInfo;
  selected: boolean;
  depth: number;
  onPick: () => void;
}) {
  return (
    <button
      className={`chat-model-popup-item chat-model-popup-file depth-${depth}${selected ? " selected" : ""}`}
      onClick={onPick}
    >
      <span className="chat-model-popup-id">{item.id}</span>
      <span className="chat-model-popup-caps">
        <ModelCaps caps={item.capabilities} />
      </span>
      {selected && <CheckIcon size={14} />}
    </button>
  );
}