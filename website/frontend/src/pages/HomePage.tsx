import { Link } from "react-router-dom";
import { CodeBlock } from "../components/CodeBlock";
import { Loom } from "../components/Loom";
import { REPO_URL, features, quickStart } from "../data/content";

const STATS = [
  { value: "16", label: "内置插件，挂上即拦截" },
  { value: "3", label: "种写法：API、注解、YAML" },
  { value: "Java 8+", label: "字节码，JDK 8 到 21 通吃" },
  { value: "0", label: "配置即可启动" },
];

export function HomePage() {
  return (
    <>
      <section className="hero">
        <div>
          <div className="eyebrow">
            <span className="eyebrow-dot" />
            基于 ByteBuddy 的 Java 字节码插桩框架
          </div>
          <h1>
            把观测能力
            <br />
            <em>织进</em>
            <br />
            每一个方法。
          </h1>
          <p className="lede">
            给任意 Java 应用挂上一个 agent，不用改一行源码，就能拦截方法调用、串起调用链、导出指标。
            灵感来自 SkyWalking 与 OpenTelemetry Java Agent，定位是 APM 与 IAST 工具的 Hook 底座。
          </p>
          <div className="cta-row">
            <Link to="/quick-start" className="btn btn-primary">
              30 秒上手
            </Link>
            <a className="btn btn-ghost" href={REPO_URL} target="_blank" rel="noreferrer">
              查看源码
            </a>
          </div>
        </div>
        <div className="hero-art">
          <Loom />
        </div>
      </section>

      <section className="stats">
        {STATS.map((stat) => (
          <div key={stat.label} className="stat">
            <b>{stat.value}</b>
            <span>{stat.label}</span>
          </div>
        ))}
      </section>

      <section className="section">
        <div className="section-head">
          <h2>能做什么</h2>
          <p>从拦截到可观测，生产环境要的自我保护也在里面。</p>
        </div>
        <div className="grid-3">
          {features.map((feature, i) => (
            <article key={feature.title} className="feature-card">
              <span className="feature-index">0{i + 1}</span>
              <h3>{feature.title}</h3>
              <p>{feature.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="section">
        <div className="section-head">
          <h2>立刻跑起来</h2>
          <p>构建 agent，挂到任何 Java 进程。16 个内置插件自动发现并开始拦截。</p>
        </div>
        <CodeBlock code={quickStart} file="terminal" />
        <p className="prose" style={{ marginTop: 16 }}>
          <Link to="/quick-start">接着看配置、编程式 API 与注解写法 →</Link>
        </p>
      </section>
    </>
  );
}
