import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, http } from '../shared/api/client';

export interface Count { key: string; label: string; count: number }
export interface DayCount { date: string; count: number }

/** Mirrors ReportDtos.Summary. `billing` and `integration` are only present for administrators. */
export interface ReportSummary {
  scope: 'ORGANIZATION' | 'CARE_TEAM';
  from: string;
  to: string;
  generatedAt: string;
  patients: {
    total: number; active: number; newInPeriod: number; withRespiratoryHistory: number;
    conditions: Count[]; smoking: Count[];
  };
  appointments: { total: number; byStatus: Count[]; completionRate?: number; noShowRate?: number; perDay: DayCount[] };
  topDiagnoses: Count[];
  imaging: { studies: number; byModality: Count[]; findingsByType: Count[]; pendingOrders: number };
  cdss: {
    assessments: number; reviewed: number; pendingReview: number;
    byHighestSeverity: Count[]; alertsByRule: Count[]; dismissedAlerts: number;
  };
  billing?: {
    currency: string; invoiced: number; collected: number; outstanding: number; overdueInvoices: number;
    byStatus: Count[]; byService: { code: string; name: string; lines: number; amount: number }[];
    byMonth: { month: string; invoiced: number; collected: number }[]; simulatedPayments: number;
  };
  integration?: {
    hl7Received: number; hl7Processed: number; hl7Failed: number; hl7Rejected: number; hl7Duplicates: number;
    outboundSent: number; outboundFailed: number; deniedAccess: number;
  };
  notes: Record<string, string>;
}

export interface DatasetInfo { slug: string; adminOnly: boolean }

export interface Period { from: string; to: string }

export const useReportSummary = (p: Period) =>
  useQuery({
    queryKey: ['reports', 'summary', p],
    queryFn: () => api.get<ReportSummary>('/reports/summary', p),
    placeholderData: keepPreviousData,
  });

export const useExportDatasets = () =>
  useQuery({ queryKey: ['reports', 'exports'], queryFn: () => api.get<DatasetInfo[]>('/reports/exports') });

/** Fetches the CSV with the bearer token (a plain link would not authenticate) and saves it. */
export async function downloadExport(slug: string, p: Period): Promise<void> {
  const res = await http.get<Blob>(`/reports/exports/${encodeURIComponent(slug)}`, {
    params: p,
    responseType: 'blob',
    headers: { Accept: 'text/csv' },
  });
  const disposition = String(res.headers['content-disposition'] ?? '');
  const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? `rdss-${slug}.csv`;
  const url = URL.createObjectURL(res.data);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

export const DATASET_LABELS: Record<string, string> = {
  'appointments-daily': 'Appointments per day and status',
  diagnoses: 'Diagnosis distribution (ICD-10)',
  'cdss-alerts': 'Decision-support alerts by rule',
  'imaging-findings': 'Imaging findings by type',
  'revenue-by-service': 'Revenue by service',
  invoices: 'Invoices (no patient names)',
  'hl7-messages': 'HL7 message log (metadata only)',
};
