import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { login, trackConsoleErrors } from './support';

test.describe('administrator workflows', () => {
  test('organisation reports include finance and integration, and exports are audited', async ({ page }) => {
    const errors = trackConsoleErrors(page);
    await login(page, 'admin');
    await page.goto('/reports');
    await expect(page.getByText('Organisation-wide aggregates')).toBeVisible();
    await page.getByRole('button', { name: '12 months' }).click();
    await expect(page.getByRole('region', { name: 'Billing' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Integration and security' })).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Invoices \(no patient names\)/ }).click();
    const csv = readFileSync((await (await download).path())!, 'utf8');
    expect(csv).toContain('invoice_number,status,issue_date');

    await page.goto('/admin/audit');
    await expect(page.getByText('REPORT_EXPORT').first()).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('HL7 message log and system status are available', async ({ page }) => {
    await login(page, 'admin');
    await page.goto('/admin/hl7');
    await expect(page.getByRole('heading', { name: /HL7/ }).first()).toBeVisible();
    await page.goto('/');
    await expect(page.getByText(/Database|System status/i).first()).toBeVisible();
  });
});
