import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { PatientDirectoryPage } from './PatientDirectoryPage';

afterEach(() => sessionStorage.clear());

const page = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

describe('PatientDirectoryPage', () => {
  it('lists patients and sends the debounced search with the bearer token', async () => {
    signInAs('CLINICIAN');
    const queries: (string | null)[] = [];
    let auth: string | null = null;
    server.use(
      http.get('*/api/v1/patients', ({ request }) => {
        const url = new URL(request.url);
        queries.push(url.searchParams.get('q'));
        auth = request.headers.get('authorization');
        return HttpResponse.json(page([
          { id: 1, mrn: 'MRN100001', firstName: 'Jordan', lastName: 'Reyes', dateOfBirth: '1994-03-14', age: 32, sex: 'FEMALE', smokingStatus: 'NEVER', active: true },
        ]));
      }),
      http.get('*/api/v1/clinicians', () => HttpResponse.json([])),
    );

    renderWithProviders(<PatientDirectoryPage />);

    expect(await screen.findByText('Jordan Reyes')).toBeInTheDocument();
    expect(screen.getByText('MRN100001')).toBeInTheDocument();
    expect(auth).toBe('Bearer test.jwt.token');

    await userEvent.type(screen.getByLabelText('Search patients'), 'reyes');
    await screen.findByText('Jordan Reyes');
    await vi.waitFor(() => expect(queries).toContain('reyes'));
  });

  it('shows an empty state', async () => {
    signInAs('ADMIN');
    server.use(http.get('*/api/v1/patients', () => HttpResponse.json(page([]))));
    renderWithProviders(<PatientDirectoryPage />);
    expect(await screen.findByText('No patients found')).toBeInTheDocument();
  });
});
