import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { AssessmentResult } from './AssessmentResult';
import type { Assessment } from './api';

afterEach(() => sessionStorage.clear());

const assessment: Assessment = {
  id: 11,
  patientId: 3,
  requestedBy: 2,
  requestedByName: 'Dr. Test',
  assessedAt: '2030-01-01T10:00:00Z',
  engineVersion: '1.0.0',
  news2: { score: 8, risk: 'HIGH', scale: 1 },
  highestSeverity: 'CRITICAL',
  missingData: ['No respiratory history recorded'],
  inputSnapshot: { latestObservation: { id: 5, spo2: 92 } },
  rulesApplied: [{ code: 'NEWS2', version: '1.0.0', outcome: 'FINDINGS:1' }],
  alerts: [
    { id: 101, ruleCode: 'NEWS2', ruleVersion: '1.0.0', category: 'DETERIORATION', severity: 'CRITICAL', title: 'NEWS2 score 8 — high clinical risk',
      explanation: 'Component scores ...', suggestion: 'RCP NEWS2 recommended response ...', evidence: { total: 8 },
      guidelines: [{ code: 'RCP-NEWS2', title: 'National Early Warning Score (NEWS) 2', publisher: 'Royal College of Physicians', version: 'NEWS2 (December 2017)' }],
      disposition: 'PENDING' },
    { id: 102, ruleCode: 'HYPOXAEMIA', ruleVersion: '1.0.0', category: 'OXYGENATION', severity: 'MEDIUM', title: 'SpO2 92% below target range',
      explanation: 'Latest SpO2 ...', evidence: { spo2: 92 }, guidelines: [], disposition: 'PENDING' },
  ],
  review: { status: 'PENDING' },
  disclaimer: 'Decision support only.',
};

describe('AssessmentResult', () => {
  it('labels system output, shows NEWS2, missing data and explanations', () => {
    signInAs('CLINICIAN');
    renderWithProviders(<AssessmentResult assessment={assessment} />);
    expect(screen.getByText('System-generated')).toBeInTheDocument();
    expect(screen.getByLabelText('NEWS2 score 8')).toHaveTextContent('High risk');
    expect(screen.getByText(/No respiratory history recorded/)).toBeInTheDocument();
    expect(screen.getByText('Decision support only.')).toBeInTheDocument();
    expect(screen.getAllByText(/Why:/)).toHaveLength(2);
  });

  it('requires a disposition for every alert and a reason to dismiss before submitting', async () => {
    signInAs('CLINICIAN');
    let body: unknown;
    server.use(
      http.post('*/api/v1/cdss/assessments/11/review', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...assessment, review: { status: 'REVIEWED', reviewedByName: 'Dr. Test', reviewedAt: '2030-01-01T10:05:00Z', note: 'ok' } });
      }),
      http.get('*/api/v1/patients/3/cdss/assessments', () => HttpResponse.json({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })),
    );
    renderWithProviders(<AssessmentResult assessment={assessment} />);
    const submit = screen.getByRole('button', { name: 'Complete review' });
    expect(submit).toBeDisabled();

    const groups = screen.getAllByRole('group', { name: /Disposition for/ });
    await userEvent.click(within(groups[0]!).getByRole('button', { name: 'Accept' }));
    await userEvent.click(within(groups[1]!).getByRole('button', { name: 'Dismiss' }));
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Reason for dismissal/), 'Known chronic hypoxaemia');
    expect(submit).toBeEnabled();
    await userEvent.click(submit);

    expect(await screen.findByText(/Reviewed by Dr. Test/)).toBeInTheDocument();
    expect(body).toEqual({
      alerts: [
        { alertId: 101, disposition: 'ACCEPTED' },
        { alertId: 102, disposition: 'DISMISSED', reason: 'Known chronic hypoxaemia' },
      ],
    });
  });
});
