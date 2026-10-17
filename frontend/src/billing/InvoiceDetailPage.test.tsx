import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from '../test/server';
import { renderWithProviders, signInAs } from '../test/render';
import { InvoiceDetailPage } from './InvoiceDetailPage';
import type { Invoice } from './api';

afterEach(() => sessionStorage.clear());

const base: Invoice = {
  summary: { id: 5, invoiceNumber: 'INV-2026-000005', patientId: 2, patientName: 'Samuel Okafor', patientMrn: 'MRN100002',
    status: 'ISSUED', issueDate: '2026-10-01', dueDate: '2026-10-31', currency: 'USD', total: 229.95, amountPaid: 0, outstanding: 229.95, overdue: false },
  subtotal: 235.5, discountTotal: 5.55, taxTotal: 0, createdAt: '2026-10-01T10:00:00Z', issuedAt: '2026-10-01T10:05:00Z', version: 1,
  lines: [{ id: 1, description: 'New patient respiratory consultation', quantity: 1, unitPrice: 180, discountPercent: 0, taxRate: 0, lineSubtotal: 180, lineDiscount: 0, lineTax: 0, lineTotal: 180 }],
  diagnoses: [{ sequence: 1, code: 'J44.1', description: 'Chronic obstructive pulmonary disease with (acute) exacerbation' }],
  payments: [], clinicName: 'RDSS Respiratory Clinic',
};

describe('InvoiceDetailPage', () => {
  it('lets a patient run a clearly labelled simulated payment and hides staff actions', async () => {
    signInAs('PATIENT', { patientId: 2 });
    server.use(
      http.get('*/api/v1/invoices/5', () => HttpResponse.json(base)),
      http.post('*/api/v1/invoices/5/simulated-payment', () => HttpResponse.json({
        notice: 'SIMULATED PAYMENT - demonstration only. No payment gateway was contacted and no money was transferred.',
        invoice: { ...base, summary: { ...base.summary, status: 'PAID', amountPaid: 229.95, outstanding: 0 },
          payments: [{ id: 9, amount: 229.95, method: 'SIMULATED', reference: 'SIM-1', simulated: true, status: 'COMPLETED', paidAt: '2026-10-02T10:00:00Z' }] },
      })),
    );
    renderWithProviders(<InvoiceDetailPage />, { route: '/billing/invoices/5', path: '/billing/invoices/:id' });

    expect(await screen.findByText('Invoice INV-2026-000005')).toBeInTheDocument();
    expect(screen.getByText(/J44.1/)).toBeInTheDocument();
    expect(screen.getByText(/is a/)).toHaveTextContent('simulation');
    expect(screen.queryByRole('button', { name: 'Record payment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Void' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Pay now (simulated)' }));
    expect(await screen.findByText(/no money was transferred/)).toBeInTheDocument();
    expect(screen.getByText('Simulated – no money moved')).toBeInTheDocument();
    // "Paid" appears as the totals label and now also as the status chip.
    expect(screen.getAllByText('Paid')).toHaveLength(2);
  });

  it('offers payment recording and voiding to administrators', async () => {
    signInAs('ADMIN');
    server.use(http.get('*/api/v1/invoices/5', () => HttpResponse.json(base)));
    renderWithProviders(<InvoiceDetailPage />, { route: '/billing/invoices/5', path: '/billing/invoices/:id' });
    expect(await screen.findByRole('button', { name: 'Record payment' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Void' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pay now (simulated)' })).not.toBeInTheDocument();
  });
});
