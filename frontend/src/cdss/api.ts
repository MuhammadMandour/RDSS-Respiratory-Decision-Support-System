import { useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type Severity = 'NONE' | 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Disposition = 'PENDING' | 'ACCEPTED' | 'DISMISSED';

export interface AsthmaControlAnswers {
  daytimeSymptomsMoreThanTwiceWeekly?: boolean;
  nightWakingDueToAsthma?: boolean;
  relieverNeededMoreThanTwiceWeekly?: boolean;
  activityLimitation?: boolean;
}

export interface Guideline {
  code: string;
  title: string;
  publisher: string;
  version: string;
  publishedYear?: number;
  url?: string;
  citation?: string;
}

/** Mirrors CdssDtos.AlertResponse */
export interface Alert {
  id: number;
  ruleCode: string;
  ruleVersion: string;
  category: string;
  severity: Severity;
  title: string;
  explanation: string;
  suggestion?: string;
  evidence: Record<string, unknown>;
  guidelines: Guideline[];
  disposition: Disposition;
  dispositionReason?: string;
  dispositionByName?: string;
  dispositionAt?: string;
}

export interface News2Summary {
  score: number;
  risk: 'LOW' | 'LOW_MEDIUM' | 'MEDIUM' | 'HIGH';
  scale: 1 | 2;
}

/** Mirrors CdssDtos.AssessmentResponse */
export interface Assessment {
  id: number;
  patientId: number;
  encounterId?: number;
  requestedBy: number;
  requestedByName?: string;
  assessedAt: string;
  engineVersion: string;
  news2?: News2Summary;
  highestSeverity: Severity;
  missingData: string[];
  inputSnapshot: Record<string, unknown>;
  rulesApplied: { code: string; version: string; outcome: string }[];
  alerts: Alert[];
  inference?: Record<string, unknown>;
  review: { status: 'PENDING' | 'REVIEWED'; reviewedBy?: number; reviewedByName?: string; reviewedAt?: string; note?: string };
  disclaimer: string;
}

export interface AssessmentSummary {
  id: number;
  encounterId?: number;
  assessedAt: string;
  requestedByName?: string;
  news2?: News2Summary;
  highestSeverity: Severity;
  alertCount: number;
  reviewStatus: 'PENDING' | 'REVIEWED';
}

export interface Rule {
  code: string;
  name: string;
  category: string;
  description: string;
  ruleVersion: string;
  implementedVersion?: string;
  validatedMethod: boolean;
  enabled: boolean;
  parameters: Record<string, number>;
  guidelines: Guideline[];
  updatedAt: string;
  updatedBy?: string;
}

export interface ReviewRequest {
  note?: string;
  alerts: { alertId: number; disposition: Exclude<Disposition, 'PENDING'>; reason?: string }[];
}

export const cdssApi = {
  assess: (patientId: number, body: { encounterId?: number; asthmaControl?: AsthmaControlAnswers }) =>
    api.post<Assessment>(`/patients/${patientId}/cdss/assessments`, body),
  history: (patientId: number, encounterId?: number) =>
    api.get<Page<AssessmentSummary>>(`/patients/${patientId}/cdss/assessments`, { encounterId, size: 50 }),
  get: (id: number) => api.get<Assessment>(`/cdss/assessments/${id}`),
  review: (id: number, body: ReviewRequest) => api.post<Assessment>(`/cdss/assessments/${id}/review`, body),
  rules: () => api.get<Rule[]>('/cdss/rules'),
  updateRule: (code: string, enabled: boolean, parameters?: Record<string, number>) =>
    api.put<Rule>(`/cdss/rules/${code}`, { enabled, parameters }),
};

export const cdssKeys = {
  history: (pid: number) => ['patients', pid, 'cdss'] as const,
  detail: (id: number) => ['cdss', 'assessment', id] as const,
  rules: ['cdss', 'rules'] as const,
};

export function useAssessmentHistory(pid: number) {
  return useQuery({ queryKey: cdssKeys.history(pid), queryFn: () => cdssApi.history(pid) });
}

export function useAssessment(id: number | null) {
  return useQuery({ queryKey: cdssKeys.detail(id ?? 0), queryFn: () => cdssApi.get(id!), enabled: !!id });
}

export function useRules() {
  return useQuery({ queryKey: cdssKeys.rules, queryFn: cdssApi.rules });
}

export const severityColor = (s: Severity): 'default' | 'info' | 'success' | 'warning' | 'error' =>
  ({ NONE: 'success', INFO: 'info', LOW: 'default', MEDIUM: 'warning', HIGH: 'error', CRITICAL: 'error' } as const)[s];

export const alertSeverity = (s: Severity): 'info' | 'success' | 'warning' | 'error' =>
  ({ NONE: 'success', INFO: 'info', LOW: 'info', MEDIUM: 'warning', HIGH: 'error', CRITICAL: 'error' } as const)[s];

export const news2RiskLabel = (r: News2Summary['risk']) =>
  ({ LOW: 'Low', LOW_MEDIUM: 'Low–medium', MEDIUM: 'Medium', HIGH: 'High' } as const)[r];
