import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { ImagingPanel } from './ImagingPanel';

afterEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
});

const study = (id: number, desc: string, mismatch = false) => ({
  id, patientId: 2, studyInstanceUid: `1.2.${id}`, studyDate: '2030-01-15', studyDescription: desc, modalities: 'CT',
  numberOfSeries: 1, numberOfInstances: 80, patientIdMismatch: mismatch, source: 'UPLOAD', linkedAt: '2030-01-15T10:00:00Z', findingCount: 2,
});

describe('ImagingPanel', () => {
  it('lists studies, flags ID mismatches and opens a comparison viewer session', async () => {
    signInAs('PATIENT', { patientId: 2 });
    let requested: unknown;
    server.use(
      http.get('*/api/v1/patients/2/imaging-studies', () => HttpResponse.json([study(1, 'CT CURRENT', true), study(2, 'CT PRIOR')])),
      http.post('*/api/v1/imaging/viewer-sessions', async ({ request }) => {
        requested = await request.json();
        return HttpResponse.json({ viewerUrl: '/ohif/viewer?StudyInstanceUIDs=1.2.1,1.2.2', expiresAt: '2030-01-15T10:30:00Z', studyInstanceUids: ['1.2.1', '1.2.2'], notice: '' });
      }),
    );
    const tab = { opener: {}, location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);

    renderWithProviders(<ImagingPanel patientId={2} canWrite={false} />);

    expect(await screen.findByText('CT CURRENT')).toBeInTheDocument();
    expect(screen.getByText('ID mismatch')).toBeInTheDocument();
    // Read-only for patients: no upload or ordering controls.
    expect(screen.queryByRole('button', { name: /Upload DICOM/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Imaging orders')).not.toBeInTheDocument();

    const compare = screen.getByRole('button', { name: /Compare selected/ });
    expect(compare).toBeDisabled();
    await userEvent.click(screen.getByLabelText('Select study 1 for comparison'));
    await userEvent.click(screen.getByLabelText('Select study 2 for comparison'));
    await userEvent.click(compare);

    await vi.waitFor(() => expect(tab.location.href).toBe('/ohif/viewer?StudyInstanceUIDs=1.2.1,1.2.2'));
    expect(requested).toEqual({ studyIds: [1, 2] });
    expect(tab.opener).toBeNull();
  });

  it('closes the pre-opened tab and shows the error when authorization fails', async () => {
    signInAs('CLINICIAN');
    server.use(
      http.get('*/api/v1/patients/2/imaging-studies', () => HttpResponse.json([study(1, 'CT CURRENT')])),
      http.get('*/api/v1/patients/2/imaging-orders', () => HttpResponse.json([])),
      http.post('*/api/v1/imaging/viewer-sessions', () => HttpResponse.json({ message: 'You do not have permission to perform this action' }, { status: 403 })),
    );
    const tab = { opener: {}, location: { href: '' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);

    renderWithProviders(<ImagingPanel patientId={2} canWrite />);
    await userEvent.click(await screen.findByRole('button', { name: 'View' }));

    expect(await screen.findByText('You do not have permission to perform this action')).toBeInTheDocument();
    expect(tab.close).toHaveBeenCalled();
    expect(tab.location.href).toBe('');
  });
});
