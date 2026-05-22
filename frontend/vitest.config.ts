import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Phase 7: Playwright specs live in ./e2e and use the @playwright/test
    // runner. Vitest must skip them so `npm run test` only exercises the
    // jsdom integration suite.
    exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**'],
  },
});
