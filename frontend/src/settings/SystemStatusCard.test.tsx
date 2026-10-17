import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { server } from '../test/server';
import { renderWithProviders } from '../test/render';
import { SystemStatusCard } from './SystemStatusCard';

describe('SystemStatusCard', () => {
  it('renders dependency status from the typed API', async () => {
    server.use(
      http.get('*/api/v1/system/status', () =>
        HttpResponse.json({
          application: 'RDSS',
          version: '0.1.0',
          status: 'DEGRADED',
          checkedAt: '2026-01-01T10:00:00Z',
          dependencies: [
            { name: 'database', status: 'UP', detail: 'schema v1' },
            { name: 'imaging', status: 'DOWN', detail: 'unreachable' },
          ],
        }),
      ),
    );

    renderWithProviders(<SystemStatusCard />);

    expect(await screen.findByText('schema v1')).toBeInTheDocument();
    expect(screen.getByText('DEGRADED')).toBeInTheDocument();
    expect(screen.getByText('unreachable')).toBeInTheDocument();
  });

  it('shows the backend error message when the request fails', async () => {
    server.use(
      http.get('*/api/v1/system/status', () =>
        HttpResponse.json(
          { status: 503, error: 'Service Unavailable', message: 'Maintenance in progress', path: '/api/v1/system/status' },
          { status: 503 },
        ),
      ),
    );

    renderWithProviders(<SystemStatusCard />);

    expect(await screen.findByText('Maintenance in progress')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
