import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

export interface PopupMenuProps {
  open: boolean;
  /** 锚点元素：菜单在其左下角弹出（HMCL jfx-popup 风格） */
  anchor: HTMLElement | null;
  onClose: () => void;
  children: ReactNode;
  /** 追加在 .popup-menu 上的类名（宽度约束一类的场景） */
  className?: string;
}

/** 弹出菜单（jfx-popup 样式：surface 底、圆角 4、项 padding 8 16 间距 10）。 */
export function PopupMenu({ open, anchor, onClose, children, className }: PopupMenuProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  useLayoutEffect(() => {
    if (!open || !anchor || !ref.current) return;
    const a = anchor.getBoundingClientRect();
    const m = ref.current.getBoundingClientRect();
    let left = a.left;
    let top = a.bottom + 2;
    if (left + m.width > window.innerWidth - 8) left = Math.max(8, window.innerWidth - m.width - 8);
    if (top + m.height > window.innerHeight - 8) {
      top = Math.max(8, a.top - m.height - 2);
    }
    setPos({ left, top });
  }, [open, anchor]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("keydown", onKey);
    window.addEventListener("resize", onClose);
    return () => {
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", onClose);
    };
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div className={`popup-menu${className ? ` ${className}` : ""}`} ref={ref} style={pos ?? { visibility: "hidden" }} role="menu">
      {children}
    </div>,
    document.body,
  );
}

export function PopupItem({
  icon,
  children,
  danger,
  onClick,
}: {
  icon?: ReactNode;
  children: ReactNode;
  danger?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      className={`popup-item${danger ? " danger" : ""}`}
      role="menuitem"
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
    >
      {icon}
      {children}
    </button>
  );
}

export function PopupSep() {
  return <div className="popup-sep" />;
}
