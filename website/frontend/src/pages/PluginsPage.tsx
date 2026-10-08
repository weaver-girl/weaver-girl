import { Table, Typography } from "antd";
import type { TableProps } from "antd";
import { type PluginRow, plugins } from "../data/content";

const { Title, Paragraph } = Typography;

const columns: TableProps<PluginRow>["columns"] = [
  { title: "插件", dataIndex: "name", key: "name", render: (name: string) => <code>{name}</code> },
  { title: "拦截目标", dataIndex: "target", key: "target" },
  { title: "关键配置", dataIndex: "config", key: "config" },
];

export function PluginsPage() {
  return (
    <>
      <Title level={2}>内置插件</Title>
      <Paragraph>
        插件通过 SPI 发现，带依赖解析、类加载器隔离、条件启停与热加载。用{" "}
        <code>disabledPlugins</code> 按名称关闭不需要的插件。
      </Paragraph>
      <Table<PluginRow>
        columns={columns}
        dataSource={plugins}
        pagination={false}
        size="middle"
      />
    </>
  );
}
