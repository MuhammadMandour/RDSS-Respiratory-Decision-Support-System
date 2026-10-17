import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';
import type { Sex } from '../auth/api';

export type SmokingStatus = 'NEVER' | 'FORMER' | 'CURRENT' | 'UNKNOWN';

export interface ClinicianRef {
  id: number;
  fullName?: string;
}

/** Mirrors PatientDtos.PatientSummary */
export interface PatientSummary {
  id: number;
  mrn: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  age: number;
  sex: Sex;
  smokingStatus: SmokingStatus;
  primaryClinician?: ClinicianRef;
  active: boolean;
}

/** Mirrors PatientDtos.PatientResponse (clinical fields are omitted for administrators). */
export interface Patient {
  id: number;
  mrn: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  age: number;
  sex: Sex;
  phone?: string;
  email?: string;
  addressLine?: string;
  city?: string;
  postalCode?: string;
  country?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelation?: string;
  medicalHistory?: string;
  allergies?: string;
  currentMedications?: string;
  smokingStatus?: SmokingStatus;
  packYears?: number;
  environmentalExposures?: string;
  knownRespiratoryConditions?: string;
  primaryClinician?: ClinicianRef;
  hasPortalAccount: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors PatientDtos.PatientRequest */
export interface PatientRequest {
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  sex: Sex;
  phone?: string;
  email?: string;
  addressLine?: string;
  city?: string;
  postalCode?: string;
  country?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelation?: string;
  medicalHistory?: string;
  allergies?: string;
  currentMedications?: string;
  smokingStatus?: SmokingStatus;
  packYears?: number;
  environmentalExposures?: string;
  knownRespiratoryConditions?: string;
  primaryClinicianId?: number;
}

export type ContactUpdate = Pick<
  PatientRequest,
  'phone' | 'email' | 'addressLine' | 'city' | 'postalCode' | 'country' | 'emergencyContactName' | 'emergencyContactPhone' | 'emergencyContactRelation'
>;

export interface CareTeamMember {
  clinicianId: number;
  fullName?: string;
  reason: 'PRIMARY' | 'ASSIGNED' | 'REGISTERED' | 'APPOINTMENT';
  addedAt: string;
}

export const patientKeys = {
  all: ['patients'] as const,
  list: (q: string, page: number, includeInactive: boolean) => ['patients', 'list', q, page, includeInactive] as const,
  detail: (id: number) => ['patients', id] as const,
  careTeam: (id: number) => ['patients', id, 'care-team'] as const,
};

export const patientsApi = {
  search: (q: string, page: number, size = 20, includeInactive = false) =>
    api.get<Page<PatientSummary>>('/patients', { q: q || undefined, page, size, includeInactive }),
  get: (id: number) => api.get<Patient>(`/patients/${id}`),
  create: (body: PatientRequest) => api.post<Patient>('/patients', body),
  update: (id: number, body: PatientRequest) => api.put<Patient>(`/patients/${id}`, body),
  updateContact: (id: number, body: ContactUpdate) => api.patch<Patient>(`/patients/${id}/contact`, body),
  setActive: (id: number, active: boolean) => api.post<Patient>(`/patients/${id}/${active ? 'activate' : 'deactivate'}`),
  careTeam: (id: number) => api.get<CareTeamMember[]>(`/patients/${id}/care-team`),
  assign: (id: number, clinicianId: number) => api.post<CareTeamMember[]>(`/patients/${id}/care-team`, { clinicianId }),
  unassign: (id: number, clinicianId: number) => api.delete<CareTeamMember[]>(`/patients/${id}/care-team/${clinicianId}`),
};

export function usePatientSearch(q: string, page: number, includeInactive = false) {
  return useQuery({
    queryKey: patientKeys.list(q, page, includeInactive),
    queryFn: () => patientsApi.search(q, page, 20, includeInactive),
    placeholderData: keepPreviousData,
  });
}

export function usePatient(id: number) {
  return useQuery({ queryKey: patientKeys.detail(id), queryFn: () => patientsApi.get(id), enabled: id > 0 });
}

export function useCareTeam(id: number, enabled = true) {
  return useQuery({ queryKey: patientKeys.careTeam(id), queryFn: () => patientsApi.careTeam(id), enabled });
}

export function useSavePatient(id?: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: PatientRequest) => (id ? patientsApi.update(id, body) : patientsApi.create(body)),
    onSuccess: (p) => {
      qc.setQueryData(patientKeys.detail(p.id), p);
      void qc.invalidateQueries({ queryKey: patientKeys.all });
    },
  });
}

export const fullName = (p: { firstName: string; lastName: string }) => `${p.firstName} ${p.lastName}`;
