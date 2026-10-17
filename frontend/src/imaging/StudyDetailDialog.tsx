import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FINDING_TYPES, imagingApi, imagingKeys, useStudy, type Finding, type FindingRequest, type Laterality } from './api';
import { DetailList } from '../shared/components/DetailList';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, formatDateTime, humanize, orDash } from '../shared/format';
import { fieldErrors } from '../shared/forms';
import { toApiRequestError } from '../shared/api/client';

const sourceLabel: Record<string, string> = {
  CLINICIAN: 'Clinician-entered',
  RADIOLOGIST_REPORT: 'From radiologist report',
  DEMONSTRATION: 'Demonstration data',
  AI_UNVALIDATED: 'Unvalidated AI output',
};

export function StudyDetailDialog({ studyId, canWrite, onClose, onView }: { studyId: number; canWrite: boolean; onClose: () => void; onView: () => void }) {
  const { data, isPending, error } = useStudy(studyId);
  const qc = useQueryClient();
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: imagingKeys.study(studyId) });
    if (data) void qc.invalidateQueries({ queryKey: imagingKeys.studies(data.study.patientId) });
  };
  const finalize = useMutation({ mutationFn: imagingApi.finalizeFinding, onSuccess: refresh });
  const [errorTarget, setErrorTarget] = useState<Finding | null>(null);

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Imaging study</DialogTitle>
      <DialogContent dividers>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} />}
        {data && (
          <Stack spacing={2}>
            {data.study.patientIdMismatch && (
              <Alert severity="warning">DICOM PatientID "{data.dicomPatientId}" differs from the patient's MRN. Verify this study belongs to this patient.</Alert>
            )}
            <DetailList items={[
              { label: 'Study date', value: formatDate(data.study.studyDate) },
              { label: 'Description', value: orDash(data.study.studyDescription) },
              { label: 'Modalities', value: orDash(data.study.modalities) },
              { label: 'Accession', value: orDash(data.study.accessionNumber) },
              { label: 'Source', value: humanize(data.study.source) },
              { label: 'StudyInstanceUID', value: <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 12 }}>{data.study.studyInstanceUid}</Box> },
            ]} />
            <Typography variant="subtitle2">Series</Typography>
            <Table size="small" aria-label="Series">
              <TableHead>
                <TableRow><TableCell>#</TableCell><TableCell>Modality</TableCell><TableCell>Description</TableCell><TableCell>Body part</TableCell><TableCell>Images</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {data.series.map((s) => (
                  <TableRow key={s.id}>
                    <TableCell>{orDash(s.seriesNumber)}</TableCell><TableCell>{orDash(s.modality)}</TableCell>
                    <TableCell>{orDash(s.description)}</TableCell><TableCell>{orDash(s.bodyPart)}</TableCell><TableCell>{s.numberOfInstances}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Divider />
            <Typography variant="subtitle2">Findings</Typography>
            {data.findings.length === 0 && <Typography color="text.secondary" variant="body2">No findings documented.</Typography>}
            {(finalize.error) && <ErrorState error={finalize.error} />}
            {data.findings.map((f) => (
              <Box key={f.id} sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1, opacity: f.status === 'ENTERED_IN_ERROR' ? 0.6 : 1 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.5 }}>
                  <Typography sx={{ fontWeight: 600, textDecoration: f.status === 'ENTERED_IN_ERROR' ? 'line-through' : 'none' }}>
                    {humanize(f.type)}{f.laterality !== 'NOT_APPLICABLE' ? ` · ${humanize(f.laterality)}` : ''}{f.sizeMm ? ` · ${f.sizeMm} mm` : ''}
                  </Typography>
                  <Chip size="small" label={humanize(f.status)} color={f.status === 'FINAL' ? 'success' : f.status === 'PRELIMINARY' ? 'warning' : 'default'} variant="outlined" />
                  <Chip size="small" label={sourceLabel[f.source]} variant="outlined" color={f.source === 'DEMONSTRATION' || f.source === 'AI_UNVALIDATED' ? 'warning' : 'default'} />
                </Stack>
                <Typography variant="body2">{f.description}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {f.location ? `${f.location} · ` : ''}{f.recordedByName} · {formatDateTime(f.recordedAt)}{f.errorReason ? ` · Entered in error: ${f.errorReason}` : ''}
                </Typography>
                {canWrite && f.status !== 'ENTERED_IN_ERROR' && (
                  <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                    {f.status === 'PRELIMINARY' && <Button size="small" onClick={() => finalize.mutate(f.id)}>Finalize</Button>}
                    <Button size="small" color="warning" onClick={() => setErrorTarget(f)}>Entered in error</Button>
                  </Stack>
                )}
              </Box>
            ))}
            {canWrite && <AddFinding studyId={studyId} onAdded={refresh} />}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button startIcon={<OpenInNewIcon />} onClick={onView}>Open in viewer</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
      {errorTarget && <ErrorDialog finding={errorTarget} onClose={() => setErrorTarget(null)} onDone={refresh} />}
    </Dialog>
  );
}

function AddFinding({ studyId, onAdded }: { studyId: number; onAdded: () => void }) {
  const empty = { type: 'PULMONARY_OPACITY', laterality: 'NOT_APPLICABLE', location: '', sizeMm: '', description: '', source: 'CLINICIAN', status: 'PRELIMINARY' };
  const [f, setF] = useState(empty);
  const m = useMutation({
    mutationFn: () => imagingApi.addFinding(studyId, {
      type: f.type as FindingRequest['type'], laterality: f.laterality as Laterality, location: f.location || undefined,
      sizeMm: f.sizeMm ? Number(f.sizeMm) : undefined, description: f.description, source: f.source as FindingRequest['source'],
      status: f.status as FindingRequest['status'],
    }),
    onSuccess: () => { setF(empty); onAdded(); },
  });
  const errs = fieldErrors(m.error);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  return (
    <Box>
      <Divider sx={{ mb: 2 }} />
      <Typography variant="subtitle2" sx={{ mb: 1 }}>Document a finding</Typography>
      {m.error && !Object.keys(errs).length && <Alert severity="error" sx={{ mb: 1 }}>{toApiRequestError(m.error).message}</Alert>}
      <FormGrid columns={3}>
        <TextField select size="small" label="Finding" value={f.type} onChange={set('type')}>
          {FINDING_TYPES.map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Laterality" value={f.laterality} onChange={set('laterality')}>
          {['NOT_APPLICABLE', 'LEFT', 'RIGHT', 'BILATERAL'].map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Size (mm)" type="number" value={f.sizeMm} onChange={set('sizeMm')} error={!!errs.sizeMm} helperText={errs.sizeMm} />
        <TextField size="small" label="Location" value={f.location} onChange={set('location')} />
        <TextField select size="small" label="Source" value={f.source} onChange={set('source')}>
          <MenuItem value="CLINICIAN">Clinician-entered</MenuItem>
          <MenuItem value="RADIOLOGIST_REPORT">From radiologist report</MenuItem>
          <MenuItem value="DEMONSTRATION">Demonstration data</MenuItem>
        </TextField>
        <TextField select size="small" label="Status" value={f.status} onChange={set('status')}>
          <MenuItem value="PRELIMINARY">Preliminary</MenuItem>
          <MenuItem value="FINAL">Final</MenuItem>
        </TextField>
        <FullRow>
          <TextField size="small" label="Description" value={f.description} onChange={set('description')} multiline minRows={2} fullWidth required error={!!errs.description} helperText={errs.description} />
        </FullRow>
      </FormGrid>
      <Button sx={{ mt: 1 }} variant="contained" disabled={!f.description.trim() || m.isPending} onClick={() => m.mutate()}>Add finding</Button>
    </Box>
  );
}

function ErrorDialog({ finding, onClose, onDone }: { finding: Finding; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const m = useMutation({ mutationFn: () => imagingApi.findingInError(finding.id, reason.trim()), onSuccess: () => { onDone(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Mark finding as entered in error</DialogTitle>
      <DialogContent>
        {m.error && <ErrorState error={m.error} />}
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required multiline minRows={2} autoFocus sx={{ mt: 1 }} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>Confirm</Button>
      </DialogActions>
    </Dialog>
  );
}
