import { useMemo, useState } from "react";
import { Input, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import { PageHero } from "../components/PageHero";
import {
  allPlugins,
  pluginHighlights,
  runtimeCapabilities,
  type PluginRow,
} from "../data/content";

const COLUMNS: ColumnsType<PluginRow> = [
  {
    title: "插件",
    dataIndex: "name",
    width: 150,
    render: (name: string) => <code className="plugin-code">{name}</code>,
  },
  { title: "拦截目标", dataIndex: "target", width: 220 },
  {
    title: "常用配置",
    dataIndex: "config",
    render: (config: string) => <span className="plugin-config">{config}</span>,
  },
];

export function PluginsPage() {
  const [query, setQuery] = useState("");
  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    return keyword ? allPlugins.filter((name) => name.includes(keyword)) : allPlugins;
  }, [query]);

  return (
    <>
      <PageHero
        kicker="PLUGINS"
        title="126 个开箱即用的插件"
        lede="每个能力一个 jar，通过字节码匹配目标库，只在目标库存在时加载。用 disabledPlugins / enabledPlugins 按名称启停，配置目录支持热加载。"
      />

      <div className="page-body">
      <h2 className="subhead">完整清单</h2>
      <div className="plugin-toolbar">
        <Input.Search
          allowClear
          placeholder="搜索插件名，例如 kafka、dubbo、jdbc"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          style={{ maxWidth: 380 }}
        />
        <span className="plugin-count">
          {filtered.length} / {allPlugins.length}
        </span>
      </div>
      <div className="plugin-tag-grid">
        {filtered.map((name) => (
          <Tag key={name} className="plugin-tag">
            {name}
          </Tag>
        ))}
        {filtered.length === 0 && <p className="prose">没有匹配的插件，换个关键字试试。</p>}
      </div>

      <h2 className="subhead">常用插件与配置</h2>
      <Table
        rowKey="key"
        className="plugin-table"
        columns={COLUMNS}
        dataSource={pluginHighlights}
        pagination={false}
        size="middle"
        scroll={{ x: 640 }}
      />

      <h2 className="subhead">14 个可选运行时能力</h2>
      <p className="prose">
        这些 jar 属于 agent 自身而不是业务插件，只有配置了对应键才会启用；发行包里不用的能力可以直接删掉。
      </p>
      <div className="runtime-grid">
        {runtimeCapabilities.map((capability) => (
          <article key={capability.key} className="runtime-card">
            <code>{capability.name}</code>
            <p>{capability.description}</p>
          </article>
        ))}
      </div>

      <h2 className="subhead">自带插件之外</h2>
      <p className="prose">
        第三方插件只需要依赖 <code className="inline">weaver-girl-api</code>，实现 <code className="inline">WeaverPlugin</code>
        ，把 jar 放进 agent 旁边的 <code className="inline">plugins/</code> 目录即可被发现。你写的插件和内置插件走同一条
        拦截、熔断、采样与导出链路。
      </p>
      </div>
    </>
  );
}
