import { useState } from 'react';
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { diagnosesApi, useDiagnoses, useIcd10Search, type Diagnosis, type DiagnosisType, type Icd10Code } from './api';
import { useEncounters } from '../encounters/api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, formatDateTime, humanize } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';
import { EnteredInErrorDialog } from '../respiratory/ObservationsPanel';

export function DiagnosesPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = useDiagnoses(patientId);
  const [adding, setAdding] = useState(false);
  const [errorTarget, setErrorTarget] = useState<Diagnosis | null>(null);
  const qc = useQueryClient();
  const resolve = useMutation({ mutationFn: diagnosesApi.resolve, onSuccess: () => qc.invalidateQueries({ queryKey: ['patients', patientId, 'diagnoses'] }) });

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Diagnoses (ICD-10-CM)</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setAdding(true)}>Add diagnosis</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {(error || resolve.error) && <ErrorState error={error ?? resolve.error} onRetry={() => void refetch()} />}
      {data?.length === 0 && <EmptyState title="No coded diagnoses" />}
      {!!data?.length && (
        <Table size="small" aria-label="Diagnoses">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Type</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Onset</TableCell>
              <TableCell>Status</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Recorded</TableCell>
              {canWrite && <TableCell />}
            </TableRow>
          </TableHead>
          <TableBody>
            {data.map((d) => (
              <TableRow key={d.id} sx={d.status === 'ENTERED_IN_ERROR' ? { opacity: 0.55, '& td': { textDecoration: 'line-through' } } : undefined}>
                <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{d.code}</TableCell>
                <TableCell>{d.description}<Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>ICD-10-CM {d.codeVersion}{d.encounterId ? ` · encounter #${d.encounterId}` : ''}</Typography></TableCell>
                <TableCell>{humanize(d.type)}</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDate(d.onsetDate)}</TableCell>
                <TableCell><Chip size="small" variant="outlined" label={humanize(d.status)} color={d.status === 'ACTIVE' ? 'primary' : 'default'} /></TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(d.recordedAt)}<Typography variant="caption" sx={{ display: 'block' }} color="text.secondary">{d.recordedByName}</Typography></TableCell>
                {canWrite && (
                  <TableCell>
                    {d.status === 'ACTIVE' && (
                      <Stack direction="row" spacing={1}>
                        <Button size="small" onClick={() => resolve.mutate(d.id)}>Resolve</Button>
                        <Button size="small" color="warning" onClick={() => setErrorTarget(d)}>Error</Button>
                      </Stack>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {adding && <AddDiagnosisDialog patientId={patientId} onClose={() => setAdding(false)} />}
      {errorTarget && (
        <EnteredInErrorDialog title="Mark diagnosis as entered in error" patientId={patientId} onClose={() => setErrorTarget(null)}
          action={(reason) => diagnosesApi.enteredInError(errorTarget.id, reason)} />
      )}
    </Paper>
  );
}

/** ICD-10 code search box (code prefix or description). */
export function Icd10Picker({ value, onChange, label = 'ICD-10-CM code' }: { value: Icd10Code | null; onChange: (c: Icd10Code | null) => void; label?: string }) {
  const [input, setInput] = useState('');
  const search = useIcd10Search(input);
  return (
    <Autocomplete
      options={search.data?.content ?? []}
      value={value}
      onChange={(_, v) => onChange(v)}
      inputValue={input}
      onInputChange={(_, v) => setInput(v)}
      filterOptions={(x) => x}
      loading={search.isFetching}
      getOptionLabel={(o) => `${o.code} — ${o.description}`}
      isOptionEqualToValue={(a, b) => a.code === b.code}
      renderInput={(params) => <TextField {...params} label={label} placeholder="e.g. J44.1 or asthma" />}
    />
  );
}

function AddDiagnosisDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [code, setCode] = useState<Icd10Code | null>(null);
  const [type, setType] = useState<DiagnosisType>('PRIMARY');
  const [encounterId, setEncounterId] = useState<number | ''>('');
  const [onset, setOnset] = useState('');
  const [notes, setNotes] = useState('');
  const encounters = useEncounters(patientId);
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => diagnosesApi.record(patientId, { code: code!.code, type, encounterId: encounterId || undefined, onsetDate: onset || undefined, notes: notes || undefined }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['patients', patientId, 'diagnoses'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Add diagnosis</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          <Icd10Picker value={code} onChange={setCode} />
          <TextField select label="Type" value={type} onChange={(e) => setType(e.target.value as DiagnosisType)}>
            {(['PRIMARY', 'SECONDARY', 'COMORBIDITY'] as const).map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
          </TextField>
          <TextField select label="Encounter (optional)" value={encounterId} onChange={(e) => setEncounterId(e.target.value === '' ? '' : Number(e.target.value))}>
            <MenuItem value="">None</MenuItem>
            {encounters.data?.content.filter((e) => e.status !== 'SUPERSEDED').map((e) => (
              <MenuItem key={e.id} value={e.id}>#{e.id} · {formatDateTime(e.startedAt)} · {e.chiefComplaint ?? ''}</MenuItem>
            ))}
          </TextField>
          <TextField label="Onset date" type="date" value={onset} onChange={(e) => setOnset(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!code || m.isPending} onClick={() => m.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}
