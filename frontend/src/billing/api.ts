import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'VOID';
export type PaymentMethod = 'CASH' | 'CARD' | 'BANK_TRANSFER' | 'INSURANCE' | 'SIMULATED';
export type ServiceCategory = 'CONSULTATION' | 'DIAGNOSTIC_TEST' | 'IMAGING' | 'PROCEDURE' | 'THERAPY' | 'OTHER';

/** Mirrors BillingDtos.ServiceResponse */
export interface BillingService {
  id: number;
  code: string;
  name: string;
  category: ServiceCategory;
  unitPrice: number;
  taxable: boolean;
  active: boolean;
}

export interface LineRequest {
  serviceId?: number;
  description?: string;
  unitPrice?: number;
  quantity: number;
  discountPercent?: number;
  taxable?: boolean;
}

export interface InvoiceRequest {
  patientId?: number;
  encounterId?: number;
  lines: LineRequest[];
  diagnosisCodes?: string[];
  notes?: string;
  version?: number;
}

export interface InvoiceSummary {
  id: number;
  invoiceNumber: string;
  patientId: number;
  patientName?: string;
  patientMrn?: string;
  status: InvoiceStatus;
  issueDate?: string;
  dueDate?: string;
  currency: string;
  total: number;
  amountPaid: number;
  outstanding: number;
  overdue: boolean;
}

export interface InvoiceLine {
  id: number;
  serviceId?: number;
  description: string;
  quantity: number;
  unitPrice: number;
  discountPercent: number;
  taxRate: number;
  lineSubtotal: number;
  lineDiscount: number;
  lineTax: number;
  lineTotal: number;
}

export interface Payment {
  id: number;
  amount: number;
  method: PaymentMethod;
  reference?: string;
  simulated: boolean;
  status: 'COMPLETED' | 'REVERSED';
  reversalReason?: string;
  paidAt: string;
  recordedByName?: string;
}

/** Mirrors BillingDtos.InvoiceResponse */
export interface Invoice {
  summary: InvoiceSummary;
  encounterId?: number;
  subtotal: number;
  discountTotal: number;
  taxTotal: number;
  notes?: string;
  voidReason?: string;
  createdAt: string;
  issuedAt?: string;
  voidedAt?: string;
  version: number;
  lines: InvoiceLine[];
  diagnoses: { sequence: number; code: string; description?: string }[];
  payments: Payment[];
  clinicName: string;
}

export interface InvoiceQuery {
  patientId?: number;
  status?: InvoiceStatus;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
}

export const billingApi = {
  services: () => api.get<BillingService[]>('/billing/services'),
  createService: (b: Omit<BillingService, 'id'>) => api.post<BillingService>('/billing/services', b),
  updateService: (id: number, b: Omit<BillingService, 'id'>) => api.put<BillingService>(`/billing/services/${id}`, b),
  invoices: (q: InvoiceQuery) => api.get<Page<InvoiceSummary>>('/invoices', { ...q, size: 25 }),
  invoice: (id: number) => api.get<Invoice>(`/invoices/${id}`),
  create: (b: InvoiceRequest) => api.post<Invoice>('/invoices', b),
  update: (id: number, b: InvoiceRequest) => api.put<Invoice>(`/invoices/${id}`, b),
  issue: (id: number) => api.post<Invoice>(`/invoices/${id}/issue`),
  void: (id: number, reason: string) => api.post<Invoice>(`/invoices/${id}/void`, { reason }),
  pay: (id: number, amount: number, method: PaymentMethod, reference?: string) =>
    api.post<Invoice>(`/invoices/${id}/payments`, { amount, method, reference }),
  simulatePay: (id: number) => api.post<{ invoice: Invoice; notice: string }>(`/invoices/${id}/simulated-payment`),
  reversePayment: (paymentId: number, reason: string) => api.post<Invoice>(`/payments/${paymentId}/reverse`, { reason }),
};

export const billingKeys = {
  services: ['billing', 'services'] as const,
  list: (q: InvoiceQuery) => ['invoices', q] as const,
  detail: (id: number) => ['invoice', id] as const,
};

export function useServices() {
  return useQuery({ queryKey: billingKeys.services, queryFn: billingApi.services });
}

export function useInvoices(q: InvoiceQuery) {
  return useQuery({ queryKey: billingKeys.list(q), queryFn: () => billingApi.invoices(q), placeholderData: keepPreviousData });
}

export function useInvoice(id: number) {
  return useQuery({ queryKey: billingKeys.detail(id), queryFn: () => billingApi.invoice(id) });
}

export const invoiceStatusColor = (s: InvoiceStatus): 'default' | 'warning' | 'info' | 'success' | 'error' =>
  ({ DRAFT: 'default', ISSUED: 'info', PARTIALLY_PAID: 'warning', PAID: 'success', VOID: 'default' } as const)[s];

export function money(v: number | undefined | null, currency = 'USD') {
  if (v === undefined || v === null) return '—';
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(v);
  } catch {
    return `${v.toFixed(2)} ${currency}`;
  }
}
