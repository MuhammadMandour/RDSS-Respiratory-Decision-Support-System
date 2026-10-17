import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { apiToken, bearer, login, patientId, trackConsoleErrors } from './support';

test.describe('clinician workflows', () => {
  test('runs and reviews a decision-support assessment', async ({ page, request }) => {
    const errors = trackConsoleErrors(page);
    const samuel = await patientId(request, 'Okafor');
    await login(page, 'dr.hale');
    await page.goto(`/patients/${samuel}`);
    await page.getByRole('link', { name: 'Decision support' }).click();
    await page.getByRole('button', { name: 'Run assessment' }).click();
    await expect(page.getByText('System-generated').first()).toBeVisible({ timeout: 20_000 });

    // Each alert must carry a disposition before the review can be completed.
    const groups = page.getByRole('group', { name: /Disposition for/ });
    const n = await groups.count();
    for (let i = 0; i < n; i++) await groups.nth(i).getByRole('button', { name: 'Accept' }).click();
    await page.getByRole('button', { name: n ? 'Complete review' : 'Acknowledge assessment' }).click();
    await expect(page.getByText(/Reviewed by/).first()).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('codes a diagnosis with the ICD-10-CM search', async ({ page, request }) => {
    const samuel = await patientId(request, 'Okafor');
    await login(page, 'dr.hale');
    await page.goto(`/patients/${samuel}?tab=diagnoses`);
    await page.getByRole('button', { name: 'Add diagnosis' }).click();
    await page.getByLabel('ICD-10-CM code').fill('J96');
    await page.getByRole('option', { name: /J96\.01/ }).click();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('cell', { name: 'J96.01' }).first()).toBeVisible();
  });

  test('cannot open a patient outside their care team', async ({ page, request }) => {
    const samuel = await patientId(request, 'Okafor');
    const token = await apiToken(request, 'dr.osei');
    expect((await request.get(`/api/v1/patients/${samuel}`, { headers: bearer(token) })).status()).toBe(403);
    await login(page, 'dr.osei');
    await page.goto(`/patients/${samuel}`);
    await expect(page.getByRole('alert').filter({ hasText: /access|permission|denied/i }).first()).toBeVisible();
  });

  test('sees care-team reports and exports diagnosis counts', async ({ page }) => {
    const errors = trackConsoleErrors(page);
    await login(page, 'dr.hale');
    await page.getByRole('navigation').getByRole('link', { name: 'Reports' }).click();
    await expect(page.getByText('Aggregates for patients on your care team')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Diagnosis distribution' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Billing' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Invoices/ })).toHaveCount(0);

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Diagnosis distribution \(ICD-10\)/ }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/^rdss-diagnoses-\d{4}-\d{2}-\d{2}-to-\d{4}-\d{2}-\d{2}\.csv$/);
    const csv = readFileSync((await file.path())!, 'utf8');
    expect(csv.replace(/^﻿/, '').split('\r\n')[0]).toBe('icd10_code,description,code_version,diagnosis_type,status,count,distinct_patients');
    expect(csv).not.toMatch(/MRN\d+/);
    expect(errors).toEqual([]);
  });
});
