import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);

export async function enableMockServiceWorker() {
  if (!import.meta.env.DEV) {
    // Production never starts MSW.
    return;
  }
  if (import.meta.env.VITE_USE_MSW !== 'true') {
    return;
  }
  await worker.start({
    onUnhandledRequest: 'bypass',
    serviceWorker: { url: '/mockServiceWorker.js' },
  });
  // eslint-disable-next-line no-console
  console.log('[MSW] Mocking enabled');
}
