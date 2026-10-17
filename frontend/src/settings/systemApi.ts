import { useQuery } from '@tanstack/react-query';
import { api } from '../shared/api/client';

/** Mirrors backend com.rdss.system.SystemStatusResponse. */
export interface DependencyStatus {
  name: string;
  status: 'UP' | 'DOWN';
  detail?: string;
}

export interface SystemStatus {
  application: string;
  version: string;
  status: 'UP' | 'DEGRADED';
  checkedAt: string;
  dependencies: DependencyStatus[];
}

export const getSystemStatus = () => api.get<SystemStatus>('/system/status');

export function useSystemStatus() {
  return useQuery({
    queryKey: ['system', 'status'],
    queryFn: getSystemStatus,
    refetchInterval: 30_000,
  });
}
