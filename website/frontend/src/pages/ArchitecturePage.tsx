import { PageHero } from "../components/PageHero";
import { modules } from "../data/content";

const FLOW = [
  { name: "premain", text: "启动时挂入目标 JVM" },
  { name: "PluginLoader", text: "SPI 发现插件" },
  { name: "Registry", text: "登记拦截器" },
  { name: "Advice", text: "内联进目标方法" },
];

export function ArchitecturePage() {
  return (
    <>
      <PageHero
        kicker="ARCHITECTURE"
        title="一条织入链路"
        lede="Agent 在目标 JVM 启动时通过 premain 挂入，插件加载器经 SPI 发现插件并登记拦截器，ByteBuddy 在类加载时把 Advice 内联进目标方法。"
      />

      <div className="flow" style={{ marginTop: 36 }}>
        {FLOW.map((node, i) => (
          <span key={node.name} style={{ display: "contents" }}>
            {i > 0 && <span className="flow-arrow">→</span>}
            <div className="flow-node">
              <b>{node.name}</b>
              <span>{node.text}</span>
            </div>
          </span>
        ))}
      </div>

      <h2 className="subhead">模块</h2>
      <div className="module-list">
        {modules.map((mod) => (
          <div key={mod.key} className="module-row">
            <code>{mod.name}</code>
            <span>{mod.description}</span>
          </div>
        ))}
      </div>

      <h2 className="subhead">运行时要求</h2>
      <p className="prose">
        主代码以 <code className="inline">--release 8</code> 编译，产物只使用 JDK 8 的 API，可以运行在 Java
        8 及以上。构建本身需要 JDK 11 或更高版本。
      </p>
    </>
  );
}
