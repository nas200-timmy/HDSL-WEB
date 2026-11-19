import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api, type McInstance } from "../api";
import { McSeedPreview } from "../components/McSeedPreview";
import { ArrowBackIcon, PublicIcon } from "../components/icons";

/**
 * 种子预览的独立页面（`/mc/preview`）。
 *
 * 之所以要一个独立入口：试种子是个"来回比较"的动作，不该先进入某台服务器
 * 的详情页才找得到。这里可以先用实例的版本/种子起手，也可以直接填任意版本和
 * 种子——预览本来就是一次性进程，和哪台实例没有关系。
 */
export function McSeedPreviewPage() {
  const nav = useNavigate();
  const [instances, setInstances] = useState<McInstance[]>([]);
  const [picked, setPicked] = useState<string>("");
  const [initial, setInitial] = useState<{ version: string; seed: string }>({ version: "", seed: "" });
  // 换实例时重挂卡片，让默认值真正回到输入框里。
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    api.mc
      .instances()
      .then((page) => {
        setInstances(page.instances);
        if (page.instances.length > 0) {
          const first = page.instances[0];
          setPicked(first.id);
          setInitial({ version: first.version, seed: first.seed ?? "" });
        }
      })
      .catch(() => setInstances([]));
  }, []);

  const choose = (id: string) => {
    setPicked(id);
    const instance = instances.find((entry) => entry.id === id);
    if (instance) {
      setInitial({ version: instance.version, seed: instance.seed ?? "" });
      setGeneration((value) => value + 1);
    }
  };

  return (
    <div className="page-with-sidebar">
      <nav className="sidebar-sub sidebar-stack" style={{ paddingTop: 0 }}>
        <div className="side-actions">
          <button className="side-item ripple-host" onClick={() => nav("/instances?view=mc")}>
            <span className="side-graphic">
              <ArrowBackIcon size={20} />
            </span>
            <span className="side-text">
              <span className="side-title">返回 MC 列表</span>
            </span>
          </button>
        </div>
      </nav>

      <div className="page-content scroll" style={{ gap: 8 }}>
        <div className="card" style={{ padding: 16, display: "flex", flexWrap: "wrap", gap: 12, alignItems: "flex-end" }}>
          <div style={{ flex: "1 1 240px" }}>
            <h2 style={{ margin: 0, display: "flex", alignItems: "center", gap: 8 }}>
              <PublicIcon size={20} />
              种子预览
            </h2>
            <p className="dim" style={{ margin: "4px 0 0", fontSize: 13 }}>
              不用先进服务器详情。选一个实例带出版本和种子，或者直接手填。
            </p>
          </div>
          {instances.length > 0 && (
            <label style={{ display: "flex", flexDirection: "column", gap: 4 }}>
              <span className="dim" style={{ fontSize: 12 }}>从实例带入</span>
              <select
                value={picked}
                onChange={(event) => choose(event.target.value)}
                style={{ padding: "6px 8px", minWidth: 220 }}
              >
                {instances.map((instance) => (
                  <option key={instance.id} value={instance.id}>
                    {instance.name} · {instance.version}
                  </option>
                ))}
              </select>
            </label>
          )}
        </div>

        <McSeedPreview
          key={generation}
          initialVersion={initial.version}
          initialSeed={initial.seed}
        />
      </div>
    </div>
  );
}
