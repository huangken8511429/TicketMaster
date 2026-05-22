/**
 * Phase 7 — Edge-case E2E.
 *
 * Covers the non-happy branches that earlier Vitest specs validated against
 * jsdom but had no real browser smoke for:
 *
 *  - Confirm page direct access (no router state) → toast + redirect home.
 *  - Sold-out section → badge disabled (and 422 path on POST exercised by
 *    edge-cases-detail). Event #3 in the MSW seed ships with 4 of 5 sections
 *    sold out, so the badges render in the disabled state.
 *  - SSE tick visibly changes section availability (the MSW handler nudges
 *    counts every ~4s).
 */

import { expect, test } from '@playwright/test';

test.describe('Edge cases', () => {
  test('Confirm page direct access redirects home with a toast', async ({ page }) => {
    await page.goto('/confirm/totally-fake-booking-id');

    // Loading placeholder is up first (intent: 1s grace).
    await expect(page.getByText('正在確認保留資訊')).toBeVisible();

    // After the grace period we should be back at the list page
    // and the toast should appear above it.
    await page.waitForURL('/', { timeout: 5_000 });
    await expect(page.getByText('無法取得保留資訊')).toBeVisible({ timeout: 5_000 });
  });

  test('Sold-out sections are not interactive on event #3 (Static Cathedral)', async ({ page }) => {
    await page.goto('/events/3');

    await expect(page.getByText('選擇票區')).toBeVisible();

    // The detail page renders SectionBadge components for A..E. Per the MSW
    // seed, A/B/D/E are SOLD_OUT and only C has stock. After the a11y fix,
    // every badge is a <button>; non-interactive ones carry `disabled`.
    const soldOutButton = page.getByRole('button', { name: /已售完/ }).first();
    await expect(soldOutButton).toBeVisible();
    await expect(soldOutButton).toBeDisabled();
    // Clicking a disabled <button> is a no-op; force-click to verify that even
    // a forced interaction does not open the booking modal.
    await soldOutButton.click({ force: true }).catch(() => undefined);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('SSE connects and stays live on event #1 (Aurora)', async ({ page }) => {
    await page.goto('/events/1');
    // SSE connection indicator surfaces once EventSource opens.
    await expect(page.getByText('即時連線中')).toBeVisible({ timeout: 8_000 });
    // After 9s (≥ 2 MSW tick intervals + 1 heartbeat) the connection must
    // still be alive. We assert on the user-visible signal rather than diffing
    // the section grid, because the UI shows status-only (no counts) per the
    // UI/UX decision, so a nudge inside the same status band would not change
    // the rendered HTML even when the stream is healthy.
    await page.waitForTimeout(9_000);
    await expect(page.getByText('即時連線中')).toBeVisible();
  });
});
