import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CloudUploadOutlinedIcon from '@mui/icons-material/CloudUploadOutlined';
import LinkIcon from '@mui/icons-material/Link';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import CompareIcon from '@mui/icons-material/Compare';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { imagingApi, imagingKeys, openViewer, useImagingOrders, useStudies, type UploadResult } from './api';
import { StudyDetailDialog } from './StudyDetailDialog';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, formatDateTime, humanize } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';

/** Imaging tab: studies (view, compare, findings), upload/link and orders. Read-only for patients. */
export function ImagingPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const studies = useStudies(patientId);
  const [selected, setSelected] = useState<number[]>([]);
  const [detail, setDetail] = useState<number | null>(null);
  const [uploading, setUploading] = useState(false);
  const [linking, setLinking] = useState(false);
  const [viewerError, setViewerError] = useState<unknown>(null);

  const view = async (ids: number[]) => {
    setViewerError(null);
    try {
      await openViewer(ids);
    } catch (e) {
      setViewerError(e);
    }
  };
  const toggle = (id: number) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id].slice(-4)));

  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ justifyContent: 'space-between', mb: 2 }}>
          <Typography variant="h3" component="h2">Imaging studies</Typography>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <Button startIcon={<CompareIcon />} disabled={selected.length < 2} onClick={() => void view(selected)}>
              Compare selected ({selected.length})
            </Button>
            {canWrite && <Button startIcon={<LinkIcon />} onClick={() => setLinking(true)}>Link existing</Button>}
            {canWrite && <Button variant="contained" startIcon={<CloudUploadOutlinedIcon />} onClick={() => setUploading(true)}>Upload DICOM</Button>}
          </Stack>
        </Stack>
        <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
          Images open in the integrated viewer (OHIF). Viewer processing such as windowing, MPR and 3D rendering is display-only; the original DICOM data is unchanged.
          The viewer does not make diagnoses.
        </Alert>
        {viewerError != null && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(viewerError).message}</Alert>}
        {studies.isPending && <LoadingState />}
        {studies.error && <ErrorState error={studies.error} onRetry={() => void studies.refetch()} />}
        {studies.data?.length === 0 && <EmptyState title="No imaging studies" />}
        {!!studies.data?.length && (
          <TableContainer>
            <Table size="small" aria-label="Imaging studies">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox" />
                  <TableCell>Date</TableCell>
                  <TableCell>Modality</TableCell>
                  <TableCell>Description</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Series / images</TableCell>
                  <TableCell>Findings</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {studies.data.map((s) => (
                  <TableRow key={s.id} selected={selected.includes(s.id)}>
                    <TableCell padding="checkbox">
                      <Checkbox checked={selected.includes(s.id)} onChange={() => toggle(s.id)} slotProps={{ input: { 'aria-label': `Select study ${s.id} for comparison` } }} />
                    </TableCell>
                    <TableCell>{formatDate(s.studyDate)}</TableCell>
                    <TableCell>{s.modalities ?? '—'}</TableCell>
                    <TableCell>
                      {s.studyDescription ?? '—'}
                      {s.patientIdMismatch && (
                        <Tooltip title="The DICOM PatientID differs from this patient's MRN. Verify the association.">
                          <Chip size="small" color="warning" icon={<WarningAmberIcon />} label="ID mismatch" sx={{ ml: 1 }} />
                        </Tooltip>
                      )}
                    </TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{s.numberOfSeries} / {s.numberOfInstances}</TableCell>
                    <TableCell>{s.findingCount}</TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                        <Button size="small" onClick={() => setDetail(s.id)}>Details</Button>
                        <Button size="small" variant="outlined" endIcon={<OpenInNewIcon />} onClick={() => void view([s.id])}>View</Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
      {canWrite && <OrdersPanel patientId={patientId} />}
      {detail && <StudyDetailDialog studyId={detail} canWrite={canWrite} onClose={() => setDetail(null)} onView={() => void view([detail])} />}
      {uploading && <UploadDialog patientId={patientId} onClose={() => setUploading(false)} />}
      {linking && <LinkDialog patientId={patientId} onClose={() => setLinking(false)} />}
    </Stack>
  );
}

function UploadDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [orderId, setOrderId] = useState<number | ''>('');
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<UploadResult | null>(null);
  const orders = useImagingOrders(patientId);
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => imagingApi.upload(patientId, files, { orderId: orderId || undefined }, setProgress),
    onSuccess: (r) => {
      setResult(r);
      void qc.invalidateQueries({ queryKey: imagingKeys.studies(patientId) });
      void qc.invalidateQueries({ queryKey: imagingKeys.orders(patientId) });
    },
  });
  const sizeMb = files.reduce((n, f) => n + f.size, 0) / 1_048_576;
  return (
    <Dialog open onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Upload DICOM files</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Alert severity="warning">Use synthetic or properly de-identified data only in this development system.</Alert>
          <Button component="label" variant="outlined" startIcon={<CloudUploadOutlinedIcon />} disabled={m.isPending}>
            Choose files
            <input hidden type="file" multiple accept=".dcm,application/dicom" onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
          </Button>
          <Typography variant="body2" color="text.secondary">
            {files.length ? `${files.length} file(s), ${sizeMb.toFixed(1)} MB` : 'No files selected (DICOM Part-10 .dcm files)'}
          </Typography>
          <TextField select size="small" label="Fulfils imaging order (optional)" value={orderId} onChange={(e) => setOrderId(e.target.value === '' ? '' : Number(e.target.value))}>
            <MenuItem value="">None</MenuItem>
            {orders.data?.filter((o) => o.status === 'REQUESTED' || o.status === 'SCHEDULED').map((o) => (
              <MenuItem key={o.id} value={o.id}>{o.accessionNumber} · {o.modality} · {o.procedureDescription}</MenuItem>
            ))}
          </TextField>
          {m.isPending && <LinearProgress variant={progress < 100 ? 'determinate' : 'indeterminate'} value={progress} />}
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          {result && (
            <Alert severity={result.warnings.length ? 'warning' : 'success'}>
              Stored {result.instancesStored} new instance(s) ({result.instancesAlreadyPresent} already present) in {result.studies.length} stud{result.studies.length === 1 ? 'y' : 'ies'}.
              {result.warnings.map((w) => <Box key={w} component="div">{w}</Box>)}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={m.isPending}>{result ? 'Close' : 'Cancel'}</Button>
        {!result && <Button variant="contained" disabled={!files.length || m.isPending} onClick={() => m.mutate()}>Upload</Button>}
      </DialogActions>
    </Dialog>
  );
}

function LinkDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [uid, setUid] = useState('');
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => imagingApi.link(patientId, uid.trim()),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: imagingKeys.studies(patientId) }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Link a study from the imaging archive</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          For studies already received by the archive (for example sent by a modality via DICOM C-STORE).
        </Typography>
        {m.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(m.error).message}</Alert>}
        <TextField label="StudyInstanceUID" value={uid} onChange={(e) => setUid(e.target.value)} fullWidth autoFocus
          error={!!uid && !/^[0-9.]{1,64}$/.test(uid.trim())} helperText="Digits and dots only" />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!/^[0-9.]{1,64}$/.test(uid.trim()) || m.isPending} onClick={() => m.mutate()}>Link</Button>
      </DialogActions>
    </Dialog>
  );
}

function OrdersPanel({ patientId }: { patientId: number }) {
  const orders = useImagingOrders(patientId);
  const [creating, setCreating] = useState(false);
  const qc = useQueryClient();
  const cancel = useMutation({ mutationFn: imagingApi.cancelOrder, onSuccess: () => qc.invalidateQueries({ queryKey: imagingKeys.orders(patientId) }) });
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
        <Typography variant="h3" component="h2">Imaging orders</Typography>
        <Button onClick={() => setCreating(true)}>New order</Button>
      </Stack>
      {orders.isPending && <LoadingState />}
      {(orders.error || cancel.error) && <ErrorState error={orders.error ?? cancel.error} />}
      {orders.data?.length === 0 && <EmptyState title="No imaging orders" />}
      {!!orders.data?.length && (
        <Table size="small" aria-label="Imaging orders">
          <TableHead>
            <TableRow>
              <TableCell>Accession</TableCell>
              <TableCell>Procedure</TableCell>
              <TableCell>Priority</TableCell>
              <TableCell>Ordered</TableCell>
              <TableCell>Status</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {orders.data.map((o) => (
              <TableRow key={o.id}>
                <TableCell>{o.accessionNumber}</TableCell>
                <TableCell>{o.modality} · {o.procedureDescription}</TableCell>
                <TableCell>{humanize(o.priority)}</TableCell>
                <TableCell>{formatDateTime(o.orderedAt)}</TableCell>
                <TableCell><Chip size="small" variant="outlined" label={humanize(o.status)} color={o.status === 'COMPLETED' ? 'success' : 'default'} /></TableCell>
                <TableCell>{(o.status === 'REQUESTED' || o.status === 'SCHEDULED') && <Button size="small" color="warning" onClick={() => cancel.mutate(o.id)}>Cancel</Button>}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {creating && <OrderDialog patientId={patientId} onClose={() => setCreating(false)} />}
    </Paper>
  );
}

function OrderDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [f, setF] = useState({ modality: 'DX', procedureDescription: 'Chest radiograph PA', clinicalIndication: '', priority: 'ROUTINE' });
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => imagingApi.createOrder(patientId, { ...f, clinicalIndication: f.clinicalIndication || undefined }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: imagingKeys.orders(patientId) }); onClose(); },
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>New imaging order</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          <TextField select label="Modality" value={f.modality} onChange={set('modality')}>
            {['DX', 'CR', 'CT', 'MR', 'US', 'PT', 'NM'].map((x) => <MenuItem key={x} value={x}>{x}</MenuItem>)}
          </TextField>
          <TextField label="Procedure" value={f.procedureDescription} onChange={set('procedureDescription')} required />
          <TextField label="Clinical indication" value={f.clinicalIndication} onChange={set('clinicalIndication')} multiline minRows={2} />
          <TextField select label="Priority" value={f.priority} onChange={set('priority')}>
            {['ROUTINE', 'URGENT', 'STAT'].map((x) => <MenuItem key={x} value={x}>{humanize(x)}</MenuItem>)}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.procedureDescription.trim() || m.isPending} onClick={() => m.mutate()}>Order</Button>
      </DialogActions>
    </Dialog>
  );
}
