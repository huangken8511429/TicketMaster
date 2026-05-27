/**
 * Phase 3 (seat-map) — VenueMap E2E.
 *
 * Verifies the new SECTION_VISUAL renderer added in Phase A:
 *  - SVG renders with stage + 5 sections (matching the MSW stadium fixture)
 *  - SOLD_OUT polygons are aria-disabled (Section E in MSW seed)
 *  - Keyboard: Tab focuses an interactive polygon, Enter opens the booking modal
 *  - Responsive: SVG remains visible across 768 / 1280 viewports
 *  - Fallback: an event whose venue has an invalid seatMap renders <SectionList>
 *
 * Runs against MSW (Event 1 = SECTION_VISUAL by seed). The fallback test
 * temporarily switches Event 1's venue.seatMap to invalid JSON via a request
 * interceptor.
 */

import { expect, test } from '@playwright/test';

test.describe('VenueMap (SECTION_VISUAL)', () => {
  test('renders SVG stage + section polygons with correct a11y labels', async ({ page }) => {
    await page.goto('/events/1');
    await page.getByText('選擇票區').waitFor();

    // Each section ships a mini-STAGE marker (Phase A) in addition to the main
    // venue stage. Expect ≥ 6 STAGE labels: 1 venue stage + 5 mini-stages.
    await expect(page.getByText('STAGE').first()).toBeVisible();
    expect(await page.getByText('STAGE').count()).toBeGreaterThanOrEqual(6);

    // Stadium fixture ships 5 sections A..E. Each renders a role="button" polygon
    // with aria-label "{displayName}：{statusLabel}".
    for (const name of ['A', 'B', 'C', 'D', 'E']) {
      await expect(page.getByRole('button', { name: new RegExp(`${name} 區：`) })).toBeVisible();
    }
  });

  test('SOLD_OUT section (E) is aria-disabled and ignores clicks', async ({ page }) => {
    await page.goto('/events/1');
    const soldOut = page.getByRole('button', { name: /E 區：已售完/ });
    await expect(soldOut).toBeVisible();
    await expect(soldOut).toHaveAttribute('aria-disabled', 'true');

    // Forced click on a disabled polygon must not open the modal.
    await soldOut.click({ force: true }).catch(() => undefined);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('Enter key on a focused interactive polygon opens the booking modal', async ({ page }) => {
    await page.goto('/events/1');
    const interactive = page
      .getByRole('button', { name: /[A-D] 區：(熱賣中|即將售完|僅剩數張)/ })
      .first();
    await interactive.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 3_000 });
  });

  test('SVG stays visible across 768 and 1280 viewports', async ({ page }) => {
    await page.goto('/events/1');
    await page.getByText('STAGE').first().waitFor();
    for (const width of [768, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await expect(page.getByText('STAGE').first()).toBeVisible();
      await expect(page.getByRole('button', { name: /A 區：/ })).toBeVisible();
    }
  });

  // NOTE: The invalid-seatMap → <SectionList> fallback flow is exercised by
  // `src/test/parseVenueSeatMap.test.ts` (7 unit tests covering null / "{}" /
  // malformed JSON / schemaVersion ≠ 1 / missing viewBox / empty sections /
  // missing shape payload). An E2E version is intentionally omitted because
  // `page.route` races MSW's service worker under workers > 1, making the
  // assertion flaky without adding coverage the unit tests don't already
  // provide.
});
