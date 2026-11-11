import { Alert } from "antd";
import { PageHero } from "../components/PageHero";
import { modules } from "../data/content";

const FLOW = [
  { name: "premain", text: "启动时挂入目标 JVM" },
  { name: "plugins/", text: "按 jar 发现能力" },
  { name: "Registry", text: "登记拦截器与配置" },
  { name: "Advice", text: "内联进目标方法" },
];

export function ArchitecturePage() {
  return (
    <>
      <PageHero
        kicker="ARCHITECTURE"
        title="一条织入链路"
        lede="Agent 通过 premain（或运行期 agentmain）挂入目标 JVM，从插件目录发现能力并登记拦截器，ByteBuddy 在类加载或重转换时把 Advice 内联进目标方法。"
      />

      <div className="page-body">
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

      <Alert
        className="arch-alert"
        type="info"
        showIcon
        message="agent jar 本身不含插件实现"
        description="能力按 jar 拆分：内置插件在 agent 旁边的 plugins/，运行时能力配置对应键后才加载。少了 plugins/，agent 能启动但不会拦截任何东西。"
      />

      <h2 className="subhead">模块</h2>
      <div className="module-list">
        {modules.map((mod) => (
          <div key={mod.key} className="module-row">
            <code>{mod.name}</code>
            <span>{mod.description}</span>
          </div>
        ))}
      </div>

      <h2 className="subhead">类加载隔离</h2>
      <p className="prose">
        Agent 使用 child-first 的 <code className="inline">AgentClassLoader</code> 装载 ByteBuddy 与插件，避免和业务
        classpath 上的同名库打架；Advice 通过桥接类回连注册表。打包冒烟会校验 ByteBuddy 确实解析自
        <code className="inline">AgentClassLoader</code>，防止隔离退化。
      </p>

      <h2 className="subhead">运行时要求</h2>
      <p className="prose">
        主代码以 <code className="inline">--release 8</code> 编译，产物只使用 JDK 8 的 API，可以运行在 Java 8 及以上；
        发布门禁在 JDK 17 上执行完整构建与质量检查。已用打包后的 agent 在 JDK 8、11、17、21 上做过真实 attach 冒烟。
      </p>
      </div>
    </>
  );
}
