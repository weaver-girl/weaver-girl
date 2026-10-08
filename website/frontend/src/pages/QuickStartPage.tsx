import { useState } from "react";
import { CodeBlock } from "../components/CodeBlock";
import { PageHero } from "../components/PageHero";
import {
  annotationExample,
  configExample,
  observabilityExample,
  programmaticExample,
  quickStart,
} from "../data/content";

const MODES = [
  {
    key: "yaml",
    label: "YAML 配置",
    file: "weaver.yml",
    intro: (
      <>
        用 <code className="inline">config=</code> 指定配置文件，<code className="inline">watch=true</code>{" "}
        开启热加载，<code className="inline">disabledPlugins</code> 按名关闭插件。
      </>
    ),
    code: configExample,
  },
  {
    key: "api",
    label: "编程式 API",
    file: "MyPlugin.java",
    intro: "在自己的 WeaverPlugin 里向注册表登记拦截器。",
    code: programmaticExample,
  },
  {
    key: "annotation",
    label: "注解",
    file: "MyInterceptor.java",
    intro: "用声明式注解描述拦截点，方法必须是 static。",
    code: annotationExample,
  },
];

const STEPS = [
  { n: "01", title: "构建", text: "mvn clean package，产物是 Java 8 字节码。" },
  { n: "02", title: "挂载", text: "-javaagent 指向 agent jar，无需配置文件。" },
  { n: "03", title: "观察", text: "插件自动拦截，指标与健康检查随即可用。" },
];

export function QuickStartPage() {
  const [mode, setMode] = useState(MODES[0]);

  return (
    <>
      <PageHero
        kicker="QUICK START"
        title="30 秒跑起来"
        lede="需要 Java 8 及以上与 Maven 3.6 及以上。构建产物是 Java 8 字节码，可以挂到 JDK 8、11、17、21 的进程上。"
      />

      <div className="steps" style={{ marginTop: 36 }}>
        {STEPS.map((step) => (
          <div key={step.n} className="step">
            <b>STEP {step.n}</b>
            <strong>{step.title}</strong>
            <p>{step.text}</p>
          </div>
        ))}
      </div>

      <h2 className="subhead">构建并挂载</h2>
      <CodeBlock code={quickStart} file="terminal" />
      <p className="prose">agent 启动后所有内置插件默认生效，不需要配置文件。</p>

      <h2 className="subhead">三种写法，同一套注册表</h2>
      <div className="mode-tabs" role="tablist">
        {MODES.map((item) => (
          <button
            key={item.key}
            role="tab"
            aria-selected={item.key === mode.key}
            onClick={() => setMode(item)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <p className="prose">{mode.intro}</p>
      <CodeBlock code={mode.code} file={mode.file} />

      <h2 className="subhead">指标与健康检查</h2>
      <CodeBlock code={observabilityExample} file="terminal" />
    </>
  );
}
