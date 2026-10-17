import { useQuery } from '@tanstack/react-query';
import { api, http } from '../shared/api/client';

export type FindingType =
  | 'PULMONARY_OPACITY' | 'CONSOLIDATION' | 'PLEURAL_EFFUSION' | 'PULMONARY_NODULE' | 'PNEUMOTHORAX' | 'ATELECTASIS'
  | 'INTERSTITIAL_CHANGES' | 'EMPHYSEMA' | 'BRONCHIECTASIS' | 'CARDIOMEGALY' | 'OTHER';
export type Laterality = 'LEFT' | 'RIGHT' | 'BILATERAL' | 'NOT_APPLICABLE';
export type FindingSource = 'CLINICIAN' | 'RADIOLOGIST_REPORT' | 'DEMONSTRATION' | 'AI_UNVALIDATED';
export type FindingStatus = 'PRELIMINARY' | 'FINAL' | 'ENTERED_IN_ERROR';

export const FINDING_TYPES: FindingType[] = [
  'PULMONARY_OPACITY', 'CONSOLIDATION', 'PLEURAL_EFFUSION', 'PULMONARY_NODULE', 'PNEUMOTHORAX', 'ATELECTASIS',
  'INTERSTITIAL_CHANGES', 'EMPHYSEMA', 'BRONCHIECTASIS', 'CARDIOMEGALY', 'OTHER',
];

/** Mirrors ImagingDtos.StudySummary */
export interface StudySummary {
  id: number;
  patientId: number;
  encounterId?: number;
  orderId?: number;
  studyInstanceUid: string;
  accessionNumber?: string;
  studyDate?: string;
  studyDescription?: string;
  modalities?: string;
  numberOfSeries: number;
  numberOfInstances: number;
  patientIdMismatch: boolean;
  source: 'UPLOAD' | 'LINKED' | 'DEMO' | 'HL7';
  linkedAt: string;
  findingCount: number;
}

export interface Series {
  id: number;
  seriesInstanceUid: string;
  modality?: string;
  bodyPart?: string;
  description?: string;
  seriesNumber?: number;
  numberOfInstances: number;
}

export interface Finding {
  id: number;
  studyId: number;
  seriesId?: number;
  type: FindingType;
  laterality: Laterality;
  location?: string;
  sizeMm?: number;
  description: string;
  source: FindingSource;
  status: FindingStatus;
  sopInstanceUid?: string;
  frameNumber?: number;
  errorReason?: string;
  recordedBy: number;
  recordedByName?: string;
  recordedAt: string;
}

export interface StudyDetail {
  study: StudySummary;
  dicomPatientId?: string;
  series: Series[];
  findings: Finding[];
}

export interface FindingRequest {
  seriesId?: number;
  type: FindingType;
  laterality?: Laterality;
  location?: string;
  sizeMm?: number;
  description: string;
  source: Exclude<FindingSource, 'AI_UNVALIDATED'>;
  status?: Exclude<FindingStatus, 'ENTERED_IN_ERROR'>;
}

export interface ImagingOrder {
  id: number;
  patientId: number;
  encounterId?: number;
  accessionNumber: string;
  modality: string;
  bodyPart: string;
  procedureDescription: string;
  clinicalIndication?: string;
  priority: 'ROUTINE' | 'URGENT' | 'STAT';
  status: 'REQUESTED' | 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
  externalOrderId?: string;
  orderedByName?: string;
  orderedAt: string;
}

export interface UploadResult {
  studies: StudySummary[];
  instancesStored: number;
  instancesAlreadyPresent: number;
  warnings: string[];
}

export interface ViewerSession {
  viewerUrl: string;
  expiresAt: string;
  studyInstanceUids: string[];
  notice: string;
}

export const imagingApi = {
  studies: (pid: number) => api.get<StudySummary[]>(`/patients/${pid}/imaging-studies`),
  study: (id: number) => api.get<StudyDetail>(`/imaging-studies/${id}`),
  upload: async (pid: number, files: File[], opts: { encounterId?: number; orderId?: number }, onProgress?: (pct: number) => void) => {
    const form = new FormData();
    files.forEach((f) => form.append('files', f, f.name));
    const res = await http.post<UploadResult>(`/patients/${pid}/imaging-studies/upload`, form, {
      params: opts,
      timeout: 600_000,
      onUploadProgress: (e) => e.total && onProgress?.(Math.round((e.loaded / e.total) * 100)),
    });
    return res.data;
  },
  link: (pid: number, studyInstanceUid: string, orderId?: number) =>
    api.post<StudyDetail>(`/patients/${pid}/imaging-studies/link`, { studyInstanceUid, orderId }),
  addFinding: (studyId: number, body: FindingRequest) => api.post<Finding>(`/imaging-studies/${studyId}/findings`, body),
  finalizeFinding: (id: number) => api.post<Finding>(`/imaging-findings/${id}/finalize`),
  findingInError: (id: number, reason: string) => api.post<Finding>(`/imaging-findings/${id}/entered-in-error`, { reason }),
  orders: (pid: number) => api.get<ImagingOrder[]>(`/patients/${pid}/imaging-orders`),
  createOrder: (pid: number, body: { modality: string; procedureDescription: string; clinicalIndication?: string; priority?: string; bodyPart?: string }) =>
    api.post<ImagingOrder>(`/patients/${pid}/imaging-orders`, body),
  cancelOrder: (id: number) => api.post<ImagingOrder>(`/imaging-orders/${id}/cancel`),
  viewerSession: (studyIds: number[]) => api.post<ViewerSession>('/imaging/viewer-sessions', { studyIds }),
};

export const imagingKeys = {
  studies: (pid: number) => ['patients', pid, 'imaging-studies'] as const,
  study: (id: number) => ['imaging-study', id] as const,
  orders: (pid: number) => ['patients', pid, 'imaging-orders'] as const,
};

export function useStudies(pid: number) {
  return useQuery({ queryKey: imagingKeys.studies(pid), queryFn: () => imagingApi.studies(pid) });
}

export function useStudy(id: number | null) {
  return useQuery({ queryKey: imagingKeys.study(id ?? 0), queryFn: () => imagingApi.study(id!), enabled: !!id });
}

export function useImagingOrders(pid: number, enabled = true) {
  return useQuery({ queryKey: imagingKeys.orders(pid), queryFn: () => imagingApi.orders(pid), enabled });
}

/**
 * Opens the viewer for the given studies. The tab is opened synchronously (so pop-up blockers
 * allow it) and navigated once the backend has authorized the studies and set the viewer cookie.
 */
export async function openViewer(studyIds: number[]): Promise<ViewerSession> {
  const tab = window.open('about:blank', '_blank');
  try {
    const session = await imagingApi.viewerSession(studyIds);
    if (tab) {
      tab.opener = null;
      tab.location.href = session.viewerUrl;
    } else {
      window.location.href = session.viewerUrl;
    }
    return session;
  } catch (e) {
    tab?.close();
    throw e;
  }
}
