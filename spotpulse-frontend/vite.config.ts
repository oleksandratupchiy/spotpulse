import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy: { '/api': { target: env.API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true } } },
    test: { environment: 'jsdom', setupFiles: './src/test/setup.ts', globals: true, maxWorkers: 2, testTimeout: 20_000 },
  };
});
