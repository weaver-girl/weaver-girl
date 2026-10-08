import { Tabs, Typography } from "antd";
import { CodeBlock } from "../components/CodeBlock";
import {
  annotationExample,
  configExample,
  observabilityExample,
  programmaticExample,
  quickStart,
} from "../data/content";

const { Title, Paragraph } = Typography;

export function QuickStartPage() {
  return (
    <>
      <Title level={2}>快速开始</Title>
      <Paragraph>
        需要 Java 8 及以上与 Maven 3.6 及以上。构建产物是 Java 8 字节码，可以挂到 JDK 8、11、17、21 的进程上。
      </Paragraph>

      <Title level={3}>构建并挂载</Title>
      <CodeBlock code={quickStart} />
      <Paragraph style={{ marginTop: 12 }}>
        agent 启动后所有内置插件默认生效，不需要配置文件。
      </Paragraph>

      <Title level={3}>三种写法</Title>
      <Tabs
        items={[
          {
            key: "yaml",
            label: "YAML 配置",
            children: (
              <>
                <Paragraph>
                  用 <Text code>config=</Text> 指定配置文件，<Text code>watch=true</Text> 开启热加载，
                  <Text code>disabledPlugins</Text> 按名关闭插件。
                </Paragraph>
                <CodeBlock code={configExample} />
              </>
            ),
          },
          {
            key: "api",
            label: "编程式 API",
            children: (
              <>
                <Paragraph>在自己的 WeaverPlugin 里向注册表登记拦截器。</Paragraph>
                <CodeBlock code={programmaticExample} />
              </>
            ),
          },
          {
            key: "annotation",
            label: "注解",
            children: (
              <>
                <Paragraph>用声明式注解描述拦截点，方法必须是 static。</Paragraph>
                <CodeBlock code={annotationExample} />
              </>
            ),
          },
        ]}
      />

      <Title level={3} style={{ marginTop: 32 }}>
        指标与健康检查
      </Title>
      <CodeBlock code={observabilityExample} />
    </>
  );
}

const { Text } = Typography;
