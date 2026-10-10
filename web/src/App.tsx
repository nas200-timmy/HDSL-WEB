import { useEffect } from "react";
import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { ErrorIcon } from "./components/icons";
import { Decorator } from "./components/Decorator";
import { SnackbarHost } from "./components/Snackbar";
import { AccountsPage } from "./pages/AccountsPage";
import { DownloadPage } from "./pages/DownloadPage";
import { InstallInstanceWizard } from "./pages/InstallInstanceWizard";
import { InstallModpackWizard } from "./pages/InstallModpackWizard";
import { InstanceDetailPage } from "./pages/InstanceDetailPage";
import { InstancesPage } from "./pages/InstancesPage";
import { LoginPage } from "./pages/LoginPage";
import { MainPage } from "./pages/MainPage";
import { SettingsPage } from "./pages/SettingsPage";
import { SetupPage } from "./pages/SetupPage";
import { bootstrap, useAppState } from "./store";
import { refreshServerVersion } from "./version";

function SplashScreen() {
  return (
    <div className="screen-full">
      <div className="spinner" />
      <div className="screen-title">HDSL 面板</div>
    </div>
  );
}

function ErrorScreen({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="screen-full">
      <div className="screen-error-card">
        <ErrorIcon size={30} style={{ color: "var(--monet-error)" }} />
        <h2>无法连接服务器</h2>
        <p>{message}</p>
        <button className="btn btn-raised ripple-host" onClick={onRetry}>
          重试
        </button>
      </div>
    </div>
  );
}

function RequireAuth() {
  const s = useAppState();
  const location = useLocation();
  if (!s.authChecked) return <SplashScreen />;
  if (s.authError) return <ErrorScreen message={s.authError} onRetry={() => void bootstrap()} />;
  if (!s.username) {
    return (
      <Navigate
        to={s.setupRequired ? "/setup" : "/login"}
        replace
        state={{ from: location.pathname }}
      />
    );
  }
  return <Outlet />;
}

/** 已登录区域：浏览器内窗口（Decorator）+ 路由动画容器。
 * key 只取 pathname：同一页内的 ?tab= 切换不重挂载（保留 ACP 会话等页内状态），
 * tab 内容的 SLIDE_UP_FADE_IN 动画由各页自行施加。
 * 内页背景 = 壁纸（侧栏/内容区的半透明板透出壁纸，见 ui-spec.md §4）。 */
function Shell() {
  const location = useLocation();
  return (
    <Decorator>
      <div
        className="page-root"
        key={location.pathname}
        style={{
          flex: 1,
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          backgroundImage: `url(assets-img/wallpapers/2021-08-26.jpg)`,
          backgroundSize: "cover",
          backgroundPosition: "center bottom",
        }}
      >
        <Outlet />
      </div>
    </Decorator>
  );
}

export default function App() {
  const s = useAppState();

  useEffect(() => {
    void bootstrap();
  }, []);

  // 登录后拉一次服务端版本（标题栏 "Hello DeepSeek! Launcher vX.Y.Z"）
  useEffect(() => {
    if (s.username) void refreshServerVersion();
  }, [s.username]);

  return (
    <>
      <Routes>
        <Route
          path="/login"
          element={
            <Decorator>
              <div className="page-root" style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
                <LoginPage />
              </div>
            </Decorator>
          }
        />
        <Route
          path="/setup"
          element={
            <Decorator>
              <div className="page-root" style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
                <SetupPage />
              </div>
            </Decorator>
          }
        />
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route path="/" element={<MainPage />} />
            <Route path="/instances" element={<InstancesPage />} />
            <Route path="/instances/new" element={<InstallInstanceWizard />} />
            <Route path="/instances/:id" element={<InstanceDetailPage />} />
            <Route path="/download" element={<DownloadPage />} />
            <Route path="/install/modpack" element={<InstallModpackWizard />} />
            <Route path="/accounts" element={<AccountsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            {/* 旧版路径兼容 */}
            <Route path="/packs" element={<Navigate to="/download?tab=modpack" replace />} />
            <Route path="/plugins" element={<Navigate to="/download?tab=plugin" replace />} />
            <Route path="/doctor" element={<Navigate to="/settings?tab=doctor" replace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Route>
      </Routes>
      <SnackbarHost />
    </>
  );
}
