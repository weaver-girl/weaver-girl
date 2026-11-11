import { Alert, Statistic, Tag } from "antd";
import { CodeBlock } from "../components/CodeBlock";
import { PageHero } from "../components/PageHero";
import { caseStudies } from "../data/content";

/**
 * Case studies read top to bottom: what was happening, what the plugin does, and the two real
 * outputs from the repo's demo scripts (with the agent and without it).
 */
export function CasesPage() {
  return (
    <>
      <PageHero
        kicker="CASES"
        title="不是演示概念，是能跑出来的结果。"
        lede="三个案例都来自仓库里的可运行程序：业务代码从未引用插件，脚本会把同一个程序跑两遍——没有 agent 和挂上 agent——下面的输出就是两次真实运行的结果。"
      />

      <div className="page-body">
        <nav className="case-quicknav" aria-label="案例导航">
          {caseStudies.map((study, i) => (
            <a key={study.key} href={`#${study.key}`} className="case-quicknav-item">
              <span>0{i + 1}</span>
              <b>{study.tag}</b>
              <small>{study.title}</small>
            </a>
          ))}
        </nav>

        {caseStudies.map((study, index) => (
          <article key={study.key} id={study.key} className="case-study">
            <header className="case-head">
              <div className="case-meta">
                <Tag color={study.tagColor} className="case-tag">
                  {study.tag}
                </Tag>
                <code className="case-demo">{study.demo}</code>
              </div>
              <h2>
                <span className="case-index">0{index + 1}</span>
                {study.title}
              </h2>
              <p className="case-sub">{study.subtitle}</p>
            </header>

            <div className="case-body">
              <div className="case-narrative">
                <section>
                  <h3>现场</h3>
                  <p>{study.scene}</p>
                </section>
                <section>
                  <h3>问题</h3>
                  <p>{study.problem}</p>
                </section>
                <section>
                  <h3>插件做了什么</h3>
                  <p>{study.approach}</p>
                </section>
                <section>
                  <h3>带来的变化</h3>
                  <ul>
                    {study.value.map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </section>
              </div>

              <aside className="case-side">
                <div className="case-metrics">
                  {study.metrics.map((metric) => (
                    <div key={metric.label} className="case-metric">
                      <Statistic value={metric.value} valueStyle={{ fontSize: 30, fontWeight: 600 }} />
                      <span>{metric.label}</span>
                    </div>
                  ))}
                </div>
                <Alert
                  type="info"
                  showIcon
                  message="自己跑一遍"
                  description={
                    <>
                      <p>在仓库根目录执行，脚本会同时跑有、无 agent 两遍，并用退出码断言结果：</p>
                      <code className="case-run">{study.run}</code>
                    </>
                  }
                />
              </aside>
            </div>

            <div className="case-output">
              <div>
                <h4>{study.beforeLabel}</h4>
                <CodeBlock code={study.before} file="without agent" />
              </div>
              <div>
                <h4>{study.afterLabel}</h4>
                <CodeBlock code={study.after} file="with agent" />
              </div>
            </div>

            <footer className="case-source">
              <span>源码</span>
              {study.sources.map((source) => (
                <a key={source.href} href={source.href} target="_blank" rel="noreferrer">
                  {source.label}
                </a>
              ))}
            </footer>
          </article>
        ))}

        <section className="case-common">
          <p className="eyebrow">为什么这些案例可信</p>
          <h2>三个程序，同一个接入方式。</h2>
          <ul>
            <li>业务程序没有 import 任何插件，也没有为插件预留接口。</li>
            <li>插件 jar 放进 <code className="inline">plugins/</code> 后被发现，移除后行为立即回到原样。</li>
            <li>脚本对两种运行结果做退出码断言：有 agent 为 0，没有 agent 为 2，输出不是手工截取的。</li>
            <li>三个插件都只依赖 <code className="inline">weaver-girl-api</code>，换一个场景就是换一个插件。</li>
          </ul>
        </section>
      </div>
    </>
  );
}
