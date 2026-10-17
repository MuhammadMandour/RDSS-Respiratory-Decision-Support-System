import { useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

/** Mirrors DiagnosisDtos.Icd10Response */
export interface Icd10Code {
  code: string;
  description: string;
  billable: boolean;
  codeSystem: string;
  codeVersion: string;
  source: 'CURATED_SUBSET' | 'OFFICIAL_IMPORT';
  active: boolean;
}

export type DiagnosisType = 'PRIMARY' | 'SECONDARY' | 'COMORBIDITY';

export interface Diagnosis {
  id: number;
  patientId: number;
  encounterId?: number;
  code: string;
  codeVersion: string;
  description: string;
  type: DiagnosisType;
  status: 'ACTIVE' | 'RESOLVED' | 'ENTERED_IN_ERROR';
  onsetDate?: string;
  resolvedDate?: string;
  notes?: string;
  errorReason?: string;
  recordedByName?: string;
  recordedAt: string;
}

/** Same rule as the backend (ICD-10-CM shape). */
export const ICD10_PATTERN = /^[A-Z][0-9][0-9A-Z](\.[0-9A-Z]{1,4})?$/;

export const diagnosesApi = {
  searchCodes: (q: string, opts: { billableOnly?: boolean; includeInactive?: boolean; page?: number } = {}) =>
    api.get<Page<Icd10Code>>('/icd10', { q: q || undefined, size: 20, ...opts }),
  setCodeActive: (code: string, active: boolean) => api.put<Icd10Code>(`/icd10/${encodeURIComponent(code)}/active?active=${active}`),
  list: (pid: number) => api.get<Diagnosis[]>(`/patients/${pid}/diagnoses`),
  record: (pid: number, b: { code: string; type: DiagnosisType; encounterId?: number; onsetDate?: string; notes?: string }) =>
    api.post<Diagnosis>(`/patients/${pid}/diagnoses`, b),
  resolve: (id: number) => api.post<Diagnosis>(`/diagnoses/${id}/resolve`),
  enteredInError: (id: number, reason: string) => api.post<Diagnosis>(`/diagnoses/${id}/entered-in-error`, { reason }),
};

export function useIcd10Search(q: string, billableOnly = true) {
  return useQuery({ queryKey: ['icd10', q, billableOnly], queryFn: () => diagnosesApi.searchCodes(q, { billableOnly }) });
}

export function useDiagnoses(pid: number) {
  return useQuery({ queryKey: ['patients', pid, 'diagnoses'], queryFn: () => diagnosesApi.list(pid) });
}
