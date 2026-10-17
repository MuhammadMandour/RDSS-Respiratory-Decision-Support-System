import axios, { AxiosError, type AxiosInstance } from 'axios';

/** Mirrors backend com.rdss.common.error.ApiError. */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: { field: string; message: string }[];
}

/** Spring Data page envelope as serialized by the backend's PageResponse DTO. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Normalized error thrown by every API call so UI code handles a single shape. */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, message: string, fieldErrors: { field: string; message: string }[] = []) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

export function toApiRequestError(error: unknown): ApiRequestError {
  if (error instanceof ApiRequestError) return error;
  if (axios.isAxiosError(error)) {
    const ax = error as AxiosError<Partial<ApiErrorBody>>;
    if (!ax.response) {
      return new ApiRequestError(0, 'Unable to reach the server. Check your connection.');
    }
    const body = ax.response.data;
    const message =
      body && typeof body === 'object' && typeof body.message === 'string'
        ? body.message
        : `Request failed (${ax.response.status})`;
    return new ApiRequestError(ax.response.status, message, body?.fieldErrors ?? []);
  }
  return new ApiRequestError(0, error instanceof Error ? error.message : 'Unexpected error');
}

type TokenProvider = () => string | null;
type UnauthorizedHandler = () => void;

let tokenProvider: TokenProvider = () => null;
let onUnauthorized: UnauthorizedHandler = () => {};

/** Wired by the auth module so the client stays independent of React state. */
export function configureAuth(provider: TokenProvider, unauthorized: UnauthorizedHandler) {
  tokenProvider = provider;
  onUnauthorized = unauthorized;
}

export const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
  headers: { Accept: 'application/json' },
});

http.interceptors.request.use((config) => {
  const token = tokenProvider();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const normalized = toApiRequestError(error);
    if (normalized.status === 401 && tokenProvider()) {
      onUnauthorized();
    }
    return Promise.reject(normalized);
  },
);

/** Typed helpers: return the response body directly. */
export const api = {
  get: async <T>(url: string, params?: object) => (await http.get<T>(url, { params })).data,
  post: async <T>(url: string, body?: unknown) => (await http.post<T>(url, body)).data,
  put: async <T>(url: string, body?: unknown) => (await http.put<T>(url, body)).data,
  patch: async <T>(url: string, body?: unknown) => (await http.patch<T>(url, body)).data,
  delete: async <T = void>(url: string) => (await http.delete<T>(url)).data,
};
