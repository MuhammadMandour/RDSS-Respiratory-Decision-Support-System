import { expect, test } from '@playwright/test';
import { apiToken, bearer, login, patientId, trackConsoleErrors } from './support';

test.describe('patient portal', () => {
  test('sees only their own record and cannot reach staff pages', async ({ page, request }) => {
    const errors = trackConsoleErrors(page);
    await login(page, 'patient.jordan');

    const nav = page.getByRole('navigation');
    await expect(nav.getByRole('link', { name: 'My record' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Patients' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Reports' })).toHaveCount(0);

    await page.goto('/my-record?tab=respiratory');
    await expect(page.getByText('Respiratory history').first()).toBeVisible();

    // Direct URLs to staff pages and to another patient's chart are blocked in the UI…
    const samuel = await patientId(request, 'Okafor');
    for (const path of [`/patients/${samuel}`, '/reports', '/admin/users']) {
      await page.goto(path);
      await expect(page.getByText('You do not have access to this page.')).toBeVisible();
    }

    // …and, independently, by the API: another patient's record is reported as not found.
    const token = await apiToken(request, 'patient.jordan');
    expect((await request.get(`/api/v1/patients/${samuel}`, { headers: bearer(token) })).status()).toBe(404);
    expect((await request.get(`/api/v1/patients/${samuel}/observations`, { headers: bearer(token) })).status()).toBe(404);
    expect((await request.get('/api/v1/patients/me', { headers: bearer(token) })).status()).toBe(200);
    expect(errors.filter((e) => !/40[34]/.test(e))).toEqual([]);
  });

  test('requests an appointment and then cancels it', async ({ page, request }) => {
    await login(page, 'patient.jordan');
    await page.goto('/appointments');
    await page.getByRole('button', { name: 'Request appointment' }).click();
    const dialog = page.getByRole('dialog', { name: 'Request an appointment' });

    await dialog.getByLabel('Clinician').click();
    await page.getByRole('option', { name: /Hale/ }).click();

    // Find the first day at least 3 days ahead (beyond the cancellation cut-off) with a free slot.
    const slots = dialog.getByRole('listbox', { name: 'Available times' }).getByRole('option');
    let booked = false;
    for (let offset = 3; offset < 24 && !booked; offset++) {
      const d = new Date();
      d.setDate(d.getDate() + offset);
      const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      await dialog.getByLabel('Date').fill(iso);
      await expect(dialog.getByText(/No free slots on this date\.|Loading slots…/).or(slots.first())).toBeVisible();
      await expect(dialog.getByText('Loading slots…')).toHaveCount(0);
      if (await slots.count()) {
        await slots.last().click();
        booked = true;
      }
    }
    expect(booked, 'a free slot within three weeks').toBeTruthy();
    await dialog.getByLabel('Reason').fill('E2E test request - safe to cancel');
    await dialog.getByRole('button', { name: 'Request' }).click();
    await expect(dialog).toBeHidden();

    // Identify exactly the appointment this test created, then cancel it through the UI.
    const token = await apiToken(request, 'patient.jordan');
    const list = await (await request.get('/api/v1/appointments', { params: { status: 'REQUESTED', size: 100 }, headers: bearer(token) })).json();
    const mine = list.content.filter((a: { reason?: string }) => a.reason === 'E2E test request - safe to cancel');
    expect(mine.length).toBeGreaterThan(0);
    const id = mine[mine.length - 1].id as number;

    await page.getByRole('button', { name: `Actions for appointment ${id}` }).click();
    await page.getByRole('menuitem', { name: /Cancel/ }).click();
    await page.getByRole('dialog').getByLabel('Reason').fill('E2E clean-up');
    await page.getByRole('button', { name: 'Cancel appointment' }).click();
    await expect(page.getByRole('dialog')).toBeHidden();
    const after = await (await request.get(`/api/v1/appointments/${id}`, { headers: bearer(token) })).json();
    expect(after.status).toBe('CANCELLED');
  });

  test('views invoices from the billing page', async ({ page }) => {
    await login(page, 'patient.samuel');
    await page.goto('/billing');
    await expect(page.getByRole('table', { name: 'Invoices' }).or(page.getByText(/No invoices/))).toBeVisible();
  });
});
