import { useEffect, useState } from "react";
import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { REPO_URL } from "./data/content";
import { ArchitecturePage } from "./pages/ArchitecturePage";
import { DocsPage } from "./pages/DocsPage";
import { HomePage } from "./pages/HomePage";
import { PluginsPage } from "./pages/PluginsPage";
import { QuickStartPage } from "./pages/QuickStartPage";

const NAV = [
  { key: "/", label: "首页" },
  { key: "/quick-start", label: "快速开始" },
  { key: "/plugins", label: "插件" },
  { key: "/architecture", label: "架构" },
  { key: "/docs", label: "文档" },
];

export default function App() {
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const current = NAV.some((item) => item.key === pathname) ? pathname : "/";

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 4);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <>
      <header className={scrolled ? "nav scrolled" : "nav"}>
        <Link to="/" className="brand" onClick={() => setOpen(false)}>
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M8 1 L15 8 L8 15 L1 8 Z" fill="#1d1d1f" />
          </svg>
          Weaver Girl
        </Link>
        <nav className={open ? "nav-links open" : "nav-links"}>
          {NAV.map((item) => (
            <Link
              key={item.key}
              to={item.key}
              className={item.key === current ? "active" : undefined}
              onClick={() => setOpen(false)}
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <a className="nav-github" href={REPO_URL} target="_blank" rel="noreferrer">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path d="M8 0C3.58 0 0 3.58 0 8a8 8 0 0 0 5.47 7.59c.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.6 7.6 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8 8 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
          </svg>
          <span>GitHub</span>
        </a>
        <button className="nav-toggle" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          菜单
        </button>
      </header>

      <main className="page">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/quick-start" element={<QuickStartPage />} />
          <Route path="/plugins" element={<PluginsPage />} />
          <Route path="/architecture" element={<ArchitecturePage />} />
          <Route path="/docs" element={<DocsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      <footer className="site-footer">
        <span>Weaver Girl · MIT License</span>
        <span>
          源码与文档维护于{" "}
          <a href={REPO_URL} target="_blank" rel="noreferrer">
            cc11001100/weaver-girl
          </a>
        </span>
      </footer>
    </>
  );
}
