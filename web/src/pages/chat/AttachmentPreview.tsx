import { useEffect, useState } from "react";
import type { ChatAttachment } from "../../types";
import { api } from "../../api";
import { CloseIcon, DownloadIcon } from "../../components/icons";

const PAGE_CHARS = 65536;

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function kindLabel(kind: string): string {
  switch (kind) {
    case "image": return "图片";
    case "video": return "视频";
    case "audio": return "音频";
    case "document": return "文档";
    case "text": return "文本";
    default: return "附件";
  }
}

export function AttachmentPreview({
  attachment,
  onClose,
}: {
  attachment: ChatAttachment;
  onClose: () => void;
}) {
  const isPdf = attachment.mime === "application/pdf";
  const textual = !isPdf && (attachment.kind === "text" || attachment.kind === "document");
  const url = api.chat.attachmentUrl(attachment.id);

  const [text, setText] = useState("");
  const [hasMore, setHasMore] = useState(false);
  const [nextOffset, setNextOffset] = useState(0);
  const [charset, setCharset] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    if (!textual) return;
    let cancelled = false;
    setText("");
    setHasMore(false);
    setNextOffset(0);
    setCharset("");
    setError(null);
    setLoading(true);
    api.chat.attachmentText(attachment.id, 0, PAGE_CHARS)
      .then((page) => {
        if (cancelled) return;
        setText(page.text);
        setHasMore(page.hasMore);
        setNextOffset(page.nextOffset);
        setCharset(page.charset);
      })
      .catch((reason) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [attachment.id, textual]);

  const loadMore = async () => {
    if (loading || !hasMore) return;
    setLoading(true);
    try {
      const page = await api.chat.attachmentText(attachment.id, nextOffset, PAGE_CHARS);
      setText((current) => current + page.text);
      setHasMore(page.hasMore);
      setNextOffset(page.nextOffset);
      setCharset(page.charset);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setLoading(false);
    }
  };

  return (
    <aside className="chat-preview" aria-label={`附件预览：${attachment.name}`}>
      <div className="chat-preview-head">
        <div className="chat-preview-title" title={attachment.name}>{attachment.name}</div>
        <button className="icon-btn" onClick={onClose} title="关闭预览">
          <CloseIcon size={18} />
        </button>
      </div>
      <div className="chat-preview-meta">
        <span>{kindLabel(attachment.kind)}</span>
        <span>{formatSize(attachment.size)}</span>
        {charset && <span>{charset}</span>}
        <span className="spacer" />
        <a className="chat-preview-action" href={url} target="_blank" rel="noreferrer noopener">
          新标签页
        </a>
        <a
          className="chat-preview-action"
          href={api.chat.attachmentDownloadUrl(attachment.id)}
          download={attachment.name}
        >
          <DownloadIcon size={15} />
          下载
        </a>
      </div>

      <div className="chat-preview-body scroll">
        {attachment.kind === "image" && (
          <img className="chat-preview-image" src={url} alt={attachment.name} />
        )}
        {attachment.kind === "video" && (
          <video className="chat-preview-video" src={url} controls preload="metadata" />
        )}
        {attachment.kind === "audio" && (
          <audio className="chat-preview-audio" src={url} controls preload="metadata" />
        )}
        {isPdf && (
          <iframe className="chat-preview-pdf" src={url} title={attachment.name} />
        )}
        {textual && (
          <>
            {attachment.kind === "document" && (
              <div className="chat-preview-note">
                这是服务器抽取的文本，不是原文档版式；下载可查看原件。
              </div>
            )}
            {attachment.extractStatus === "failed" && (
              <div className="chat-preview-note error">
                文本抽取失败：{attachment.extractError ?? "未知原因"}，只能下载原件。
              </div>
            )}
            {error ? (
              <div className="chat-preview-error">{error}</div>
            ) : (
              <pre className="chat-preview-text">
                {text || (loading ? "读取中…" : "（空文件）")}
              </pre>
            )}
            {hasMore && (
              <button className="btn btn-outline" disabled={loading} onClick={() => void loadMore()}>
                {loading ? "加载中…" : "加载更多"}
              </button>
            )}
          </>
        )}
        {!textual && !isPdf && attachment.kind !== "image"
          && attachment.kind !== "video" && attachment.kind !== "audio" && (
            <div className="chat-preview-empty">
              这个类型没有内嵌预览，可下载后用本地应用打开。
            </div>
          )}
      </div>
    </aside>
  );
}
