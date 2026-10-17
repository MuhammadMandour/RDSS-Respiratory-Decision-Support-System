import { expect, test } from '@playwright/test';
import { login } from './support';

test.describe('authentication', () => {
  test('rejects bad credentials with a generic message', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Username').fill('dr.hale');
    await page.getByLabel('Password').fill('definitely-not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('Invalid username or password')).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test('redirects anonymous users to the login page', async ({ page }) => {
    await page.goto('/patients');
    await expect(page).toHaveURL(/\/login/);
  });

  test('the API refuses requests without a token', async ({ request }) => {
    expect((await request.get('/api/v1/patients')).status()).toBe(401);
    expect((await request.get('/api/v1/reports/summary')).status()).toBe(401);
  });

  test('signing out clears the session', async ({ page }) => {
    await login(page, 'dr.hale');
    await page.getByRole('button', { name: 'Account menu' }).click();
    await page.getByRole('menuitem', { name: /sign out/i }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto('/patients');
    await expect(page).toHaveURL(/\/login/);
  });
});
