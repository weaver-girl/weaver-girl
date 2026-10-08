import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// BASE_PATH is "/" for local dev and "/<repo>/" when built for GitHub Pages
// (project sites are served from a subpath). Set by the Pages workflow.
export default defineConfig(({ mode }) => ({
  // Project sites on GitHub Pages are served from /<repo>/, so the workflow
  // builds with --mode pages. Local dev and preview stay at the domain root.
  base: mode === "pages" ? "/weaver-girl/" : "/",
  plugins: [react()],
}));
