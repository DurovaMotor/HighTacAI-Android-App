import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

function manualChunks(id: string) {
  const normalizedId = id.replace(/\\/g, '/');

  if (normalizedId.includes('/node_modules/@ant-design/v5-patch-for-react-19/')) {
    return 'react19-patch';
  }
  if (/\/node_modules\/(?:react|react-dom|scheduler|react-router|react-router-dom|@remix-run)\//.test(normalizedId)) {
    return 'react-vendor';
  }
  if (
    normalizedId.includes('/node_modules/@ant-design/')
    || /\/node_modules\/(?:rc-util|rc-motion)\//.test(normalizedId)
    || /\/node_modules\/@rc-component\/(?:context|portal)\//.test(normalizedId)
  ) {
    return 'antd-vendor';
  }
  if (normalizedId.includes('/node_modules/@tanstack/')) {
    return 'query-vendor';
  }

  return undefined;
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8088';

  return {
    plugins: [react()],
    define: {
      'import.meta.env.VITE_MOCK_API': JSON.stringify(mode === 'mock' ? 'true' : env.VITE_MOCK_API || 'false'),
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target, changeOrigin: true, ws: true },
        '/ws': { target, changeOrigin: true, ws: true },
      },
    },
    preview: { port: 4173 },
    build: {
      outDir: 'dist',
      sourcemap: true,
      rollupOptions: {
        output: { manualChunks, onlyExplicitManualChunks: true },
      },
    },
  };
});
