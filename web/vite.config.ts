import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// SPA 构建产物将来会被打进 jar 并挂载在任意路径下，因此必须使用相对 base。
export default defineConfig({
  base: "./",
  plugins: [react()],
  build: {
    outDir: "dist",
    target: "es2020",
  },
  server: {
    proxy: {
      "/api": {
        target: "http://127.0.0.1:3080",
        // 保留 Host 头原样透传（后端与 dsh 反代都依赖 Host）
        changeOrigin: false,
      },
      "/ws": {
        target: "http://127.0.0.1:3080",
        ws: true,
        changeOrigin: false,
      },
      // 正则 key（^ 开头）：只匹配 /i、/i/…，避免把 SPA 自身的 /instances 路由误代理
      "^/i($|/)": {
        target: "http://127.0.0.1:3080",
        ws: true,
        changeOrigin: false,
      },
    },
  },
});
