import { useMemo, useState, type FormEvent } from 'react';
import {
  Alert,
  Box,
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
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts';
import { respiratoryApi, useInvalidatePatient, useObservations, useRecordObservation, type Consciousness, type Observation } from './api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDateTime, humanize, localInputToIso, numOrUndefined, orDash, toLocalInputValue } from '../shared/format';

export function ObservationsPanel({ patientId, encounterId, canWrite }: { patientId: number; encounterId?: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = useObservations(patientId, encounterId);
  const [adding, setAdding] = useState(false);
  const [errorTarget, setErrorTarget] = useState<Observation | null>(null);

  const chartData = useMemo(
    () =>
      (data?.content ?? [])
        .filter((o) => !o.enteredInError)
        .slice()
        .reverse()
        .map((o) => ({ t: new Date(o.observedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }), SpO2: o.spo2, RR: o.respiratoryRate, HR: o.heartRate })),
    [data],
  );

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Observations</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setAdding(true)}>Record vitals</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {data && data.content.length === 0 && <EmptyState title="No observations recorded" />}
      {chartData.length > 1 && (
        <Box sx={{ height: 220, mb: 2 }} role="img" aria-label="Trend of SpO2, respiratory rate and heart rate">
          <ResponsiveContainer>
            <LineChart data={chartData} margin={{ top: 8, right: 16, left: -16, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e1e5ea" />
              <XAxis dataKey="t" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} />
              <ChartTooltip />
              <Legend />
              <Line type="monotone" dataKey="SpO2" stroke="#0b5c78" strokeWidth={2} dot={{ r: 3 }} connectNulls />
              <Line type="monotone" dataKey="RR" stroke="#c2410c" strokeWidth={2} dot={{ r: 3 }} connectNulls />
              <Line type="monotone" dataKey="HR" stroke="#6b7280" strokeWidth={2} dot={{ r: 3 }} connectNulls />
            </LineChart>
          </ResponsiveContainer>
        </Box>
      )}
      {data && data.content.length > 0 && (
        <TableContainer>
          <Table size="small" aria-label="Observations">
            <TableHead>
              <TableRow>
                <TableCell>Time</TableCell>
                <TableCell>RR</TableCell>
                <TableCell>SpO₂ %</TableCell>
                <TableCell>O₂</TableCell>
                <TableCell>HR</TableCell>
                <TableCell>BP</TableCell>
                <TableCell>Temp °C</TableCell>
                <TableCell>ACVPU</TableCell>
                <TableCell>By</TableCell>
                {canWrite && <TableCell />}
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((o) => (
                <TableRow key={o.id} sx={o.enteredInError ? { opacity: 0.55, '& td': { textDecoration: 'line-through' } } : undefined}>
                  <TableCell>
                    {formatDateTime(o.observedAt)}
                    {o.enteredInError && (
                      <Tooltip title={`Entered in error: ${o.errorReason ?? ''}`}>
                        <Chip size="small" label="Entered in error" color="warning" sx={{ ml: 1, textDecoration: 'none' }} />
                      </Tooltip>
                    )}
                  </TableCell>
                  <TableCell>{orDash(o.respiratoryRate)}</TableCell>
                  <TableCell>{orDash(o.spo2)}</TableCell>
                  <TableCell>{o.onSupplementalOxygen ? `Yes${o.oxygenFlowLpm ? ` ${o.oxygenFlowLpm} L/min` : ''}` : o.onSupplementalOxygen === false ? 'Air' : '—'}</TableCell>
                  <TableCell>{orDash(o.heartRate)}</TableCell>
                  <TableCell>{o.systolicBp ? `${o.systolicBp}/${o.diastolicBp ?? '—'}` : '—'}</TableCell>
                  <TableCell>{orDash(o.temperatureC)}</TableCell>
                  <TableCell>{humanize(o.consciousness)}</TableCell>
                  <TableCell>{o.recordedByName ?? '—'}</TableCell>
                  {canWrite && (
                    <TableCell>
                      {!o.enteredInError && (
                        <Tooltip title="Mark as entered in error">
                          <IconButton size="small" aria-label="Mark as entered in error" onClick={() => setErrorTarget(o)}>
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
      {adding && <ObservationDialog patientId={patientId} encounterId={encounterId} onClose={() => setAdding(false)} />}
      {errorTarget && (
        <EnteredInErrorDialog
          title="Mark observation as entered in error"
          onClose={() => setErrorTarget(null)}
          patientId={patientId}
          action={(reason) => respiratoryApi.observationInError(errorTarget.id, reason)}
        />
      )}
    </Paper>
  );
}

const FIELDS = ['observedAt', 'respiratoryRate', 'heartRate', 'systolicBp', 'diastolicBp', 'temperatureC', 'spo2', 'oxygenFlowLpm', 'fio2Percent', 'consciousness', 'peakFlowLpm', 'notes'];

function ObservationDialog({ patientId, encounterId, onClose }: { patientId: number; encounterId?: number; onClose: () => void }) {
  const [f, setF] = useState<Record<string, string>>({ observedAt: toLocalInputValue(new Date()), consciousness: 'ALERT' });
  const [onO2, setOnO2] = useState(false);
  const record = useRecordObservation(patientId);
  const errs = fieldErrors(record.error);
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  const num = (k: string, label: string, min: number, max: number, step = 1) => (
    <TextField label={label} type="number" value={f[k] ?? ''} onChange={set(k)} error={!!errs[k]} helperText={errs[k]}
      slotProps={{ htmlInput: { min, max, step } }} />
  );

  const submit = (e: FormEvent) => {
    e.preventDefault();
    record.mutate(
      {
        encounterId,
        observedAt: localInputToIso(f.observedAt ?? ''),
        respiratoryRate: numOrUndefined(f.respiratoryRate ?? ''),
        heartRate: numOrUndefined(f.heartRate ?? ''),
        systolicBp: numOrUndefined(f.systolicBp ?? ''),
        diastolicBp: numOrUndefined(f.diastolicBp ?? ''),
        temperatureC: numOrUndefined(f.temperatureC ?? ''),
        spo2: numOrUndefined(f.spo2 ?? ''),
        onSupplementalOxygen: onO2,
        oxygenFlowLpm: onO2 ? numOrUndefined(f.oxygenFlowLpm ?? '') : undefined,
        fio2Percent: onO2 ? numOrUndefined(f.fio2Percent ?? '') : undefined,
        consciousness: (f.consciousness || undefined) as Consciousness | undefined,
        peakFlowLpm: numOrUndefined(f.peakFlowLpm ?? ''),
        notes: f.notes?.trim() || undefined,
      },
      { onSuccess: onClose },
    );
  };

  const top = formLevelError(record.error, FIELDS);
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={submit} noValidate>
        <DialogTitle>Record observations</DialogTitle>
        <DialogContent dividers>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <FormGrid>
            <FullRow>
              <TextField label="Observed at" type="datetime-local" value={f.observedAt} onChange={set('observedAt')} fullWidth required
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: toLocalInputValue(new Date()) } }} error={!!errs.observedAt} helperText={errs.observedAt} />
            </FullRow>
            {num('respiratoryRate', 'Respiratory rate (/min)', 0, 100)}
            {num('spo2', 'SpO₂ (%)', 40, 100)}
            {num('heartRate', 'Heart rate (bpm)', 0, 300)}
            {num('temperatureC', 'Temperature (°C)', 25, 45, 0.1)}
            {num('systolicBp', 'Systolic BP (mmHg)', 30, 300)}
            {num('diastolicBp', 'Diastolic BP (mmHg)', 10, 200)}
            <TextField select label="Consciousness (ACVPU)" value={f.consciousness ?? ''} onChange={set('consciousness')}>
              <MenuItem value="">Not assessed</MenuItem>
              {['ALERT', 'CONFUSION', 'VOICE', 'PAIN', 'UNRESPONSIVE'].map((c) => <MenuItem key={c} value={c}>{humanize(c)}</MenuItem>)}
            </TextField>
            {num('peakFlowLpm', 'Peak flow (L/min)', 30, 1000)}
            <FullRow>
              <FormControlLabel control={<Switch checked={onO2} onChange={(e) => setOnO2(e.target.checked)} />} label="On supplemental oxygen" />
            </FullRow>
            {onO2 && num('oxygenFlowLpm', 'O₂ flow (L/min)', 0, 80, 0.5)}
            {onO2 && num('fio2Percent', 'FiO₂ (%)', 21, 100)}
            <FullRow>
              <TextField label="Notes" value={f.notes ?? ''} onChange={set('notes')} multiline minRows={2} fullWidth />
            </FullRow>
          </FormGrid>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={record.isPending}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function EnteredInErrorDialog({
  title,
  onClose,
  patientId,
  action,
}: {
  title: string;
  onClose: () => void;
  patientId: number;
  action: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState('');
  const invalidate = useInvalidatePatient(patientId);
  const m = useMutation({ mutationFn: action, onSuccess: () => { void invalidate(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          The original entry is kept for the audit trail and shown struck through.
        </Typography>
        {m.error && <ErrorState error={m.error} />}
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required autoFocus multiline minRows={2} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate(reason.trim())}>
          Confirm
        </Button>
      </DialogActions>
    </Dialog>
  );
}
