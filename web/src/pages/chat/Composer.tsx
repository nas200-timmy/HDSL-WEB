import { useEffect, useRef, useState } from "react";
import { ArrowForwardIcon, CloseIcon } from "../../components/icons";
import { toast } from "../../store";
import type { ChatAttachment, ChatModelInfo } from "../../types";

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
  models, model, onModel, pending, onUpload, onRemove, uploading,
  quickPhrases,
}: {
  draft: string;
  onDraft: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  streaming: boolean;
  disabled: boolean;
  models: ChatModelInfo[];
  model: string | null;
  onModel: (model: string) => void;
  pending: ChatAttachment[];
  onUpload: (file: File) => void;
  onRemove: (id: string) => void;
  uploading: boolean;
  quickPhrases: string[];
}) {
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [composing, setComposing] = useState(false);
  const [dragging, setDragging] = useState(false);
  const current = models.find((item) => item.id === model) ?? null;
  const canVision = current?.capabilities.vision ?? true;
  const canFiles = current?.capabilities.files ?? true;

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;
    area.style.height = "0px";
    area.style.height = Math.min(240, Math.max(44, area.scrollHeight)) + "px";
  }, [draft]);

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
        toast("info", "当前模型不支持原生文件，Office/PDF 会以抽取文本发送");
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
      {pending.length > 0 && (
        <div className="chat-pending">
          {pending.map((attachment) => (
            <span className="chat-pending-chip" key={attachment.id}>
              <span className="chat-pending-kind">{attachment.kind === "image" ? "IMG" : attachment.kind.toUpperCase()}</span>
              <span className="chat-pending-name">{attachment.name}</span>
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
        placeholder="发消息…（Enter 发送，Shift+Enter 换行）"
        disabled={disabled}
        onChange={(event) => onDraft(event.target.value)}
        onPaste={(event) => {
          const files = Array.from(event.clipboardData.files);
          if (files.length > 0) {
            event.preventDefault();
            void addFiles(files);
          }
        }}
        onCompositionStart={() => setComposing(true)}
        onCompositionEnd={() => setComposing(false)}
        onKeyDown={(event) => {
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
          title="添加附件"
        >
          +
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

        <span className="spacer" />

        <label className="chat-model-pick" title={current ? `上下文 ${current.capabilities.maxContext} · 输出 ${current.capabilities.maxOutput}` : "选择模型"}>
          <select
            value={model ?? ""}
            onChange={(event) => onModel(event.target.value)}
            disabled={disabled || streaming || models.length === 0}
          >
            {models.length === 0 && <option value={model ?? ""}>{model ?? "没有可用模型"}</option>}
            {models.map((item) => (
              <option key={item.id} value={item.id}>
                {item.id}
                {item.capabilities.vision ? " · 图" : ""}
                {item.capabilities.reasoning ? " · 思" : ""}
              </option>
            ))}
          </select>
        </label>

        {streaming ? (
          <button className="chat-send stop" onClick={onStop} title="停止生成">
            <span className="chat-stop-square" />
          </button>
        ) : (
          <button
            className="chat-send"
            onClick={onSend}
            disabled={disabled || uploading || (!draft.trim() && pending.length === 0)}
            title="发送"
          >
            <ArrowForwardIcon size={20} />
          </button>
        )}
      </div>
    </div>
  );
}