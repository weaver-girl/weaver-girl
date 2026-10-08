import { Link } from "react-router-dom";
import { CodeBlock } from "../components/CodeBlock";
import { Reveal } from "../components/Reveal";
import { features, quickStart } from "../data/content";

const STATS = [
  { value: "16", label: "个内置插件，挂上即拦截" },
  { value: "3", label: "种写法：API、注解、YAML" },
  { value: "8+", label: "Java 8 字节码，JDK 8 到 21" },
  { value: "0", label: "行配置，即可启动" },
];

const ATTACH = `# 挂载到任何 Java 进程，无需改动源码
java -javaagent:weaver-girl-agent.jar -jar your-app.jar`;

export function HomePage() {
  return (
    <>
      <section className="hero">
        <p className="kicker">Java 字节码插桩框架</p>
        <h1>
          把观测能力，
          <br />
          <span className="grad">织进每一个方法。</span>
        </h1>
        <p className="lede">
          一个 agent，不用改一行业务代码，就能拦截方法调用、串起调用链、导出指标。
          为 APM 与 IAST 准备的 Hook 底座。
        </p>
        <div className="cta-row">
          <Link to="/quick-start" className="btn btn-primary">
            30 秒上手
          </Link>
          <Link to="/plugins" className="chev">
            看看能拦截什么 ›
          </Link>
        </div>
        <div className="product">
          <CodeBlock code={ATTACH} file="terminal" />
        </div>
      </section>

      <Reveal>
        <section className="stats">
          {STATS.map((stat) => (
            <div key={stat.label} className="stat">
              <b>{stat.value}</b>
              <span>{stat.label}</span>
            </div>
          ))}
        </section>
      </Reveal>

      <section className="band">
        <Reveal>
          <h2>挂上，就够了。</h2>
          <p>16 个内置插件自动发现并开始拦截。没有配置文件，也不需要重启。</p>
          <Link to="/architecture" className="chev">
            了解它如何工作 ›
          </Link>
        </Reveal>
      </section>

      <section className="section wrap">
        <Reveal>
          <div className="section-intro">
            <h2>为一个 agent 准备的全部。</h2>
            <p>从拦截到可观测，生产环境需要的自我保护也在里面。</p>
          </div>
        </Reveal>
        <div className="grid-3">
          {features.map((feature) => (
            <Reveal key={feature.title}>
              <article className="feature-card">
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
              </article>
            </Reveal>
          ))}
        </div>
      </section>

      <section className="section wrap">
        <Reveal>
          <div className="section-intro">
            <h2>三行命令，跑起来。</h2>
            <p>构建 agent，挂到任何 Java 进程。</p>
          </div>
          <CodeBlock code={quickStart} file="terminal" />
          <p className="prose" style={{ textAlign: "center" }}>
            <Link to="/quick-start" className="chev">
              查看配置、API 与注解写法 ›
            </Link>
          </p>
        </Reveal>
      </section>
    </>
  );
}
