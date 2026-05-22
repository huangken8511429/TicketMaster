/**
 * Phase 7 — Visual smoke screenshots.
 *
 * Captures one full-page screenshot for each of the four MVP screens against
 * the MSW-backed dev server. Used as the verify-stage's visual sanity check;
 * not asserting visual regression here — these are reference shots.
 *
 *   01-events-list.png   /
 *   02-event-detail.png  /events/1
 *   03-queue.png         /queue/<live-id>     (captured immediately after POST)
 *   04-confirm.png       /confirm/<live-id>   (after BOOKED resolution)
 *
 * Run with:
 *   npx playwright test e2e/screenshots.spec.ts --project=chromium
 */

import { test } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SCREENSHOTS_DIR = path.resolve(__dirname, '../screenshots');

function file(name: string): string {
  return path.join(SCREENSHOTS_DIR, name);
}

test.describe('Phase 7 screenshots', () => {
  test('01 — Events list', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('heading', { level: 1 }).waitFor();
    await page.waitForTimeout(500);
    await page.screenshot({ path: file('01-events-list.png'), fullPage: true });
  });

  test('02 — Event detail', async ({ page }) => {
    await page.goto('/events/1');
    await page.getByText('選擇票區').waitFor();
    await page.waitForTimeout(800);
    await page.screenshot({ path: file('02-event-detail.png'), fullPage: true });
  });

  test('03 — Queue overlay', async ({ page }) => {
    // Drive the booking start so a real bookingId is in the URL.
    await page.goto('/events/1');
    await page
      .getByRole('button', { name: /[A-E] 區：(熱賣中|即將售完|僅剩數張)/ })
      .first()
      .click();
    await page.getByRole('button', { name: '確認搶票' }).click();
    await page.waitForURL(/\/queue\//, { timeout: 10_000 });
    // Give the overlay a moment to settle visually.
    await page.waitForTimeout(700);
    await page.screenshot({ path: file('03-queue.png'), fullPage: true });
  });

  test('04 — Confirm hold', async ({ page }) => {
    await page.goto('/events/1');
    await page
      .getByRole('button', { name: /[A-E] 區：(熱賣中|即將售完|僅剩數張)/ })
      .first()
      .click();
    await page.getByRole('button', { name: '確認搶票' }).click();
    await page.waitForURL(/\/queue\//, { timeout: 10_000 });

    const arrived = await Promise.race([
      page.waitForURL(/\/confirm\//, { timeout: 30_000 }).then(() => 'confirm' as const),
      page
        .getByRole('button', { name: '回活動列表' })
        .waitFor({ state: 'visible', timeout: 30_000 })
        .then(() => 'failed' as const),
    ]).catch(() => 'timeout' as const);

    if (arrived !== 'confirm') {
      test.skip(true, 'Booking rolled REJECTED — rerun to capture the success screen.');
      return;
    }
    await page.getByRole('heading', { name: '已為您保留座位' }).waitFor();
    await page.waitForTimeout(500);
    await page.screenshot({ path: file('04-confirm.png'), fullPage: true });
  });
});
