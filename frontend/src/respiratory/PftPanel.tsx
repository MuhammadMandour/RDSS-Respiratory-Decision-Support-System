import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Switch,
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
import AddIcon from '@mui/icons-material/Add';
import ReportOutlinedIcon from '@mui/icons-material/ReportOutlined';
import { useMutation } from '@tanstack/react-query';
import { respiratoryApi, useInvalidatePatient, usePfts, type Pft } from './api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDateTime, localInputToIso, numOrUndefined, orDash, toLocalInputValue } from '../shared/format';
import { EnteredInErrorDialog } from './ObservationsPanel';

export function PftPanel({ patientId, encounterId, canWrite }: { patientId: number; encounterId?: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = usePfts(patientId);
  const [adding, setAdding] = useState(false);
  const [errorTarget, setErrorTarget] = useState<Pft | null>(null);
  const rows = encounterId ? data?.filter((p) => p.encounterId === encounterId) : data;

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Pulmonary function tests</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setAdding(true)}>Add result</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {rows && rows.length === 0 && <EmptyState title="No lung function results" />}
      {rows && rows.length > 0 && (
        <TableContainer>
          <Table size="small" aria-label="Pulmonary function tests">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Post-BD</TableCell>
                <TableCell>FEV₁ (L)</TableCell>
                <TableCell>FVC (L)</TableCell>
                <TableCell>FEV₁/FVC</TableCell>
                <TableCell>FEV₁ %pred</TableCell>
                <TableCell>DLCO %pred</TableCell>
                <TableCell>Quality</TableCell>
                <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>Interpretation</TableCell>
                {canWrite && <TableCell />}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((p) => (
                <TableRow key={p.id} sx={p.enteredInError ? { opacity: 0.55, '& td': { textDecoration: 'line-through' } } : undefined}>
                  <TableCell>
                    {formatDateTime(p.performedAt)}
                    {p.enteredInError && <Chip size="small" label="Entered in error" color="warning" sx={{ ml: 1 }} />}
                  </TableCell>
                  <TableCell>{p.postBronchodilator ? 'Yes' : 'No'}</TableCell>
                  <TableCell>{orDash(p.fev1L)}</TableCell>
                  <TableCell>{orDash(p.fvcL)}</TableCell>
                  <TableCell>{p.fev1FvcRatio != null ? p.fev1FvcRatio.toFixed(2) : '—'}</TableCell>
                  <TableCell>{orDash(p.fev1PercentPredicted)}</TableCell>
                  <TableCell>{orDash(p.dlcoPercentPredicted)}</TableCell>
                  <TableCell>{orDash(p.qualityGrade)}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' }, maxWidth: 320 }}>{orDash(p.interpretation)}</TableCell>
                  {canWrite && (
                    <TableCell>
                      {!p.enteredInError && (
                        <Tooltip title="Mark as entered in error">
                          <IconButton size="small" aria-label="Mark as entered in error" onClick={() => setErrorTarget(p)}>
                            <ReportOutlinedIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {adding && <PftDialog patientId={patientId} encounterId={encounterId} onClose={() => setAdding(false)} />}
      {errorTarget && (
        <EnteredInErrorDialog
          title="Mark result as entered in error"
          patientId={patientId}
          onClose={() => setErrorTarget(null)}
          action={(reason) => respiratoryApi.pftInError(errorTarget.id, reason)}
        />
      )}
    </Paper>
  );
}

const FIELDS = ['performedAt', 'fev1L', 'fvcL', 'fev1PercentPredicted', 'fvcPercentPredicted', 'dlcoPercentPredicted', 'bronchodilatorResponsePercent', 'bronchodilatorResponseMl', 'qualityGrade', 'interpretation'];

function PftDialog({ patientId, encounterId, onClose }: { patientId: number; encounterId?: number; onClose: () => void }) {
  const [f, setF] = useState<Record<string, string>>({ performedAt: toLocalInputValue(new Date()) });
  const [postBd, setPostBd] = useState(true);
  const invalidate = useInvalidatePatient(patientId);
  const m = useMutation({
    mutationFn: () =>
      respiratoryApi.recordPft(patientId, {
        encounterId,
        performedAt: localInputToIso(f.performedAt ?? ''),
        postBronchodilator: postBd,
        fev1L: numOrUndefined(f.fev1L ?? ''),
        fvcL: numOrUndefined(f.fvcL ?? ''),
        fev1PercentPredicted: numOrUndefined(f.fev1PercentPredicted ?? ''),
        fvcPercentPredicted: numOrUndefined(f.fvcPercentPredicted ?? ''),
        dlcoPercentPredicted: numOrUndefined(f.dlcoPercentPredicted ?? ''),
        bronchodilatorResponsePercent: numOrUndefined(f.bronchodilatorResponsePercent ?? ''),
        bronchodilatorResponseMl: numOrUndefined(f.bronchodilatorResponseMl ?? ''),
        qualityGrade: f.qualityGrade || undefined,
        interpretation: f.interpretation?.trim() || undefined,
      }),
    onSuccess: () => { void invalidate(); onClose(); },
  });
  const errs = fieldErrors(m.error);
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  const num = (k: string, label: string, step = 1) => (
    <TextField label={label} type="number" value={f[k] ?? ''} onChange={set(k)} error={!!errs[k]} helperText={errs[k]} slotProps={{ htmlInput: { step } }} />
  );
  const ratio = Number(f.fev1L) > 0 && Number(f.fvcL) > 0 ? (Number(f.fev1L) / Number(f.fvcL)).toFixed(2) : null;
  const top = formLevelError(m.error, FIELDS);

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); m.mutate(); }} noValidate>
        <DialogTitle>Add pulmonary function result</DialogTitle>
        <DialogContent dividers>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <FormGrid>
            <TextField label="Performed at" type="datetime-local" value={f.performedAt} onChange={set('performedAt')} required
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: toLocalInputValue(new Date()) } }} />
            <FormControlLabel control={<Switch checked={postBd} onChange={(e) => setPostBd(e.target.checked)} />} label="Post-bronchodilator" />
            {num('fev1L', 'FEV₁ (L)', 0.01)}
            {num('fvcL', 'FVC (L)', 0.01)}
            <FullRow>
              <Typography variant="body2" color="text.secondary">
                Calculated FEV₁/FVC: <strong>{ratio ?? '—'}</strong>
              </Typography>
            </FullRow>
            {num('fev1PercentPredicted', 'FEV₁ % predicted')}
            {num('fvcPercentPredicted', 'FVC % predicted')}
            {num('dlcoPercentPredicted', 'DLCO % predicted')}
            <TextField select label="Quality grade" value={f.qualityGrade ?? ''} onChange={set('qualityGrade')}>
              <MenuItem value="">Not graded</MenuItem>
              {['A', 'B', 'C', 'D', 'E', 'F', 'U'].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
            </TextField>
            {num('bronchodilatorResponsePercent', 'BD response (% FEV₁)', 0.1)}
            {num('bronchodilatorResponseMl', 'BD response (mL)')}
            <FullRow>
              <TextField label="Interpretation" value={f.interpretation ?? ''} onChange={set('interpretation')} multiline minRows={2} fullWidth />
            </FullRow>
          </FormGrid>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={m.isPending}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
