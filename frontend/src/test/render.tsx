import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AppProviders, createQueryClient } from '../app/providers';
import { AuthProvider } from '../auth/AuthContext';
import type { AuthResponse, Role } from '../auth/api';

export function renderWithProviders(
  ui: ReactElement,
  { route = '/', path = '*', extraRoutes }: { route?: string; path?: string; extraRoutes?: ReactElement } = {},
) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false, staleTime: 0, refetchInterval: false } });
  return render(
    <AppProviders client={client}>
      <AuthProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={ui} />
            {extraRoutes}
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </AppProviders>,
  );
}

/** Seeds a signed-in session the same way AuthProvider persists it. */
export function signInAs(role: Role, overrides: Partial<AuthResponse['user']> = {}) {
  const session = {
    token: 'test.jwt.token',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    user: { id: 7, username: `${role.toLowerCase()}1`, fullName: `Test ${role}`, email: 't@test', role, ...overrides },
  };
  sessionStorage.setItem('rdss.session', JSON.stringify(session));
  return session;
}
