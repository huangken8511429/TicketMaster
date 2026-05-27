import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/globals.css';

async function bootstrap() {
  // Phase 7: lazy-load MSW only when explicitly enabled in dev. This keeps the
  // production bundle free of the ~245KB worker runtime — in prod the import
  // never even runs, so Rollup tree-shakes the module entirely.
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MSW === 'true') {
    const { enableMockServiceWorker } = await import('./mocks/browser');
    await enableMockServiceWorker();
  }

  const container = document.getElementById('root');
  if (!container) throw new Error('Root container #root not found');

  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void bootstrap();
