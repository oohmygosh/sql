import { defineConfig } from 'vite';

const target = process.env.QUERY_API_TARGET || 'http://127.0.0.1:8080';
const proxy = { '/search': target, '/ui': target };

export default defineConfig({
  base: './',
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5174, strictPort: true, proxy },
});
