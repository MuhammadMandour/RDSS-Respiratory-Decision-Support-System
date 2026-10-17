import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));

/** Demo password: E2E_PASSWORD, else DEMO_USER_PASSWORD from the repository's .env, else the dev default. */
export const PASSWORD: string = (() => {
  if (process.env.E2E_PASSWORD) return process.env.E2E_PASSWORD;
  const env = resolve(here, '..', '..', '.env');
  if (existsSync(env)) {
    const line = readFileSync(env, 'utf8').split(/\r?\n/).find((l) => l.startsWith('DEMO_USER_PASSWORD='));
    const value = line?.slice('DEMO_USER_PASSWORD='.length).trim();
    if (value) return value;
  }
  return 'Demo-Pass-2026';
})();

export type DemoUser = 'admin' | 'dr.hale' | 'dr.osei' | 'patient.jordan' | 'patient.samuel';

export async function login(page: Page, username: DemoUser): Promise<void> {
  await page.goto('/login');
  await page.evaluate(() => sessionStorage.clear());
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Welcome,')).toBeVisible({ timeout: 15_000 });
}

export async function apiToken(request: APIRequestContext, username: DemoUser): Promise<string> {
  const res = await request.post('/api/v1/auth/login', { data: { username, password: PASSWORD } });
  expect(res.ok(), `login ${username}`).toBeTruthy();
  return (await res.json()).accessToken as string;
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

/** Looks up a demo patient's id by name (as administrator), so tests do not depend on seed order. */
export async function patientId(request: APIRequestContext, lastName: string): Promise<number> {
  const token = await apiToken(request, 'admin');
  const res = await request.get('/api/v1/patients', { params: { q: lastName }, headers: bearer(token) });
  expect(res.ok()).toBeTruthy();
  const page = await res.json();
  const match = page.content.find((p: { lastName?: string; fullName?: string; name?: string }) =>
    [p.lastName, p.fullName, p.name].some((v) => v?.includes(lastName)));
  expect(match, `demo patient ${lastName}`).toBeTruthy();
  return match.id as number;
}

/** Collects browser console errors so a test can assert the page ran cleanly. */
export function trackConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}
