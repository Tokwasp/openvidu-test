import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 로컬 개발(npm run dev)에서 /api 를 백엔드로 프록시. 도커에선 nginx 가 담당.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
