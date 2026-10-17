import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { ReportsPage } from './ReportsPage';
import type { ReportSummary } from './api';

afterEach(() => sessionStorage.clear());

const clinicianSummary: ReportSummary = {
  scope: 'CARE_TEAM', from: '2026-09-18', to: '2026-10-17', generatedAt: '2026-10-17T08:00:00Z',
  patients: { total: 2, active: 2, newInPeriod: 0, withRespiratoryHistory: 2,
    conditions: [{ key: 'COPD', label: 'COPD', count: 1 }, { key: 'ASTHMA', label: 'Asthma', count: 1 }],
    smoking: [{ key: 'FORMER', label: 'FORMER', count: 2 }] },
  appointments: { total: 4, byStatus: [{ key: 'COMPLETED', label: 'COMPLETED', count: 3 }, { key: 'NO_SHOW', label: 'NO_SHOW', count: 1 }],
    completionRate: 0.75, noShowRate: 0.25, perDay: [{ date: '2026-10-01', count: 2 }] },
  topDiagnoses: [{ key: 'J44.1', label: 'COPD with (acute) exacerbation', count: 3 }, { key: 'J45.909', label: 'Unspecified asthma, uncomplicated', count: 1 }],
  imaging: { studies: 2, byModality: [{ key: 'CT', label: 'CT', count: 2 }], findingsByType: [], pendingOrders: 1 },
  cdss: { assessments: 5, reviewed: 4, pendingReview: 1, byHighestSeverity: [{ key: 'HIGH', label: 'HIGH', count: 2 }],
    alertsByRule: [{ key: 'NEWS2', label: 'NEWS2', count: 5 }], dismissedAlerts: 1 },
  notes: { scope: 'Figures cover patients on your care team only.' },
};

describe('ReportsPage', () => {
  it('shows care-team aggregates without finance and filters the diagnosis table', async () => {
    signInAs('CLINICIAN');
    const seen: URLSearchParams[] = [];
    server.use(
      http.get('*/api/v1/reports/summary', ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json(clinicianSummary);
      }),
      http.get('*/api/v1/reports/exports', () => HttpResponse.json([{ slug: 'diagnoses', adminOnly: false }])),
    );
    renderWithProviders(<ReportsPage />);

    expect(await screen.findByText('Aggregates for patients on your care team')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-Appointments')).toHaveTextContent('4');
    expect(screen.getByText(/Completion 75%/)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Billing' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Integration and security' })).not.toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /Diagnosis distribution/ })).toBeInTheDocument();

    const dx = screen.getByRole('region', { name: 'Diagnosis distribution' });
    await userEvent.type(within(dx).getByLabelText('Filter ICD-10'), 'asthma');
    expect(within(dx).queryByText('J44.1')).not.toBeInTheDocument();
    expect(within(dx).getByText('J45.909')).toBeInTheDocument();

    // Default period is the last 30 days; a preset change re-queries.
    expect(seen[0]?.get('from')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: '7 days' }));
    await waitFor(() => expect(seen.length).toBeGreaterThan(1));
    const last = seen[seen.length - 1]!;
    const days = (Date.parse(last.get('to')!) - Date.parse(last.get('from')!)) / 86_400_000;
    expect(days).toBe(6);
  });

  it('shows finance and integration sections for administrators, flagging simulated payments', async () => {
    signInAs('ADMIN');
    server.use(
      http.get('*/api/v1/reports/summary', () => HttpResponse.json({
        ...clinicianSummary, scope: 'ORGANIZATION',
        billing: { currency: 'USD', invoiced: 229.95, collected: 229.95, outstanding: 0, overdueInvoices: 0,
          byStatus: [{ key: 'PAID', label: 'PAID', count: 1 }],
          byService: [{ code: 'CONS-NEW', name: 'New patient consultation', lines: 1, amount: 180 }],
          byMonth: [{ month: '2026-10', invoiced: 229.95, collected: 229.95 }], simulatedPayments: 229.95 },
        integration: { hl7Received: 4, hl7Processed: 4, hl7Failed: 0, hl7Rejected: 0, hl7Duplicates: 1,
          outboundSent: 0, outboundFailed: 2, deniedAccess: 3 },
      })),
      http.get('*/api/v1/reports/exports', () => HttpResponse.json([{ slug: 'invoices', adminOnly: true }])),
    );
    renderWithProviders(<ReportsPage />);

    const billing = await screen.findByRole('region', { name: 'Billing' });
    expect(within(billing).getByText(/No real payment gateway is connected/)).toBeInTheDocument();
    expect(within(billing).getByText('New patient consultation')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-Denied access')).toHaveTextContent('3');
    expect(await screen.findByRole('button', { name: /Invoices \(no patient names\)/ })).toBeInTheDocument();
  });

  it('shows the server validation message for an invalid period', async () => {
    signInAs('CLINICIAN');
    server.use(
      http.get('*/api/v1/reports/summary', () =>
        HttpResponse.json({ status: 422, message: "'from' must not be after 'to'" }, { status: 422 })),
      http.get('*/api/v1/reports/exports', () => HttpResponse.json([])),
    );
    renderWithProviders(<ReportsPage />);
    expect(await screen.findByText("'from' must not be after 'to'")).toBeInTheDocument();
  });
});
