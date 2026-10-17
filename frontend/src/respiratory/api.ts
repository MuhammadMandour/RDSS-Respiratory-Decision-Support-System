import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type Consciousness = 'ALERT' | 'CONFUSION' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

/** Mirrors RespiratoryDtos.RespiratoryHistoryRequest */
export interface RespiratoryHistoryRequest {
  asthma: boolean;
  copd: boolean;
  bronchiectasis: boolean;
  interstitialLungDisease: boolean;
  obstructiveSleepApnea: boolean;
  previousTuberculosis: boolean;
  hypercapnicRespiratoryFailureRisk: boolean;
  targetSpo2Low?: number;
  targetSpo2High?: number;
  homeOxygen: boolean;
  exacerbationsLast12m?: number;
  hospitalizationsLast12m?: number;
  previousIntubation: boolean;
  inhalerTherapy?: string;
  occupationalExposures?: string;
  familyHistory?: string;
  vaccinations?: string;
  comorbidities?: string;
  notes?: string;
}

export interface RespiratoryHistory extends RespiratoryHistoryRequest {
  id: number;
  patientId: number;
  versionNo: number;
  supersedesId?: number;
  recordedBy: number;
  recordedByName?: string;
  recordedAt: string;
}

/** Mirrors RespiratoryDtos.ObservationRequest */
export interface ObservationRequest {
  encounterId?: number;
  observedAt: string;
  respiratoryRate?: number;
  heartRate?: number;
  systolicBp?: number;
  diastolicBp?: number;
  temperatureC?: number;
  spo2?: number;
  onSupplementalOxygen?: boolean;
  oxygenFlowLpm?: number;
  fio2Percent?: number;
  consciousness?: Consciousness;
  peakFlowLpm?: number;
  notes?: string;
}

export interface Observation extends ObservationRequest {
  id: number;
  patientId: number;
  enteredInError: boolean;
  errorReason?: string;
  recordedBy: number;
  recordedByName?: string;
  createdAt: string;
}

/** Mirrors RespiratoryDtos.PulmonaryFunctionTestRequest */
export interface PftRequest {
  encounterId?: number;
  performedAt: string;
  postBronchodilator: boolean;
  fev1L?: number;
  fvcL?: number;
  fev1PercentPredicted?: number;
  fvcPercentPredicted?: number;
  dlcoPercentPredicted?: number;
  bronchodilatorResponsePercent?: number;
  bronchodilatorResponseMl?: number;
  qualityGrade?: string;
  interpretation?: string;
}

export interface Pft extends PftRequest {
  id: number;
  patientId: number;
  fev1FvcRatio?: number;
  enteredInError: boolean;
  errorReason?: string;
  recordedBy: number;
  recordedByName?: string;
  createdAt: string;
}

export const respiratoryKeys = {
  history: (pid: number) => ['patients', pid, 'respiratory-history'] as const,
  historyVersions: (pid: number) => ['patients', pid, 'respiratory-history', 'versions'] as const,
  observations: (pid: number, encounterId?: number) => ['patients', pid, 'observations', encounterId ?? 'all'] as const,
  pfts: (pid: number) => ['patients', pid, 'pfts'] as const,
};

export const respiratoryApi = {
  // 204 (no history yet) arrives as an empty body.
  currentHistory: async (pid: number) => (await api.get<RespiratoryHistory | ''>(`/patients/${pid}/respiratory-history`)) || null,
  historyVersions: (pid: number) => api.get<RespiratoryHistory[]>(`/patients/${pid}/respiratory-history/versions`),
  recordHistory: (pid: number, body: RespiratoryHistoryRequest) =>
    api.post<RespiratoryHistory>(`/patients/${pid}/respiratory-history`, body),
  observations: (pid: number, encounterId?: number, size = 100) =>
    api.get<Page<Observation>>(`/patients/${pid}/observations`, { encounterId, size }),
  recordObservation: (pid: number, body: ObservationRequest) => api.post<Observation>(`/patients/${pid}/observations`, body),
  observationInError: (id: number, reason: string) => api.post<Observation>(`/observations/${id}/entered-in-error`, { reason }),
  pfts: (pid: number) => api.get<Pft[]>(`/patients/${pid}/pulmonary-function-tests`),
  recordPft: (pid: number, body: PftRequest) => api.post<Pft>(`/patients/${pid}/pulmonary-function-tests`, body),
  pftInError: (id: number, reason: string) => api.post<Pft>(`/pulmonary-function-tests/${id}/entered-in-error`, { reason }),
};

export function useRespiratoryHistory(pid: number) {
  return useQuery({ queryKey: respiratoryKeys.history(pid), queryFn: () => respiratoryApi.currentHistory(pid) });
}

export function useHistoryVersions(pid: number, enabled: boolean) {
  return useQuery({ queryKey: respiratoryKeys.historyVersions(pid), queryFn: () => respiratoryApi.historyVersions(pid), enabled });
}

export function useObservations(pid: number, encounterId?: number) {
  return useQuery({
    queryKey: respiratoryKeys.observations(pid, encounterId),
    queryFn: () => respiratoryApi.observations(pid, encounterId),
  });
}

export function usePfts(pid: number) {
  return useQuery({ queryKey: respiratoryKeys.pfts(pid), queryFn: () => respiratoryApi.pfts(pid) });
}

/** Invalidates everything under a patient after a clinical write. */
export function useInvalidatePatient(pid: number) {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['patients', pid] });
}

export function useRecordObservation(pid: number) {
  const invalidate = useInvalidatePatient(pid);
  return useMutation({ mutationFn: (b: ObservationRequest) => respiratoryApi.recordObservation(pid, b), onSuccess: invalidate });
}
