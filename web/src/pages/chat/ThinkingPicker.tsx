import { useRef, useState } from "react";
import { PopupMenu } from "../../components/PopupMenu";
import { CheckIcon, KeyboardArrowDownIcon } from "../../components/icons";

/** 思考挡位：'' = 跟随助手默认，off = 明确关闭，其余按强度下发。 */
export const THINKING_LEVELS = [
  { value: "", label: "默认", hint: "跟随助手的「思考强度」" },
  { value: "off", label: "关", hint: "不让模型展开思考，最快" },
  { value: "low", label: "低", hint: "够用就停，适合日常问答" },
  { value: "medium", label: "中", hint: "平衡" },
  { value: "high", label: "高", hint: "深入思考，更慢也更贵" },
] as const;

export function thinkingLabel(level: string | null | undefined): string {
  if (!level) return "默认";
  return THINKING_LEVELS.find((item) => item.value === level)?.label ?? level;
}

/** 输入区的思考挡位选择器；只在模型声明了思考能力（或会话已设过）时显示。 */
export function ThinkingPicker({
  level, disabled, onPick,
}: {
  level: string;
  disabled: boolean;
  onPick: (level: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);

  return (
    <>
      <button
        ref={anchor}
        className={`chat-thinking-button${level && level !== "off" ? " active" : ""}${level === "off" ? " muted" : ""}`}
        disabled={disabled}
        title="思考挡位：控制模型展开多少思考"
        onClick={() => {
          if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
          setOpen((value) => !value);
        }}
      >
        思考·{thinkingLabel(level)}
        <KeyboardArrowDownIcon size={12} />
      </button>

      <PopupMenu
        open={open}
        anchor={anchor.current}
        onClose={() => setOpen(false)}
        className="chat-thinking-popup"
      >
        {THINKING_LEVELS.map((item) => (
          <button
            key={item.value || "default"}
            className={`chat-model-popup-item${(level || "") === item.value ? " selected" : ""}`}
            onClick={() => {
              onPick(item.value);
              setOpen(false);
            }}
          >
            <span className="chat-thinking-text">
              <span className="chat-thinking-name">{item.label}</span>
              <span className="chat-thinking-hint">{item.hint}</span>
            </span>
            {(level || "") === item.value && <CheckIcon size={14} />}
          </button>
        ))}
      </PopupMenu>
    </>
  );
}