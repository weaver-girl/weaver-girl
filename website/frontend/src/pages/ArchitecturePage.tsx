import { Table, Typography } from "antd";
import type { TableProps } from "antd";
import { CodeBlock } from "../components/CodeBlock";
import { type ModuleRow, modules } from "../data/content";

const { Title, Paragraph } = Typography;

const columns: TableProps<ModuleRow>["columns"] = [
  { title: "模块", dataIndex: "name", key: "name", render: (name: string) => <code>{name}</code> },
  { title: "职责", dataIndex: "description", key: "description" },
];

const architecture = `+--------------------------------------------------+
|                  目标 JVM                          |
|  +----------------------------------------------+ |
|  |        Weaver-Girl Agent (premain)            | |
|  |   Transformer    PluginLoader     YAML 配置   | |
|  |        \\              |              /        | |
|  |         +------------- v -------------+        | |
|  |         |  DefaultInterceptorRegistry |        | |
|  |         +------------- | -------------+        | |
|  |         |     InterceptAdvice         |        | |
|  |         |  (ByteBuddy @Advice 内联)    |        | |
|  |         +-----------------------------+        | |
|  +----------------------------------------------+ |
+--------------------------------------------------+`;

export function ArchitecturePage() {
  return (
    <>
      <Title level={2}>架构</Title>
      <Paragraph>
        Agent 在目标 JVM 启动时通过 premain 挂入，由插件加载器经 SPI 发现插件并登记拦截器，
        ByteBuddy 在类加载时把 Advice 内联进目标方法。
      </Paragraph>
      <CodeBlock code={architecture} />

      <Title level={3} style={{ marginTop: 32 }}>
        模块
      </Title>
      <Table<ModuleRow> columns={columns} dataSource={modules} pagination={false} size="middle" />

      <Title level={3} style={{ marginTop: 32 }}>
        运行时要求
      </Title>
      <Paragraph>
        主代码以 <code>--release 8</code> 编译，保证产物只使用 JDK 8 的 API，可以运行在 Java 8
        及以上。构建本身需要 JDK 11 或更高版本。
      </Paragraph>
    </>
  );
}
