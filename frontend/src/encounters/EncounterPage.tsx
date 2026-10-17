import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
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
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { encounterKeys, encountersApi, useEncounter, type Encounter, type EncounterDocumentation, type EncounterType } from './api';
import { usePatient, fullName } from '../patients/api';
import { PageHeader } from '../shared/components/PageHeader';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { DetailList } from '../shared/components/DetailList';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDateTime, humanize, orDash } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { ObservationsPanel } from '../respiratory/ObservationsPanel';
import { PftPanel } from '../respiratory/PftPanel';
import { encounterStatusColor } from './EncountersPanel';
import { toApiRequestError } from '../shared/api/client';

const BOOL_FIELDS: [keyof EncounterDocumentation, string][] = [
  ['cough', 'Cough'], ['sputum', 'Sputum'], ['wheezing', 'Wheezing'], ['dyspnea', 'Dyspnoea'],
  ['chestPain', 'Chest pain'], ['fever', 'Fever'], ['hemoptysis', 'Haemoptysis'],
];

export function EncounterPage() {
  const id = Number(useParams().id);
  const { data: encounter, isPending, error, refetch } = useEncounter(id);
  const { hasRole } = useAuth();

  if (isPending) return <LoadingState />;
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />;
  const editable = hasRole('CLINICIAN') && encounter.status === 'IN_PROGRESS';
  return <EncounterView key={`${encounter.id}-${encounter.version}`} encounter={encounter} editable={editable} />;
}

function EncounterView({ encounter: e, editable }: { encounter: Encounter; editable: boolean }) {
  const patient = usePatient(e.patientId);
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [doc, setDoc] = useState<EncounterDocumentation>(() => pickDoc(e));
  const [type, setType] = useState<EncounterType>(e.type);
  const [dirty, setDirty] = useState(false);
  const [amending, setAmending] = useState(false);
  const [confirmFinalize, setConfirmFinalize] = useState(false);

  // Warn before leaving with unsaved documentation.
  useEffect(() => {
    if (!dirty) return;
    const h = (ev: BeforeUnloadEvent) => ev.preventDefault();
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  const onSaved = (updated: Encounter) => {
    qc.setQueryData(encounterKeys.detail(updated.id), updated);
    void qc.invalidateQueries({ queryKey: ['patients', updated.patientId] });
    void qc.invalidateQueries({ queryKey: ['encounters', 'mine'] });
    setDirty(false);
  };
  const save = useMutation({ mutationFn: () => encountersApi.update(e.id, { ...doc, type, version: e.version }), onSuccess: onSaved });
  const finalize = useMutation({
    mutationFn: async () => {
      // Save pending edits first so the finalized record matches what is on screen.
      if (dirty) onSaved(await encountersApi.update(e.id, { ...doc, type, version: e.version }));
      return encountersApi.finalize(e.id);
    },
    onSuccess: (r) => { onSaved(r); setConfirmFinalize(false); },
  });

  const set = <K extends keyof EncounterDocumentation>(k: K, v: EncounterDocumentation[K]) => {
    setDoc((d) => ({ ...d, [k]: v }));
    setDirty(true);
  };
  const errs = fieldErrors(save.error);
  const text = (k: keyof EncounterDocumentation, label: string, rows = 1) => (
    <TextField label={label} value={(doc[k] as string | undefined) ?? ''} onChange={(ev) => set(k, ev.target.value as never)}
      multiline={rows > 1} minRows={rows} fullWidth disabled={!editable} error={!!errs[k]} helperText={errs[k]} />
  );
  const num = (k: keyof EncounterDocumentation, label: string, min: number, max: number) => (
    <TextField label={label} type="number" value={(doc[k] as number | undefined) ?? ''} disabled={!editable}
      onChange={(ev) => set(k, (ev.target.value === '' ? undefined : Number(ev.target.value)) as never)}
      slotProps={{ htmlInput: { min, max } }} error={!!errs[k]} helperText={errs[k]} />
  );

  const p = patient.data;
  return (
    <>
      <PageHeader
        title={`Encounter #${e.id}`}
        subtitle={
          <>
            {p ? <RouterLink to={`/patients/${p.id}`}>{fullName(p)}</RouterLink> : '…'} · {p?.mrn} · {humanize(e.type)} · started {formatDateTime(e.startedAt)} · {e.clinicianName}
          </>
        }
        crumbs={[{ label: 'Patients', to: '/patients' }, ...(p ? [{ label: fullName(p), to: `/patients/${p.id}` }] : []), { label: `Encounter #${e.id}` }]}
        actions={
          <>
            <Chip label={humanize(e.status)} color={encounterStatusColor(e.status)} />
            {editable && <Button variant="outlined" onClick={() => save.mutate()} disabled={!dirty || save.isPending}>{save.isPending ? 'Saving…' : 'Save draft'}</Button>}
            {editable && <Button variant="contained" onClick={() => setConfirmFinalize(true)}>Finalize</Button>}
            {hasRole('CLINICIAN') && e.status === 'FINALIZED' && !e.amendedByEncounterId && (
              <Button variant="outlined" onClick={() => setAmending(true)}>Amend</Button>
            )}
            {hasRole('CLINICIAN') && (
              <Button component={RouterLink} to={`/patients/${e.patientId}/cdss?encounterId=${e.id}`}>Decision support</Button>
            )}
          </>
        }
      />
      <Stack spacing={2}>
        {e.amendsEncounterId && (
          <Alert severity="info">
            Amendment of <RouterLink to={`/encounters/${e.amendsEncounterId}`}>encounter #{e.amendsEncounterId}</RouterLink>. Reason: {e.amendmentReason}
          </Alert>
        )}
        {e.status === 'SUPERSEDED' && (
          <Alert severity="warning">
            This version was superseded by <RouterLink to={`/encounters/${e.amendedByEncounterId}`}>encounter #{e.amendedByEncounterId}</RouterLink>.
          </Alert>
        )}
        {e.status !== 'IN_PROGRESS' && (
          <Alert severity="success" variant="outlined">Finalized {formatDateTime(e.finalizedAt)}. Finalized records cannot be edited; use Amend to correct them.</Alert>
        )}
        {save.error && <Alert severity="error">{formLevelError(save.error, Object.keys(doc)) ?? toApiRequestError(save.error).message}</Alert>}

        <Paper sx={{ p: 2 }}>
          <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Presentation</Typography>
          <FormGrid>
            <TextField select label="Encounter type" value={type} disabled={!editable} onChange={(ev) => { setType(ev.target.value as EncounterType); setDirty(true); }}>
              {(['OUTPATIENT', 'INPATIENT', 'EMERGENCY', 'TELEHEALTH'] as const).map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
            </TextField>
            {text('chiefComplaint', 'Chief complaint')}
            <FullRow>{text('historyOfPresentIllness', 'History of presenting illness', 3)}</FullRow>
          </FormGrid>
        </Paper>

        <Paper sx={{ p: 2 }}>
          <Typography variant="h3" component="h2" sx={{ mb: 1 }}>Respiratory symptoms</Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' }, mb: 2 }}>
            {BOOL_FIELDS.map(([k, label]) => (
              <FormControlLabel key={k} label={label} disabled={!editable}
                control={<Switch checked={!!doc[k]} onChange={(ev) => set(k, ev.target.checked as never)} />} />
            ))}
          </Box>
          <FormGrid columns={3}>
            {num('symptomDurationDays', 'Symptom duration (days)', 0, 36500)}
            {num('coughDurationDays', 'Cough duration (days)', 0, 36500)}
            {text('sputumDescription', 'Sputum description')}
            <TextField select label="mMRC dyspnoea grade" value={doc.mmrcGrade ?? ''} disabled={!editable}
              onChange={(ev) => set('mmrcGrade', ev.target.value === '' ? undefined : Number(ev.target.value))}>
              <MenuItem value="">Not assessed</MenuItem>
              {[0, 1, 2, 3, 4].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
            </TextField>
            {num('catScore', 'CAT score (0–40)', 0, 40)}
          </FormGrid>
        </Paper>

        <Paper sx={{ p: 2 }}>
          <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Examination, results and plan</Typography>
          <FormGrid>
            {text('physicalExam', 'Physical examination', 3)}
            {text('labResults', 'Laboratory results', 3)}
            {text('assessmentNotes', 'Assessment', 3)}
            {text('planNotes', 'Plan', 3)}
            <FullRow>{text('followUp', 'Follow-up', 2)}</FullRow>
          </FormGrid>
        </Paper>

        <ObservationsPanel patientId={e.patientId} encounterId={e.id} canWrite={editable} />
        <PftPanel patientId={e.patientId} encounterId={e.id} canWrite={editable} />
      </Stack>

      {confirmFinalize && (
        <Dialog open onClose={() => setConfirmFinalize(false)}>
          <DialogTitle>Finalize encounter?</DialogTitle>
          <DialogContent>
            <Typography>Finalized documentation becomes read-only. Later corrections are made through an amendment that keeps this version.</Typography>
            {finalize.error && <Alert severity="error" sx={{ mt: 2 }}>{toApiRequestError(finalize.error).message}</Alert>}
            <DetailList columns={2} items={[{ label: 'Chief complaint', value: orDash(doc.chiefComplaint) }, { label: 'Unsaved changes', value: dirty ? 'Will be saved first' : 'None' }]} />
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setConfirmFinalize(false)}>Cancel</Button>
            <Button variant="contained" onClick={() => finalize.mutate()} disabled={finalize.isPending}>Finalize</Button>
          </DialogActions>
        </Dialog>
      )}
      {amending && <AmendDialog encounterId={e.id} onClose={() => setAmending(false)} onCreated={(id) => navigate(`/encounters/${id}`)} />}
    </>
  );
}

function AmendDialog({ encounterId, onClose, onCreated }: { encounterId: number; onClose: () => void; onCreated: (id: number) => void }) {
  const [reason, setReason] = useState('');
  const m = useMutation({ mutationFn: () => encountersApi.amend(encounterId, reason.trim()), onSuccess: (a) => onCreated(a.id) });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Amend encounter #{encounterId}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          A new draft is created from this encounter. When the amendment is finalized, this version is marked as superseded (it is never deleted).
        </Typography>
        {m.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(m.error).message}</Alert>}
        <TextField label="Reason for amendment" value={reason} onChange={(ev) => setReason(ev.target.value)} fullWidth required multiline minRows={2} autoFocus />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>Create amendment</Button>
      </DialogActions>
    </Dialog>
  );
}

function pickDoc(e: Encounter): EncounterDocumentation {
  const { chiefComplaint, historyOfPresentIllness, cough, coughDurationDays, sputum, sputumDescription, wheezing, dyspnea,
    mmrcGrade, catScore, chestPain, fever, hemoptysis, symptomDurationDays, physicalExam, labResults, assessmentNotes,
    planNotes, followUp } = e;
  return { chiefComplaint, historyOfPresentIllness, cough, coughDurationDays, sputum, sputumDescription, wheezing, dyspnea,
    mmrcGrade, catScore, chestPain, fever, hemoptysis, symptomDurationDays, physicalExam, labResults, assessmentNotes,
    planNotes, followUp };
}
