import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// Isolated proxy used only by stream-render-smoke.mjs; production/dev config is unchanged.
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api/chat': {
        target: 'http://127.0.0.1:5189',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
