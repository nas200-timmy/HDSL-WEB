import { useEffect, useRef, useState } from "react";
import { ArrowForwardIcon, AttachFileIcon, CloseIcon, SearchIcon } from "../../components/icons";
import { toast } from "../../store";
import type { ChatAttachment } from "../../types";
import { api } from "../../api";
import { ModelPicker, type ModelGroup } from "./ModelPicker";
import { ThinkingPicker } from "./ThinkingPicker";

const IMAGE_TYPES = ["image/png", "image/jpeg", "image/webp", "image/gif"];

/** 大图在浏览器里压到最长边 2048、JPEG 0.85；GIF 与不支持 canvas 的环境原样上传。 */
async function prepareImage(file: File): Promise<File> {
  if (!file.type.startsWith("image/") || file.type === "image/gif"
      || typeof createImageBitmap !== "function") {
    return file;
  }
  try {
    const bitmap = await createImageBitmap(file);
    const max = 2048;
    if (bitmap.width <= max && bitmap.height <= max && file.size <= 2 * 1024 * 1024) {
      bitmap.close?.();
      return file;
    }
    const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext("2d");
    if (!context) return file;
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", 0.85));
    if (!blob) return file;
    const stem = file.name.replace(/\.[^.]+$/, "") || "image";
    return new File([blob], stem + ".jpg", { type: "image/jpeg" });
  } catch {
    return file;
  }
}

/** 输入区：多行、附件、模型选择、发送/停止一体。 */
export function Composer({
  draft, onDraft, onSend, onStop, streaming, disabled,
  groups, account, model, onPick, onManage, onAdd, pending, onUpload, onRemove, uploading,
  quickPhrases, focusSignal, historyImages, reasoningLevel, onReasoningLevel,
  onUploadText, editing, onCancelEdit, searchEnabled, searchConfigured, onToggleSearch,
}: {
  draft: string;
  onDraft: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  streaming: boolean;
  disabled: boolean;
  groups: ModelGroup[];
  account: string | null;
  model: string | null;
  onPick: (account: string, model: string) => void;
  onManage: () => void;
  onAdd: () => void;
  pending: ChatAttachment[];
  onUpload: (file: File) => void;
  onRemove: (id: string) => void;
  uploading: boolean;
  quickPhrases: string[];
  focusSignal?: number;
  historyImages: number;
  reasoningLevel: string;
  onReasoningLevel: (level: string) => void;
  onUploadText: (text: string) => void;
  editing?: boolean;
  onCancelEdit?: () => void;
  searchEnabled: boolean;
  searchConfigured: boolean;
  onToggleSearch: () => void;
}) {
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [composing, setComposing] = useState(false);
  const [dragging, setDragging] = useState(false);
  const currentGroup = groups.find((group) => group.name === account) ?? null;
  const current = currentGroup?.models.find((item) => item.id === model)
    ?? groups.flatMap((group) => group.models).find((item) => item.id === model)
    ?? null;
  const canVision = current?.capabilities.vision ?? true;
  const reasoningMode = current?.capabilities.reasoningMode
    ?? (current?.capabilities.reasoning
      ? { kind: "effort" as const, canDisable: true, efforts: ["low", "medium", "high"],
          minBudget: 0, maxBudget: 0, source: "builtin" }
      : undefined);
  const canFiles = current?.capabilities.files ?? true;
  const pendingImages = pending.filter((item) => item.kind === "image").length;

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;
    area.style.height = "0px";
    area.style.height = Math.min(240, Math.max(44, area.scrollHeight)) + "px";
  }, [draft]);

  useEffect(() => {
    if (focusSignal) areaRef.current?.focus();
  }, [focusSignal]);

  const addFiles = async (files: FileList | File[] | null) => {
    if (!files) return;
    for (const file of Array.from(files)) {
      if (pending.length >= 8) {
        toast("error", "单条消息最多 8 个附件");
        break;
      }
      const image = IMAGE_TYPES.includes(file.type) || /\.(png|jpe?g|webp|gif)$/i.test(file.name);
      if (image && !canVision) {
        toast("error", "当前模型没有声明图片能力，先到聊天设置里打开或换模型");
        continue;
      }
      if (!image && !canFiles) {
        toast("info", "当前模型未开启原生文件，PDF/Office 会以抽取文本发送");
      }
      const limit = image ? 20 * 1024 * 1024 : 50 * 1024 * 1024;
      if (file.size > limit) {
        toast("error", `${file.name} 超过 ${image ? 20 : 50} MB`);
        continue;
      }
      onUpload(await prepareImage(file));
    }
  };

  return (
    <div
      className={`chat-composer${dragging ? " dragging" : ""}`}
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        void addFiles(event.dataTransfer.files);
      }}
    >
      {editing && (
        <div className="chat-edit-banner">
          <span className="chat-edit-banner-title">编辑中</span>
          <span className="chat-edit-banner-hint">保存后会重新生成这条消息之后的回复</span>
          <button className="chat-edit-banner-close" onClick={onCancelEdit} title="退出编辑">
            <CloseIcon size={14} />
          </button>
        </div>
      )}
      {!canVision && (pendingImages > 0 || historyImages > 0) && (
        <div className="chat-composer-note">
          {pendingImages > 0
            ? `当前模型不支持图片：待发送的 ${pendingImages} 张图片请移除或换模型。`
            : `当前模型不支持图片：上文中的 ${historyImages} 张图片会被省略，只发送文字。`}
        </div>
      )}
      {dragging && <div className="chat-drop-hint">松开上传附件</div>}
      {pending.length > 0 && (
        <div className="chat-pending">
          {pending.map((attachment) => (
            <span className="chat-pending-chip" key={attachment.id}>
              {attachment.kind === "image" ? (
                <img className="chat-pending-thumb" src={api.chat.attachmentUrl(attachment.id)} alt={attachment.name} />
              ) : (
                <span className="chat-pending-kind">{attachment.kind === "document" ? "DOC" : attachment.kind.toUpperCase()}</span>
              )}
              <span className="chat-pending-name">{attachment.name}</span>
              {attachment.extractStatus === "failed" && (
                <span className="chat-pending-warn" title={attachment.extractError ?? "文本抽取失败"}>!</span>
              )}
              <button className="chat-pending-remove" onClick={() => onRemove(attachment.id)} title="移除">
                <CloseIcon size={12} />
              </button>
            </span>
          ))}
        </div>
      )}

      {quickPhrases.length > 0 && !draft && (
        <div className="chat-quick">
          {quickPhrases.map((phrase) => (
            <button key={phrase} className="chat-quick-chip" onClick={() => onDraft(phrase)}>
              {phrase}
            </button>
          ))}
        </div>
      )}

      <textarea
        ref={areaRef}
        className="chat-input"
        value={draft}
        rows={1}
        placeholder={editing ? "编辑这条消息…（Enter 保存，Esc 取消）" : "发消息…（Enter 发送，Shift+Enter 换行）"}
        disabled={disabled}
        onChange={(event) => onDraft(event.target.value)}
        onPaste={(event) => {
          const files = Array.from(event.clipboardData.files);
          if (files.length > 0) {
            event.preventDefault();
            void addFiles(files);
            return;
          }
          // 长文本粘贴不该灌满输入框：超过 100 字直接转成 .txt 附件，模型看到的是附件
          const text = event.clipboardData.getData("text/plain");
          if (text.length > 100) {
            event.preventDefault();
            onUploadText(text);
          }
        }}
        onCompositionStart={() => setComposing(true)}
        onCompositionEnd={() => setComposing(false)}
        onKeyDown={(event) => {
          if (event.key === "Escape" && editing) {
            event.preventDefault();
            onCancelEdit?.();
            return;
          }
          if (event.key === "Enter" && !event.shiftKey && !composing) {
            event.preventDefault();
            if (!streaming && !disabled) onSend();
          }
        }}
      />

      <div className="chat-composer-bar">
        <button
          className="chat-tool ripple-host"
          onClick={() => fileRef.current?.click()}
          disabled={disabled || uploading}
          title="添加图片 / PDF / Office / 文本"
        >
          <AttachFileIcon size={18} />
        </button>
        <input
          ref={fileRef}
          type="file"
          hidden
          multiple
          accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.json,.log,.yml,.yaml,.xml,.mp3,.wav,.mp4,.webm"
          onChange={(event) => {
            void addFiles(event.target.files);
            event.target.value = "";
          }}
        />
        {uploading && <span className="chat-uploading">上传中…</span>}
        {draft.length > 4000 && <span className="chat-charcount">{draft.length.toLocaleString()} 字</span>}

        <span className="spacer" />

        {current && (
          <span className="chat-caps">
            {current.capabilities.vision && <span className="chat-cap" title="支持图片">图</span>}
            {current.capabilities.files && <span className="chat-cap" title="支持原生文件（PDF）">文</span>}
            {current.capabilities.reasoning && <span className="chat-cap" title="支持思考">思</span>}
          </span>
        )}
        <button
          className={`chat-tool chat-search-toggle${searchEnabled ? " active" : ""}`}
          onClick={onToggleSearch}
          disabled={disabled}
          title={searchConfigured
            ? (searchEnabled ? "联网搜索已开启，点击关闭" : "联网搜索：允许模型查网页")
            : "还没配置搜索 key：聊天设置 → 联网搜索"}
        >
          <SearchIcon size={16} />
        </button>
        {reasoningMode && reasoningMode.kind !== "none" && (
          <ThinkingPicker
            level={reasoningLevel}
            mode={reasoningMode}
            disabled={disabled}
            onPick={onReasoningLevel}
          />
        )}
        <ModelPicker
          groups={groups}
          account={account}
          model={model}
          disabled={disabled || streaming}
          onPick={onPick}
          onManage={onManage}
          onAdd={onAdd}
        />

        {streaming ? (
          <button className="chat-send stop" onClick={onStop} title="停止生成">
            <span className="chat-stop-square" />
          </button>
        ) : (
          <button
            className="chat-send"
            onClick={onSend}
            disabled={disabled || uploading || (!draft.trim() && pending.length === 0)}
            title={editing ? "保存修改" : "发送"}
          >
            <ArrowForwardIcon size={20} />
          </button>
        )}
      </div>
    </div>
  );
}