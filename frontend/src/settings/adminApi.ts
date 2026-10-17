import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';
import type { Role } from '../auth/api';

export interface ClinicianProfile {
  departmentId?: number;
  departmentName?: string;
  title?: string;
  specialty?: string;
  licenseNumber?: string;
  phone?: string;
  acceptingAppointments: boolean;
}

/** Mirrors UserDtos.UserResponse */
export interface User {
  id: number;
  username: string;
  email: string;
  fullName: string;
  role: Role;
  enabled: boolean;
  lastLoginAt?: string;
  createdAt: string;
  patientId?: number;
  clinicianProfile?: ClinicianProfile;
}

export interface ClinicianProfileRequest {
  departmentId?: number;
  title?: string;
  specialty?: string;
  licenseNumber?: string;
  phone?: string;
  acceptingAppointments?: boolean;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  fullName: string;
  role: Role;
  initialPassword: string;
  patientId?: number;
  clinicianProfile?: ClinicianProfileRequest;
}

export interface UpdateUserRequest {
  email: string;
  fullName: string;
  role: Role;
  clinicianProfile?: ClinicianProfileRequest;
}

export interface Department {
  id: number;
  name: string;
  description?: string;
  active: boolean;
}

export interface Setting {
  key: string;
  value: string;
  description?: string;
  updatedAt: string;
  updatedBy?: string;
}

export interface AuditEntry {
  id: number;
  occurredAt: string;
  actorUserId?: number;
  actorUsername?: string;
  actorRole?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  patientId?: number;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
  detail?: string;
  clientIp?: string;
}

export interface AuditQuery {
  actor?: string;
  action?: string;
  resourceType?: string;
  patientId?: number;
  outcome?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export const adminApi = {
  users: (q: { role?: Role; q?: string; page?: number; size?: number }) => api.get<Page<User>>('/users', q),
  createUser: (body: CreateUserRequest) => api.post<User>('/users', body),
  updateUser: (id: number, body: UpdateUserRequest) => api.put<User>(`/users/${id}`, body),
  setEnabled: (id: number, enabled: boolean) => api.post<User>(`/users/${id}/${enabled ? 'enable' : 'disable'}`),
  resetPassword: (id: number, newPassword: string) => api.post<void>(`/users/${id}/reset-password`, { newPassword }),
  departments: () => api.get<Department[]>('/departments'),
  createDepartment: (body: Omit<Department, 'id'>) => api.post<Department>('/departments', body),
  updateDepartment: (id: number, body: Omit<Department, 'id'>) => api.put<Department>(`/departments/${id}`, body),
  settings: () => api.get<Setting[]>('/settings'),
  updateSetting: (key: string, value: string) => api.put<Setting>(`/settings/${encodeURIComponent(key)}`, { value }),
  audit: (q: AuditQuery) => api.get<Page<AuditEntry>>('/audit', q),
};

export function useUsers(q: { role?: Role; q?: string; page?: number }) {
  return useQuery({ queryKey: ['users', q], queryFn: () => adminApi.users({ ...q, size: 20 }), placeholderData: keepPreviousData });
}

export function useDepartments() {
  return useQuery({ queryKey: ['departments'], queryFn: adminApi.departments });
}

export function useSettings() {
  return useQuery({ queryKey: ['settings'], queryFn: adminApi.settings });
}

export function useAudit(q: AuditQuery) {
  return useQuery({ queryKey: ['audit', q], queryFn: () => adminApi.audit(q), placeholderData: keepPreviousData });
}
