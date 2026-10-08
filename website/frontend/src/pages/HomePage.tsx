import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { CodeBlock } from "../components/CodeBlock";
import { Reveal } from "../components/Reveal";
import { TraceStage } from "../components/TraceStage";
import { features, quickStart } from "../data/content";

const STATS = [
  { value: "16", unit: "个", label: "内置插件，挂上即拦截" },
  { value: "3", unit: "种", label: "写法：API、注解、YAML" },
  { value: "8+", unit: "", label: "Java 8 字节码，JDK 8 到 21" },
  { value: "0", unit: "行", label: "配置，即可启动" },
];

/** Small line icons, one per feature card, drawn on a 24 grid. */
const ICONS: ReactNode[] = [
  <svg key="i" viewBox="0 0 24 24">
    <rect x="3" y="3" width="7.5" height="7.5" rx="2" />
    <rect x="13.5" y="3" width="7.5" height="7.5" rx="2" />
    <rect x="3" y="13.5" width="7.5" height="7.5" rx="2" />
    <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2" />
  </svg>,
  <svg key="i" viewBox="0 0 24 24">
    <path d="M8 7 L4 12 L8 17" />
    <path d="M16 7 L20 12 L16 17" />
    <path d="M13 5 L11 19" />
  </svg>,
  <svg key="i" viewBox="0 0 24 24">
    <path d="M13 3 L5 13 H11 L10 21 L19 10 H13 Z" />
  </svg>,
  <svg key="i" viewBox="0 0 24 24">
    <path d="M4 12 H9 L12 5 L15 19 L17 12 H20" />
  </svg>,
  <svg key="i" viewBox="0 0 24 24">
    <path d="M12 3 L20 6.5 V12 C20 16.5 16.5 20 12 21.5 C7.5 20 4 16.5 4 12 V6.5 Z" />
    <path d="M8.5 12 L11 14.5 L15.5 9.5" />
  </svg>,
  <svg key="i" viewBox="0 0 24 24">
    <circle cx="6" cy="7" r="2.2" />
    <circle cx="18" cy="6" r="2.2" />
    <circle cx="8" cy="18" r="2.2" />
    <circle cx="17" cy="16" r="2.2" />
    <path d="M8 8 L16 7 M7.2 9 L8.6 16 M10 17.5 L15 16.4 M16.4 8 L17.2 14" />
  </svg>,
];

export function HomePage() {
  return (
    <>
      <section className="hero">
        <div className="hero-copy">
          <p className="kicker">
            <span className="kicker-dot" />
            Java 字节码插桩框架
          </p>
          <h1>
            把观测能力
            <br />
            <span className="grad">织进每一个方法</span>
          </h1>
          <p className="lede">
            挂上一个 agent，不用改一行业务代码。方法调用被拦截，调用链自动串起，指标直接导出。
            为 APM 与 IAST 准备的 Hook 底座。
          </p>
          <div className="cta-row">
            <Link to="/quick-start" className="btn btn-primary">
              30 秒上手
            </Link>
            <Link to="/plugins" className="btn btn-ghost">
              看看能拦截什么
            </Link>
          </div>
          <ul className="hero-points">
            <li>基于 ByteBuddy，热路径零分配</li>
            <li>JDK 8 到 21，一份字节码通吃</li>
            <li>Prometheus、OTel、W3C Trace Context</li>
          </ul>
        </div>
        <TraceStage />
      </section>

      <section className="stats">
        {STATS.map((stat) => (
          <div key={stat.label} className="stat">
            <b>
              {stat.value}
              {stat.unit && <small>{stat.unit}</small>}
            </b>
            <span>{stat.label}</span>
          </div>
        ))}
      </section>

      <section className="section wrap">
        <Reveal>
          <div className="section-intro">
            <p className="eyebrow">能力</p>
            <h2>为一个 agent 准备的全部。</h2>
            <p>从拦截到可观测，生产环境需要的自我保护也在里面。</p>
          </div>
        </Reveal>
        <div className="grid-3">
          {features.map((feature, i) => (
            <Reveal key={feature.title}>
              <article className={`feature-card tone-${i % 3}`}>
                <span className="feature-icon">{ICONS[i]}</span>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
              </article>
            </Reveal>
          ))}
        </div>
      </section>

      <section className="band">
        <div className="band-inner">
          <div>
            <p className="eyebrow">为什么用它</p>
            <h2>挂上，就够了。</h2>
            <p>
              16 个内置插件在启动时自动发现并开始拦截。没有配置文件要写，YAML
              改了也不用重启，出问题的拦截器会被熔断器自动摘除。
            </p>
            <Link to="/architecture" className="chev">
              了解它如何工作 ›
            </Link>
          </div>
          <ol className="band-list">
            <li>
              <b>01</b>
              <span>premain 挂入目标 JVM</span>
            </li>
            <li>
              <b>02</b>
              <span>SPI 发现插件并登记拦截器</span>
            </li>
            <li>
              <b>03</b>
              <span>Advice 内联进目标方法</span>
            </li>
            <li>
              <b>04</b>
              <span>调用链、指标、告警即刻可用</span>
            </li>
          </ol>
        </div>
      </section>

      <section className="section wrap">
        <Reveal>
          <div className="section-intro">
            <p className="eyebrow">开始</p>
            <h2>两行命令，跑起来。</h2>
            <p>构建 agent，挂到任何 Java 进程。插件自动生效。</p>
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
