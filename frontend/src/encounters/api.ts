import { useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type EncounterType = 'OUTPATIENT' | 'INPATIENT' | 'EMERGENCY' | 'TELEHEALTH';
export type EncounterStatus = 'IN_PROGRESS' | 'FINALIZED' | 'SUPERSEDED';

export interface EncounterSummary {
  id: number;
  patientId: number;
  patientName?: string;
  clinicianId: number;
  clinicianName?: string;
  type: EncounterType;
  status: EncounterStatus;
  startedAt: string;
  finalizedAt?: string;
  chiefComplaint?: string;
  amendsEncounterId?: number;
}

/** Editable documentation fields (EncounterDtos.UpdateEncounterRequest minus version/type). */
export interface EncounterDocumentation {
  chiefComplaint?: string;
  historyOfPresentIllness?: string;
  cough?: boolean;
  coughDurationDays?: number;
  sputum?: boolean;
  sputumDescription?: string;
  wheezing?: boolean;
  dyspnea?: boolean;
  mmrcGrade?: number;
  catScore?: number;
  chestPain?: boolean;
  fever?: boolean;
  hemoptysis?: boolean;
  symptomDurationDays?: number;
  physicalExam?: string;
  labResults?: string;
  assessmentNotes?: string;
  planNotes?: string;
  followUp?: string;
}

export interface Encounter extends EncounterDocumentation {
  id: number;
  patientId: number;
  clinicianId: number;
  clinicianName?: string;
  type: EncounterType;
  status: EncounterStatus;
  startedAt: string;
  finalizedAt?: string;
  finalizedBy?: number;
  amendsEncounterId?: number;
  amendmentReason?: string;
  amendedByEncounterId?: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type PlanStatus = 'ACTIVE' | 'COMPLETED' | 'DISCONTINUED';

export interface TreatmentPlanRequest {
  encounterId?: number;
  title: string;
  goals?: string;
  interventions?: string;
  followUpInstructions?: string;
  startDate: string;
  reviewDate?: string;
  status?: PlanStatus;
  version?: number;
}

export interface TreatmentPlan extends TreatmentPlanRequest {
  id: number;
  patientId: number;
  status: PlanStatus;
  createdBy: number;
  createdByName?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface PrescriptionRequest {
  encounterId?: number;
  medicationName: string;
  dose: string;
  route: string;
  frequency: string;
  duration?: string;
  quantity?: string;
  refills: number;
  instructions?: string;
}

export interface Prescription extends PrescriptionRequest {
  id: number;
  patientId: number;
  status: 'ACTIVE' | 'COMPLETED' | 'DISCONTINUED';
  discontinuedReason?: string;
  prescribedBy: number;
  prescribedByName?: string;
  prescribedAt: string;
}

export const encounterKeys = {
  list: (pid: number) => ['patients', pid, 'encounters'] as const,
  detail: (id: number) => ['encounters', id] as const,
  mine: (status: EncounterStatus) => ['encounters', 'mine', status] as const,
  plans: (pid: number) => ['patients', pid, 'treatment-plans'] as const,
  prescriptions: (pid: number) => ['patients', pid, 'prescriptions'] as const,
};

export const encountersApi = {
  list: (pid: number, page = 0) => api.get<Page<EncounterSummary>>(`/patients/${pid}/encounters`, { page, size: 50 }),
  mine: (status: EncounterStatus) => api.get<Page<EncounterSummary>>('/encounters/mine', { status, size: 20 }),
  get: (id: number) => api.get<Encounter>(`/encounters/${id}`),
  create: (pid: number, type: EncounterType, chiefComplaint?: string) =>
    api.post<Encounter>(`/patients/${pid}/encounters`, { type, chiefComplaint }),
  update: (id: number, body: EncounterDocumentation & { version: number; type: EncounterType }) =>
    api.put<Encounter>(`/encounters/${id}`, body),
  finalize: (id: number) => api.post<Encounter>(`/encounters/${id}/finalize`),
  amend: (id: number, reason: string) => api.post<Encounter>(`/encounters/${id}/amend`, { reason }),
  plans: (pid: number) => api.get<TreatmentPlan[]>(`/patients/${pid}/treatment-plans`),
  createPlan: (pid: number, body: TreatmentPlanRequest) => api.post<TreatmentPlan>(`/patients/${pid}/treatment-plans`, body),
  updatePlan: (id: number, body: TreatmentPlanRequest) => api.put<TreatmentPlan>(`/treatment-plans/${id}`, body),
  prescriptions: (pid: number) => api.get<Prescription[]>(`/patients/${pid}/prescriptions`),
  prescribe: (pid: number, body: PrescriptionRequest) => api.post<Prescription>(`/patients/${pid}/prescriptions`, body),
  discontinue: (id: number, reason: string) => api.post<Prescription>(`/prescriptions/${id}/discontinue`, { reason }),
  complete: (id: number) => api.post<Prescription>(`/prescriptions/${id}/complete`),
};

export function useEncounters(pid: number) {
  return useQuery({ queryKey: encounterKeys.list(pid), queryFn: () => encountersApi.list(pid) });
}

export function useEncounter(id: number) {
  return useQuery({ queryKey: encounterKeys.detail(id), queryFn: () => encountersApi.get(id) });
}

export function useMyEncounters(status: EncounterStatus, enabled = true) {
  return useQuery({ queryKey: encounterKeys.mine(status), queryFn: () => encountersApi.mine(status), enabled });
}

export function useTreatmentPlans(pid: number) {
  return useQuery({ queryKey: encounterKeys.plans(pid), queryFn: () => encountersApi.plans(pid) });
}

export function usePrescriptions(pid: number) {
  return useQuery({ queryKey: encounterKeys.prescriptions(pid), queryFn: () => encountersApi.prescriptions(pid) });
}
