import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Card,
  CardActions,
  CardContent,
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
import { encountersApi, usePrescriptions, useTreatmentPlans, type PlanStatus, type TreatmentPlan } from './api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { DetailList } from '../shared/components/DetailList';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDate, formatDateTime, humanize, orDash, toDateInputValue } from '../shared/format';

const statusColor = (s: string) => (s === 'ACTIVE' ? 'success' : 'default') as 'success' | 'default';

export function TreatmentPlansPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = useTreatmentPlans(patientId);
  const [editing, setEditing] = useState<TreatmentPlan | 'new' | null>(null);
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Treatment plans</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New plan</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {data && data.length === 0 && <EmptyState title="No treatment plans" />}
      <Stack spacing={2}>
        {data?.map((p) => (
          <Card key={p.id} variant="outlined">
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>{p.title}</Typography>
                <Chip size="small" label={humanize(p.status)} color={statusColor(p.status)} variant="outlined" />
              </Stack>
              <DetailList items={[
                { label: 'Goals', value: orDash(p.goals) },
                { label: 'Interventions', value: orDash(p.interventions) },
                { label: 'Follow-up', value: orDash(p.followUpInstructions) },
                { label: 'Start', value: formatDate(p.startDate) },
                { label: 'Review', value: formatDate(p.reviewDate) },
                { label: 'Author', value: orDash(p.createdByName) },
              ]} />
            </CardContent>
            {canWrite && p.status === 'ACTIVE' && (
              <CardActions><Button size="small" onClick={() => setEditing(p)}>Update</Button></CardActions>
            )}
          </Card>
        ))}
      </Stack>
      {editing && <PlanDialog patientId={patientId} plan={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Paper>
  );
}

function PlanDialog({ patientId, plan, onClose }: { patientId: number; plan?: TreatmentPlan; onClose: () => void }) {
  const [f, setF] = useState({
    title: plan?.title ?? '', goals: plan?.goals ?? '', interventions: plan?.interventions ?? '',
    followUpInstructions: plan?.followUpInstructions ?? '', startDate: plan?.startDate ?? toDateInputValue(new Date()),
    reviewDate: plan?.reviewDate ?? '', status: (plan?.status ?? 'ACTIVE') as PlanStatus,
  });
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => {
      const body = { ...f, goals: f.goals || undefined, interventions: f.interventions || undefined,
        followUpInstructions: f.followUpInstructions || undefined, reviewDate: f.reviewDate || undefined };
      return plan ? encountersApi.updatePlan(plan.id, { ...body, version: plan.version }) : encountersApi.createPlan(patientId, body);
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['patients', patientId] }); onClose(); },
  });
  const errs = fieldErrors(m.error);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  const top = formLevelError(m.error, Object.keys(f));
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); m.mutate(); }} noValidate>
        <DialogTitle>{plan ? 'Update treatment plan' : 'New treatment plan'}</DialogTitle>
        <DialogContent dividers>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <FormGrid>
            <FullRow><TextField label="Title" value={f.title} onChange={set('title')} required fullWidth error={!!errs.title} helperText={errs.title} /></FullRow>
            <FullRow><TextField label="Goals" value={f.goals} onChange={set('goals')} multiline minRows={2} fullWidth /></FullRow>
            <FullRow><TextField label="Interventions" value={f.interventions} onChange={set('interventions')} multiline minRows={2} fullWidth /></FullRow>
            <FullRow><TextField label="Follow-up instructions" value={f.followUpInstructions} onChange={set('followUpInstructions')} multiline minRows={2} fullWidth /></FullRow>
            <TextField label="Start date" type="date" value={f.startDate} onChange={set('startDate')} slotProps={{ inputLabel: { shrink: true } }} required />
            <TextField label="Review date" type="date" value={f.reviewDate} onChange={set('reviewDate')} slotProps={{ inputLabel: { shrink: true } }} />
            {plan && (
              <TextField select label="Status" value={f.status} onChange={set('status')}>
                {(['ACTIVE', 'COMPLETED', 'DISCONTINUED'] as const).map((s) => <MenuItem key={s} value={s}>{humanize(s)}</MenuItem>)}
              </TextField>
            )}
          </FormGrid>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={m.isPending || !f.title.trim()}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function PrescriptionsPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = usePrescriptions(patientId);
  const [adding, setAdding] = useState(false);
  const [stopping, setStopping] = useState<number | null>(null);
  const qc = useQueryClient();
  const complete = useMutation({
    mutationFn: encountersApi.complete,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['patients', patientId] }),
  });

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Prescriptions</Typography>
        {canWrite && <Button startIcon={<AddIcon />} onClick={() => setAdding(true)}>Prescribe</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {complete.error && <ErrorState error={complete.error} />}
      {data && data.length === 0 && <EmptyState title="No prescriptions" />}
      {data && data.length > 0 && (
        <TableContainer>
          <Table size="small" aria-label="Prescriptions">
            <TableHead>
              <TableRow>
                <TableCell>Medication</TableCell>
                <TableCell>Dose / route / frequency</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Duration</TableCell>
                <TableCell>Prescribed</TableCell>
                <TableCell>Status</TableCell>
                {canWrite && <TableCell />}
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((p) => (
                <TableRow key={p.id}>
                  <TableCell sx={{ fontWeight: 600 }}>
                    {p.medicationName}
                    {p.instructions && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{p.instructions}</Typography>}
                  </TableCell>
                  <TableCell>{p.dose} · {p.route} · {p.frequency}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{orDash(p.duration)}{p.refills ? ` · ${p.refills} refills` : ''}</TableCell>
                  <TableCell>{formatDateTime(p.prescribedAt)}<Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{p.prescribedByName}</Typography></TableCell>
                  <TableCell>
                    <Chip size="small" label={humanize(p.status)} color={statusColor(p.status)} variant="outlined" />
                    {p.discontinuedReason && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{p.discontinuedReason}</Typography>}
                  </TableCell>
                  {canWrite && (
                    <TableCell>
                      {p.status === 'ACTIVE' && (
                        <Stack direction="row" spacing={1}>
                          <Button size="small" onClick={() => complete.mutate(p.id)}>Complete</Button>
                          <Button size="small" color="warning" onClick={() => setStopping(p.id)}>Discontinue</Button>
                        </Stack>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {adding && <PrescribeDialog patientId={patientId} onClose={() => setAdding(false)} />}
      {stopping && <DiscontinueDialog patientId={patientId} prescriptionId={stopping} onClose={() => setStopping(null)} />}
    </Paper>
  );
}

function PrescribeDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const [f, setF] = useState({ medicationName: '', dose: '', route: 'Oral', frequency: '', duration: '', quantity: '', refills: '0', instructions: '' });
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => encountersApi.prescribe(patientId, {
      ...f, refills: Number(f.refills) || 0, duration: f.duration || undefined, quantity: f.quantity || undefined, instructions: f.instructions || undefined,
    }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['patients', patientId] }); onClose(); },
  });
  const errs = fieldErrors(m.error);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  const field = (k: keyof typeof f, label: string, required = false) => (
    <TextField label={label} value={f[k]} onChange={set(k)} required={required} error={!!errs[k]} helperText={errs[k]} fullWidth />
  );
  const top = formLevelError(m.error, Object.keys(f));
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); m.mutate(); }} noValidate>
        <DialogTitle>New prescription</DialogTitle>
        <DialogContent dividers>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <FormGrid>
            <FullRow>{field('medicationName', 'Medication', true)}</FullRow>
            {field('dose', 'Dose (e.g. 100 mcg)', true)}
            <TextField select label="Route" value={f.route} onChange={set('route')} required>
              {['Oral', 'Inhaled', 'Nebulised', 'Intravenous', 'Intramuscular', 'Subcutaneous', 'Topical', 'Intranasal'].map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}
            </TextField>
            {field('frequency', 'Frequency (e.g. twice daily)', true)}
            {field('duration', 'Duration')}
            {field('quantity', 'Quantity')}
            <TextField label="Refills" type="number" value={f.refills} onChange={set('refills')} slotProps={{ htmlInput: { min: 0, max: 12 } }} error={!!errs.refills} helperText={errs.refills} />
            <FullRow><TextField label="Instructions" value={f.instructions} onChange={set('instructions')} multiline minRows={2} fullWidth /></FullRow>
          </FormGrid>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={m.isPending || !f.medicationName || !f.dose || !f.frequency}>Prescribe</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function DiscontinueDialog({ patientId, prescriptionId, onClose }: { patientId: number; prescriptionId: number; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => encountersApi.discontinue(prescriptionId, reason.trim()),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['patients', patientId] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Discontinue prescription</DialogTitle>
      <DialogContent>
        {m.error && <ErrorState error={m.error} />}
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required multiline minRows={2} autoFocus sx={{ mt: 1 }} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>Discontinue</Button>
      </DialogActions>
    </Dialog>
  );
}
