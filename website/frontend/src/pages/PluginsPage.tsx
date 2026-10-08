import { PageHero } from "../components/PageHero";
import { plugins } from "../data/content";

export function PluginsPage() {
  return (
    <>
      <PageHero
        kicker="PLUGINS"
        title="16 个开箱即用的插件"
        lede="插件通过 SPI 发现，带依赖解析、类加载器隔离、条件启停与热加载。用 disabledPlugins 按名称关闭不需要的插件。"
      />
      <div className="plugin-grid" style={{ marginTop: 36 }}>
        {plugins.map((plugin) => (
          <article key={plugin.key} className="plugin-card">
            <code>{plugin.name}</code>
            <div className="target">{plugin.target}</div>
            <div className="config">{plugin.config}</div>
          </article>
        ))}
      </div>
    </>
  );
}
