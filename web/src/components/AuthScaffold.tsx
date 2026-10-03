import type { ReactNode } from "react";

function wallpaper(): string {
  return `${import.meta.env.BASE_URL}assets-img/wallpapers/2021-08-26.jpg`;
}

/** 认证页公共骨架：壁纸背景 + 居中 HMCL 对话框式卡片（surface-container-high、圆角 4、20px 标题）。
 *  登录页与首次启动引导页共用，仅表单内容不同。 */
export function AuthScaffold({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="page-root" style={{ animation: "none" }}>
      <div
        className="wallpaper-bg"
        style={{ backgroundImage: `url(${wallpaper()})` }}
      />
      <div
        style={{
          position: "relative",
          flex: 1,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <div
          className="dialog"
          style={{ minWidth: 340, width: 380, position: "static", animation: "dialog-in 200ms var(--ease)" }}
        >
          <div className="dialog-title">{title}</div>
          {children}
        </div>
      </div>
    </div>
  );
}
