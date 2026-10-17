import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { LoginPage } from './LoginPage';
import { RequireAuth } from './RequireAuth';
import { navItems } from '../app/navigation';

afterEach(() => sessionStorage.clear());

describe('LoginPage', () => {
  it('shows the generic backend error on failed login', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () =>
        HttpResponse.json({ status: 401, message: 'Invalid username or password' }, { status: 401 })),
    );
    renderWithProviders(<LoginPage />, { route: '/login', path: '/login' });

    await userEvent.type(screen.getByLabelText(/username/i), 'someone');
    await userEvent.type(screen.getByLabelText(/password/i), 'bad-password-1');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument();
    expect(sessionStorage.getItem('rdss.session')).toBeNull();
  });

  it('stores the session and navigates on success', async () => {
    let body: unknown;
    server.use(
      http.post('*/api/v1/auth/login', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          accessToken: 'a.b.c', tokenType: 'Bearer', expiresAt: new Date(Date.now() + 60_000).toISOString(),
          user: { id: 1, username: 'dr.hale', fullName: 'Dr. Hale', email: 'h@x', role: 'CLINICIAN' },
        });
      }),
    );
    renderWithProviders(<LoginPage />, {
      route: '/login', path: '/login', extraRoutes: <Route path="/" element={<p>Home screen</p>} />,
    });

    await userEvent.type(screen.getByLabelText(/username/i), ' dr.hale ');
    await userEvent.type(screen.getByLabelText(/password/i), 'Demo-Pass-2026');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Home screen')).toBeInTheDocument();
    expect(body).toEqual({ username: 'dr.hale', password: 'Demo-Pass-2026' });
    expect(JSON.parse(sessionStorage.getItem('rdss.session')!).user.role).toBe('CLINICIAN');
  });
});

describe('RequireAuth', () => {
  it('redirects anonymous users to the login page', async () => {
    renderWithProviders(<RequireAuth><p>Secret</p></RequireAuth>, {
      route: '/patients', path: '/patients', extraRoutes: <Route path="/login" element={<p>Login screen</p>} />,
    });
    expect(await screen.findByText('Login screen')).toBeInTheDocument();
    expect(screen.queryByText('Secret')).not.toBeInTheDocument();
  });

  it('blocks roles that are not allowed', async () => {
    signInAs('PATIENT');
    renderWithProviders(<RequireAuth roles={['ADMIN']}><p>Admin only</p></RequireAuth>);
    await waitFor(() => expect(screen.getByText('You do not have access to this page.')).toBeInTheDocument());
    expect(screen.queryByText('Admin only')).not.toBeInTheDocument();
  });
});

describe('navigation', () => {
  it('is role-aware', () => {
    expect(navItems('PATIENT').map((i) => i.label)).toEqual(['Dashboard', 'My record', 'Appointments', 'Billing']);
    expect(navItems('CLINICIAN').map((i) => i.label)).not.toContain('Users');
    expect(navItems('CLINICIAN').map((i) => i.label)).toContain('Reports');
    expect(navItems('ADMIN').map((i) => i.label)).toContain('Audit log');
    expect(navItems(undefined)).toEqual([]);
  });
});
