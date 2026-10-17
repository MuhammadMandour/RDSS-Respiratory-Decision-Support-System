import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
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
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { encountersApi, useEncounters, type EncounterStatus, type EncounterType } from './api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDateTime, humanize } from '../shared/format';

export const encounterStatusColor = (s: EncounterStatus) =>
  ({ IN_PROGRESS: 'warning', FINALIZED: 'success', SUPERSEDED: 'default' } as const)[s];

export function EncountersPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = useEncounters(patientId);
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Clinical encounters</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setCreating(true)}>New encounter</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {data && data.content.length === 0 && <EmptyState title="No encounters documented" />}
      {data && data.content.length > 0 && (
        <TableContainer>
          <Table size="small" aria-label="Encounters">
            <TableHead>
              <TableRow>
                <TableCell>Started</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Chief complaint</TableCell>
                <TableCell>Clinician</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((e) => (
                <TableRow key={e.id} hover sx={{ cursor: 'pointer' }} tabIndex={0}
                  onClick={() => navigate(`/encounters/${e.id}`)} onKeyDown={(k) => k.key === 'Enter' && navigate(`/encounters/${e.id}`)}>
                  <TableCell>{formatDateTime(e.startedAt)}</TableCell>
                  <TableCell>{humanize(e.type)}</TableCell>
                  <TableCell>
                    {e.chiefComplaint ?? '—'}
                    {e.amendsEncounterId && <Chip size="small" label={`Amends #${e.amendsEncounterId}`} sx={{ ml: 1 }} />}
                  </TableCell>
                  <TableCell>{e.clinicianName ?? '—'}</TableCell>
                  <TableCell><Chip size="small" label={humanize(e.status)} color={encounterStatusColor(e.status)} variant="outlined" /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {creating && <NewEncounterDialog patientId={patientId} onClose={() => setCreating(false)} />}
    </Paper>
  );
}

function NewEncounterDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [type, setType] = useState<EncounterType>('OUTPATIENT');
  const [complaint, setComplaint] = useState('');
  const navigate = useNavigate();
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => encountersApi.create(patientId, type, complaint.trim() || undefined),
    onSuccess: (e) => {
      void qc.invalidateQueries({ queryKey: ['patients', patientId] });
      navigate(`/encounters/${e.id}`);
    },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Start encounter</DialogTitle>
      <DialogContent>
        {m.error && <ErrorState error={m.error} />}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Type" value={type} onChange={(e) => setType(e.target.value as EncounterType)}>
            {(['OUTPATIENT', 'INPATIENT', 'EMERGENCY', 'TELEHEALTH'] as const).map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
          </TextField>
          <TextField label="Chief complaint" value={complaint} onChange={(e) => setComplaint(e.target.value)} autoFocus />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => m.mutate()} disabled={m.isPending}>Start</Button>
      </DialogActions>
    </Dialog>
  );
}
