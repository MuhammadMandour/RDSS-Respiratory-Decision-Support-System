import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, type Page } from '../shared/api/client';

export type Hl7Status = 'RECEIVED' | 'PROCESSED' | 'FAILED' | 'REJECTED' | 'PENDING' | 'SENT';

/** Mirrors Hl7Controller.MessageSummary */
export interface Hl7MessageSummary {
  id: number;
  direction: 'INBOUND' | 'OUTBOUND';
  transport: 'HTTP' | 'MLLP';
  messageType?: string;
  controlId?: string;
  sendingApplication?: string;
  sendingFacility?: string;
  status: Hl7Status;
  ackCode?: string;
  errorMessage?: string;
  attempts: number;
  retryable: boolean;
  nextAttemptAt?: string;
  duplicateCount: number;
  patientId?: number;
  resourceType?: string;
  resourceId?: string;
  createdAt: string;
  processedAt?: string;
}

export interface Hl7MessageDetail {
  summary: Hl7MessageSummary;
  rawMessage: string;
  ackMessage?: string;
}

export interface Hl7Status_ {
  httpInboundEnabled: boolean;
  mllpEnabled: boolean;
  mllpPort: number;
  outboundEnabled: boolean;
  supportedInboundTypes: string[];
  hl7Version: string;
}

export const hl7Api = {
  status: () => api.get<Hl7Status_>('/hl7/status'),
  messages: (q: { direction?: string; status?: string; type?: string; page?: number }) =>
    api.get<Page<Hl7MessageSummary>>('/hl7/messages', { ...q, size: 50 }),
  message: (id: number) => api.get<Hl7MessageDetail>(`/hl7/messages/${id}`),
  reprocess: (id: number) => api.post<Hl7MessageDetail>(`/hl7/messages/${id}/reprocess`),
};

export function useHl7Status() {
  return useQuery({ queryKey: ['hl7', 'status'], queryFn: hl7Api.status });
}

export function useHl7Messages(q: { direction?: string; status?: string; type?: string; page?: number }) {
  return useQuery({ queryKey: ['hl7', 'messages', q], queryFn: () => hl7Api.messages(q), placeholderData: keepPreviousData });
}
