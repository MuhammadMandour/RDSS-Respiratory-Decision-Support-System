import { api } from '../shared/api/client';

export type Role = 'ADMIN' | 'CLINICIAN' | 'PATIENT';
export type Sex = 'FEMALE' | 'MALE' | 'OTHER' | 'UNKNOWN';

/** Mirrors AuthDtos.UserInfo */
export interface UserInfo {
  id: number;
  username: string;
  fullName: string;
  email: string;
  role: Role;
  patientId?: number;
}

/** Mirrors AuthDtos.AuthResponse */
export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  user: UserInfo;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  sex: Sex;
  phone?: string;
}

export const authApi = {
  login: (body: LoginRequest) => api.post<AuthResponse>('/auth/login', body),
  register: (body: RegisterRequest) => api.post<AuthResponse>('/auth/register', body),
  me: () => api.get<UserInfo>('/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/auth/change-password', { currentPassword, newPassword }),
};

/** Mirrors the backend password rule (AuthDtos.PASSWORD_REGEX). */
export const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d).{10,128}$/;
export const PASSWORD_HINT = 'At least 10 characters, including a letter and a digit.';
