import { Layout, Menu, Typography } from "antd";
import { GithubOutlined } from "@ant-design/icons";
import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { REPO_URL } from "./data/content";
import { ArchitecturePage } from "./pages/ArchitecturePage";
import { DocsPage } from "./pages/DocsPage";
import { HomePage } from "./pages/HomePage";
import { PluginsPage } from "./pages/PluginsPage";
import { QuickStartPage } from "./pages/QuickStartPage";

const { Header, Content, Footer } = Layout;

const NAV = [
  { key: "/", label: "首页" },
  { key: "/quick-start", label: "快速开始" },
  { key: "/plugins", label: "插件" },
  { key: "/architecture", label: "架构" },
  { key: "/docs", label: "文档" },
];

export default function App() {
  const { pathname } = useLocation();
  const selected = NAV.some((item) => item.key === pathname) ? pathname : "/";

  return (
    <Layout style={{ minHeight: "100vh", background: "transparent" }}>
      <Header
        style={{
          position: "sticky",
          top: 0,
          zIndex: 10,
          display: "flex",
          alignItems: "center",
          gap: 24,
          background: "#0f172a",
        }}
      >
        <Link to="/" style={{ color: "#fff", fontWeight: 700, fontSize: 18, whiteSpace: "nowrap" }}>
          Weaver Girl
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[selected]}
          style={{ flex: 1, minWidth: 0, background: "transparent" }}
          items={NAV.map((item) => ({
            key: item.key,
            label: <Link to={item.key}>{item.label}</Link>,
          }))}
        />
        <a href={REPO_URL} target="_blank" rel="noreferrer" style={{ color: "#fff" }}>
          <GithubOutlined style={{ fontSize: 18 }} />
        </a>
      </Header>
      <Content>
        <div className="site-content">
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/quick-start" element={<QuickStartPage />} />
            <Route path="/plugins" element={<PluginsPage />} />
            <Route path="/architecture" element={<ArchitecturePage />} />
            <Route path="/docs" element={<DocsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </Content>
      <Footer style={{ textAlign: "center", background: "transparent" }}>
        <Typography.Text type="secondary">
          Weaver Girl · MIT License ·{" "}
          <a href={REPO_URL} target="_blank" rel="noreferrer">
            cc11001100/weaver-girl
          </a>
        </Typography.Text>
      </Footer>
    </Layout>
  );
}
