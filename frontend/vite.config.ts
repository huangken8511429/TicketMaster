import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: true,
  },
  build: {
    sourcemap: true,
    target: 'es2022',
    // Phase 7: split the largest dependency families into their own chunks so
    // first paint loads less JS. Each chunk is independently cacheable across
    // deploys when the dep version doesn't change.
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('framer-motion')) return 'framer';
          if (id.includes('@tanstack')) return 'tanstack';
          if (id.includes('react-router')) return 'router';
          if (id.includes('msw') || id.includes('@mswjs')) return 'msw';
          if (id.includes('react-dom') || id.includes('scheduler') || id.includes('/react/')) {
            return 'react';
          }
          return 'vendor';
        },
      },
    },
    chunkSizeWarningLimit: 350,
  },
});
