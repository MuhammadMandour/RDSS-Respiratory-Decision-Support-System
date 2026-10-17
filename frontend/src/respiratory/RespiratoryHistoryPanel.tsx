import { useState, type FormEvent } from 'react';
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
  FormControlLabel,
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import HistoryIcon from '@mui/icons-material/History';
import { useMutation } from '@tanstack/react-query';
import { respiratoryApi, useHistoryVersions, useInvalidatePatient, useRespiratoryHistory, type RespiratoryHistory, type RespiratoryHistoryRequest } from './api';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { DetailList } from '../shared/components/DetailList';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDateTime, numOrUndefined, orDash } from '../shared/format';

const CONDITIONS: [keyof RespiratoryHistoryRequest, string][] = [
  ['asthma', 'Asthma'],
  ['copd', 'COPD'],
  ['bronchiectasis', 'Bronchiectasis'],
  ['interstitialLungDisease', 'Interstitial lung disease'],
  ['obstructiveSleepApnea', 'Obstructive sleep apnoea'],
  ['previousTuberculosis', 'Previous tuberculosis'],
  ['homeOxygen', 'Home oxygen'],
  ['previousIntubation', 'Previous intubation'],
];

export function RespiratoryHistoryPanel({ patientId, canWrite }: { patientId: number; canWrite: boolean }) {
  const { data, isPending, error, refetch } = useRespiratoryHistory(patientId);
  const [editing, setEditing] = useState(false);
  const [showVersions, setShowVersions] = useState(false);

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
        <Box>
          <Typography variant="h3" component="h2">Respiratory history</Typography>
          {data && (
            <Typography variant="caption" color="text.secondary">
              Version {data.versionNo} · recorded {formatDateTime(data.recordedAt)} by {data.recordedByName ?? '—'}
            </Typography>
          )}
        </Box>
        <Stack direction="row" spacing={1}>
          {data && data.versionNo > 1 && (
            <Button startIcon={<HistoryIcon />} onClick={() => setShowVersions(true)}>Versions</Button>
          )}
          {canWrite && (
            <Button startIcon={<EditOutlinedIcon />} onClick={() => setEditing(true)}>
              {data ? 'Record new version' : 'Record history'}
            </Button>
          )}
        </Stack>
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {!isPending && !error && !data && <EmptyState title="No respiratory history recorded" />}
      {data && <HistoryView h={data} />}
      {editing && <HistoryDialog patientId={patientId} current={data ?? undefined} onClose={() => setEditing(false)} />}
      {showVersions && <VersionsDialog patientId={patientId} onClose={() => setShowVersions(false)} />}
    </Paper>
  );
}

function HistoryView({ h }: { h: RespiratoryHistory }) {
  const active = CONDITIONS.filter(([k]) => h[k]);
  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
        {active.length === 0 && <Typography color="text.secondary">No chronic respiratory conditions documented.</Typography>}
        {active.map(([k, label]) => <Chip key={k} label={label} color="primary" variant="outlined" size="small" />)}
        {h.hypercapnicRespiratoryFailureRisk && <Chip label="Hypercapnic respiratory failure risk" color="warning" size="small" />}
      </Stack>
      <DetailList
        items={[
          { label: 'Target SpO₂', value: h.targetSpo2Low && h.targetSpo2High ? `${h.targetSpo2Low}–${h.targetSpo2High}%` : '—' },
          { label: 'Exacerbations (12 months)', value: orDash(h.exacerbationsLast12m) },
          { label: 'Hospitalisations (12 months)', value: orDash(h.hospitalizationsLast12m) },
          { label: 'Inhaler therapy', value: orDash(h.inhalerTherapy) },
          { label: 'Occupational exposures', value: orDash(h.occupationalExposures) },
          { label: 'Family history', value: orDash(h.familyHistory) },
          { label: 'Vaccinations', value: orDash(h.vaccinations) },
          { label: 'Comorbidities', value: orDash(h.comorbidities) },
          { label: 'Notes', value: orDash(h.notes) },
        ]}
      />
    </Stack>
  );
}

function toForm(h?: RespiratoryHistory): Record<string, string | boolean> {
  const base: Record<string, string | boolean> = {};
  for (const [k] of CONDITIONS) base[k] = h ? Boolean(h[k]) : false;
  base.hypercapnicRespiratoryFailureRisk = h?.hypercapnicRespiratoryFailureRisk ?? false;
  for (const k of ['targetSpo2Low', 'targetSpo2High', 'exacerbationsLast12m', 'hospitalizationsLast12m', 'inhalerTherapy', 'occupationalExposures', 'familyHistory', 'vaccinations', 'comorbidities', 'notes'] as const) {
    const v = h?.[k];
    base[k] = v === undefined || v === null ? '' : String(v);
  }
  return base;
}

function HistoryDialog({ patientId, current, onClose }: { patientId: number; current?: RespiratoryHistory; onClose: () => void }) {
  const [f, setF] = useState(() => toForm(current));
  const invalidate = useInvalidatePatient(patientId);
  const m = useMutation({
    mutationFn: (body: RespiratoryHistoryRequest) => respiratoryApi.recordHistory(patientId, body),
    onSuccess: () => { void invalidate(); onClose(); },
  });
  const errs = fieldErrors(m.error);
  const str = (k: string) => String(f[k] ?? '');
  const text = (k: string, label: string, multiline = false) => (
    <TextField label={label} value={str(k)} onChange={(e) => setF((s) => ({ ...s, [k]: e.target.value }))} fullWidth multiline={multiline} minRows={multiline ? 2 : undefined} error={!!errs[k]} helperText={errs[k]} />
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const opt = (k: string) => str(k).trim() || undefined;
    m.mutate({
      asthma: !!f.asthma, copd: !!f.copd, bronchiectasis: !!f.bronchiectasis, interstitialLungDisease: !!f.interstitialLungDisease,
      obstructiveSleepApnea: !!f.obstructiveSleepApnea, previousTuberculosis: !!f.previousTuberculosis,
      hypercapnicRespiratoryFailureRisk: !!f.hypercapnicRespiratoryFailureRisk, homeOxygen: !!f.homeOxygen,
      previousIntubation: !!f.previousIntubation,
      targetSpo2Low: numOrUndefined(str('targetSpo2Low')), targetSpo2High: numOrUndefined(str('targetSpo2High')),
      exacerbationsLast12m: numOrUndefined(str('exacerbationsLast12m')), hospitalizationsLast12m: numOrUndefined(str('hospitalizationsLast12m')),
      inhalerTherapy: opt('inhalerTherapy'), occupationalExposures: opt('occupationalExposures'), familyHistory: opt('familyHistory'),
      vaccinations: opt('vaccinations'), comorbidities: opt('comorbidities'), notes: opt('notes'),
    });
  };
  const top = formLevelError(m.error, Object.keys(f));

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <form onSubmit={submit} noValidate>
        <DialogTitle>{current ? `Record version ${current.versionNo + 1}` : 'Record respiratory history'}</DialogTitle>
        <DialogContent dividers>
          <Alert severity="info" sx={{ mb: 2 }}>Saving creates a new version. Earlier versions are kept unchanged.</Alert>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <Typography variant="subtitle2">Conditions</Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, mb: 2 }}>
            {CONDITIONS.map(([k, label]) => (
              <FormControlLabel key={k} control={<Checkbox checked={!!f[k]} onChange={(e) => setF((s) => ({ ...s, [k]: e.target.checked }))} />} label={label} />
            ))}
          </Box>
          <FormGrid>
            <FullRow>
              <FormControlLabel
                control={<Checkbox checked={!!f.hypercapnicRespiratoryFailureRisk} onChange={(e) => setF((s) => ({ ...s, hypercapnicRespiratoryFailureRisk: e.target.checked }))} />}
                label="Documented risk of hypercapnic respiratory failure (enables NEWS2 SpO₂ scale 2)"
              />
            </FullRow>
            {text('targetSpo2Low', 'Target SpO₂ low (%)')}
            {text('targetSpo2High', 'Target SpO₂ high (%)')}
            {text('exacerbationsLast12m', 'Exacerbations in last 12 months')}
            {text('hospitalizationsLast12m', 'Hospitalisations in last 12 months')}
            {text('inhalerTherapy', 'Inhaler therapy', true)}
            {text('occupationalExposures', 'Occupational exposures', true)}
            {text('familyHistory', 'Family history', true)}
            {text('vaccinations', 'Vaccinations', true)}
            {text('comorbidities', 'Comorbidities', true)}
            {text('notes', 'Notes', true)}
          </FormGrid>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={m.isPending}>Save new version</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function VersionsDialog({ patientId, onClose }: { patientId: number; onClose: () => void }) {
  const { data, isPending, error } = useHistoryVersions(patientId, true);
  const [selected, setSelected] = useState<RespiratoryHistory | null>(null);
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Respiratory history versions</DialogTitle>
      <DialogContent dividers>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} />}
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '220px 1fr' }, gap: 2 }}>
          <List dense>
            {data?.map((v) => (
              <ListItem key={v.id} disablePadding>
                <Button fullWidth sx={{ justifyContent: 'flex-start' }} variant={selected?.id === v.id ? 'contained' : 'text'} onClick={() => setSelected(v)}>
                  <ListItemText primary={`Version ${v.versionNo}`} secondary={`${formatDateTime(v.recordedAt)} · ${v.recordedByName ?? ''}`} />
                </Button>
              </ListItem>
            ))}
          </List>
          <Box>{selected ? <HistoryView h={selected} /> : <Typography color="text.secondary">Select a version to view it.</Typography>}</Box>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
