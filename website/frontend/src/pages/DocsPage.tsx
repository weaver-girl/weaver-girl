import { PageHero } from "../components/PageHero";
import { docLinks } from "../data/content";

export function DocsPage() {
  return (
    <>
      <PageHero
        kicker="DOCS"
        title="文档都在仓库里"
        lede="完整文档随源码一起维护在 docs 目录，保证和代码同一个版本。这里是入口。"
      />
      <div className="doc-grid" style={{ marginTop: 36 }}>
        {docLinks.map((doc, i) => (
          <a key={doc.href} className="doc-card" href={doc.href} target="_blank" rel="noreferrer">
            <span className="doc-kicker">0{i + 1} · 仓库文档</span>
            <h3>{doc.title}</h3>
            <p>{doc.description}</p>
          </a>
        ))}
      </div>
    </>
  );
}
