import { Steps, Tabs } from "antd";
import { CodeBlock } from "../components/CodeBlock";
import { PageHero } from "../components/PageHero";
import {
  annotationExample,
  configExample,
  containerInstall,
  observabilityExample,
  pluginDependencyExample,
  programmaticExample,
  quickStart,
  releaseInstall,
} from "../data/content";

const STEPS = [
  { title: "构建", description: "mvn clean package，产物是 Java 8 字节码" },
  { title: "挂载", description: "-javaagent 指向 agent jar，旁边保留 plugins/" },
  { title: "观察", description: "插件自动拦截，指标与健康检查随即可用" },
];

const INSTALL_TABS = [
  {
    key: "source",
    label: "源码构建",
    file: "terminal",
    code: quickStart,
    note: "当前版本从源码构建即可；target/plugins/ 与 agent jar 必须放在一起。",
  },
  {
    key: "release",
    label: "发行包",
    file: "terminal",
    code: releaseInstall,
    note: "正式发布后，GitHub Release 会提供 agent + plugins/ 的 dist.zip，避免只拷 jar 导致不拦截。",
  },
  {
    key: "container",
    label: "容器镜像",
    file: "terminal",
    code: containerInstall,
    note: "仓库自带 Dockerfile 与 Helm chart，agent、plugins/ 和配置一起进镜像。",
  },
];

const MODE_TABS = [
  {
    key: "yaml",
    label: "YAML 配置",
    file: "weaver.yml",
    code: configExample,
    note: (
      <>
        用 <code className="inline">config=</code> 指定配置文件，<code className="inline">watch=true</code>{" "}
        开启热加载，<code className="inline">disabledPlugins</code> 按名关闭插件。
      </>
    ),
  },
  {
    key: "api",
    label: "编程式 API",
    file: "MyPlugin.java",
    code: programmaticExample,
    note: "在自己的 WeaverPlugin 里向注册表登记拦截器。",
  },
  {
    key: "annotation",
    label: "注解",
    file: "MyInterceptor.java",
    code: annotationExample,
    note: "用声明式注解描述拦截点，方法必须是 static。",
  },
];

export function QuickStartPage() {
  return (
    <>
      <PageHero
        kicker="QUICK START"
        title="30 秒跑起来"
        lede="运行时需要 Java 8 及以上；构建建议使用 JDK 17，产物是 Java 8 字节码，可以挂到 JDK 8、11、17、21 的进程上。"
      />

      <div className="page-body">
      <Steps
        className="quick-steps"
        items={STEPS.map((step) => ({ title: step.title, description: step.description }))}
      />

      <h2 className="subhead">安装与挂载</h2>
      <Tabs
        className="site-tabs"
        items={INSTALL_TABS.map((tab) => ({
          key: tab.key,
          label: tab.label,
          children: (
            <>
              <p className="prose">{tab.note}</p>
              <CodeBlock code={tab.code} file={tab.file} />
            </>
          ),
        }))}
      />

      <h2 className="subhead">三种写法，同一套注册表</h2>
      <Tabs
        className="site-tabs"
        items={MODE_TABS.map((tab) => ({
          key: tab.key,
          label: tab.label,
          children: (
            <>
              <p className="prose">{tab.note}</p>
              <CodeBlock code={tab.code} file={tab.file} />
            </>
          ),
        }))}
      />

      <h2 className="subhead">写自己的插件</h2>
      <p className="prose">
        第三方插件只依赖 <code className="inline">weaver-girl-api</code>。实现 <code className="inline">WeaverPlugin</code>
        后把 jar 放进 <code className="inline">plugins/</code>，它会和内置插件一起被加载。发布到 Maven Central 之前，先从源码
        <code className="inline">mvn install</code> 安装 SDK。
      </p>
      <CodeBlock code={pluginDependencyExample} file="pom.xml" />

      <h2 className="subhead">指标与健康检查</h2>
      <p className="prose">
        metrics、health、OTLP 等都是运行时能力 jar，配置对应端口后才启用；Prometheus 抓取 <code className="inline">/metrics</code>
        ，Kubernetes 探针直接打 <code className="inline">/health</code> 与 <code className="inline">/ready</code>。
      </p>
      <CodeBlock code={observabilityExample} file="terminal" />
      </div>
    </>
  );
}
