import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type AppointmentStatus = 'REQUESTED' | 'SCHEDULED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
export type AppointmentType = 'CONSULTATION' | 'FOLLOW_UP' | 'SPIROMETRY' | 'IMAGING_REVIEW' | 'TELEHEALTH';
export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export const APPOINTMENT_TYPES: AppointmentType[] = ['CONSULTATION', 'FOLLOW_UP', 'SPIROMETRY', 'IMAGING_REVIEW', 'TELEHEALTH'];
export const DAYS: DayOfWeek[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/** Mirrors AppointmentDtos.AppointmentResponse */
export interface Appointment {
  id: number;
  patientId: number;
  patientName?: string;
  patientMrn?: string;
  clinicianId: number;
  clinicianName?: string;
  startsAt: string;
  endsAt: string;
  status: AppointmentStatus;
  type: AppointmentType;
  reason?: string;
  encounterId?: number;
  rescheduledFromId?: number;
  cancelledAt?: string;
  cancellationReason?: string;
  createdAt: string;
}

export interface BookRequest {
  patientId?: number;
  clinicianId: number;
  startsAt: string;
  durationMinutes?: number;
  type: AppointmentType;
  reason?: string;
}

export interface Slot {
  startsAt: string;
  endsAt: string;
}

export interface AvailabilityWindow {
  id?: number;
  dayOfWeek: DayOfWeek;
  startTime: string;
  endTime: string;
  slotMinutes: number;
  validFrom?: string;
  validTo?: string;
}

/** Mirrors UserDtos.ClinicianDirectoryEntry */
export interface ClinicianEntry {
  id: number;
  fullName: string;
  title?: string;
  specialty?: string;
  departmentId?: number;
  departmentName?: string;
  acceptingAppointments: boolean;
}

export interface AppointmentQuery {
  patientId?: number;
  clinicianId?: number;
  status?: AppointmentStatus;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: 'asc' | 'desc';
}

export const appointmentsApi = {
  search: (q: AppointmentQuery) => api.get<Page<Appointment>>('/appointments', q),
  get: (id: number) => api.get<Appointment>(`/appointments/${id}`),
  book: (body: BookRequest) => api.post<Appointment>('/appointments', body),
  reschedule: (id: number, startsAt: string, durationMinutes?: number) =>
    api.post<Appointment>(`/appointments/${id}/reschedule`, { startsAt, durationMinutes }),
  cancel: (id: number, reason: string) => api.post<Appointment>(`/appointments/${id}/cancel`, { reason }),
  setStatus: (id: number, status: AppointmentStatus) => api.post<Appointment>(`/appointments/${id}/status`, { status }),
  startEncounter: (id: number) => api.post<{ id: number }>(`/appointments/${id}/encounter`),
  slots: (clinicianId: number, from: string, to: string) => api.get<Slot[]>(`/clinicians/${clinicianId}/slots`, { from, to }),
  availability: (clinicianId: number) => api.get<AvailabilityWindow[]>(`/clinicians/${clinicianId}/availability`),
  replaceAvailability: (clinicianId: number, windows: AvailabilityWindow[]) =>
    api.put<AvailabilityWindow[]>(`/clinicians/${clinicianId}/availability`, windows),
  clinicians: () => api.get<ClinicianEntry[]>('/clinicians'),
};

export function useAppointments(q: AppointmentQuery) {
  return useQuery({ queryKey: ['appointments', q], queryFn: () => appointmentsApi.search(q), placeholderData: keepPreviousData });
}

export function useClinicians() {
  return useQuery({ queryKey: ['clinicians'], queryFn: appointmentsApi.clinicians, staleTime: 60_000 });
}

export function useSlots(clinicianId: number | undefined, from: string, to: string) {
  return useQuery({
    queryKey: ['slots', clinicianId, from, to],
    queryFn: () => appointmentsApi.slots(clinicianId!, from, to),
    enabled: !!clinicianId && !!from && !!to,
  });
}

export function useAvailability(clinicianId: number | undefined) {
  return useQuery({
    queryKey: ['availability', clinicianId],
    queryFn: () => appointmentsApi.availability(clinicianId!),
    enabled: !!clinicianId,
  });
}

export const statusColor = (s: AppointmentStatus): 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' =>
  ({ REQUESTED: 'warning', SCHEDULED: 'primary', CHECKED_IN: 'info', COMPLETED: 'success', CANCELLED: 'default', NO_SHOW: 'error' } as const)[s];
