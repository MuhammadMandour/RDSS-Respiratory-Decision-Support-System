import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  FormControlLabel,
  FormLabel,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import EditNoteIcon from '@mui/icons-material/EditNote';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { cdssApi, cdssKeys, news2RiskLabel, severityColor, useAssessment, useAssessmentHistory, type AsthmaControlAnswers } from './api';
import { AssessmentResult } from './AssessmentResult';
import { fullName, usePatient } from '../patients/api';
import { useObservations, useRespiratoryHistory } from '../respiratory/api';
import { useEncounters } from '../encounters/api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDateTime, humanize, orDash } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';

const ASTHMA_QUESTIONS: [keyof AsthmaControlAnswers, string][] = [
  ['daytimeSymptomsMoreThanTwiceWeekly', 'Daytime asthma symptoms more than twice a week?'],
  ['nightWakingDueToAsthma', 'Any night waking due to asthma?'],
  ['relieverNeededMoreThanTwiceWeekly', 'Reliever needed for symptoms more than twice a week?'],
  ['activityLimitation', 'Any activity limitation due to asthma?'],
];

export function CdssPage() {
  const patientId = Number(useParams().id);
  const [search, setSearch] = useSearchParams();
  const initialEncounter = search.get('encounterId');
  const patient = usePatient(patientId);
  const history = useAssessmentHistory(patientId);
  const [selected, setSelected] = useState<number | null>(search.get('assessment') ? Number(search.get('assessment')) : null);
  const current = useAssessment(selected);

  const select = (id: number) => {
    setSelected(id);
    const next = new URLSearchParams(search);
    next.set('assessment', String(id));
    setSearch(next, { replace: true });
  };

  if (patient.isPending) return <LoadingState />;
  if (patient.error) return <ErrorState error={patient.error} />;
  const p = patient.data;

  return (
    <>
      <PageHeader
        title="Respiratory decision support"
        subtitle={`${fullName(p)} · ${p.mrn} · ${p.age} y · ${humanize(p.sex)}`}
        crumbs={[{ label: 'Patients', to: '/patients' }, { label: fullName(p), to: `/patients/${p.id}` }, { label: 'Decision support' }]}
      />
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '360px minmax(0, 1fr)' }, alignItems: 'start' }}>
        <Stack spacing={2}>
          <ClinicalSummary patientId={patientId} />
          <AssessmentForm patientId={patientId} initialEncounterId={initialEncounter ? Number(initialEncounter) : undefined} onCreated={select} />
          <Paper sx={{ p: 2 }}>
            <Typography variant="h3" component="h2" sx={{ mb: 1 }}>Assessment history</Typography>
            {history.isPending && <LoadingState />}
            {history.error && <ErrorState error={history.error} />}
            {history.data?.totalElements === 0 && <EmptyState title="No assessments yet" />}
            {!!history.data?.totalElements && (
              <Table size="small" aria-label="Assessment history">
                <TableHead>
                  <TableRow>
                    <TableCell>When</TableCell>
                    <TableCell>NEWS2</TableCell>
                    <TableCell>Highest</TableCell>
                    <TableCell>Review</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {history.data.content.map((h) => (
                    <TableRow key={h.id} hover selected={h.id === selected} sx={{ cursor: 'pointer' }} tabIndex={0}
                      onClick={() => select(h.id)} onKeyDown={(e) => e.key === 'Enter' && select(h.id)}>
                      <TableCell>{formatDateTime(h.assessedAt)}</TableCell>
                      <TableCell>{h.news2 ? `${h.news2.score} (${news2RiskLabel(h.news2.risk)})` : '—'}</TableCell>
                      <TableCell><Chip size="small" label={h.highestSeverity} color={severityColor(h.highestSeverity)} /></TableCell>
                      <TableCell>{humanize(h.reviewStatus)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Paper>
        </Stack>
        <Box>
          {!selected && <Paper sx={{ p: 2 }}><EmptyState title="Run an assessment or select one from the history" /></Paper>}
          {selected && current.isPending && <LoadingState />}
          {current.error && <ErrorState error={current.error} />}
          {current.data && <AssessmentResult assessment={current.data} />}
        </Box>
      </Box>
    </>
  );
}

/** Clinician-entered data the engine will read, shown before running. */
function ClinicalSummary({ patientId }: { patientId: number }) {
  const hist = useRespiratoryHistory(patientId);
  const obs = useObservations(patientId);
  const latest = obs.data?.content.find((o) => !o.enteredInError);
  const h = hist.data;
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
        <EditNoteIcon fontSize="small" color="action" />
        <Typography variant="h3" component="h2">Clinician-entered data</Typography>
      </Stack>
      {(hist.isPending || obs.isPending) && <LoadingState />}
      <Typography variant="subtitle2">Respiratory history</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {h ? [h.asthma && 'Asthma', h.copd && 'COPD', h.hypercapnicRespiratoryFailureRisk && 'Hypercapnic risk',
          h.targetSpo2Low && `Target SpO₂ ${h.targetSpo2Low}–${h.targetSpo2High}%`].filter(Boolean).join(' · ') || 'No chronic conditions'
          : 'Not recorded'}
      </Typography>
      <Typography variant="subtitle2">Latest observations</Typography>
      {latest ? (
        <Typography variant="body2" color="text.secondary">
          {formatDateTime(latest.observedAt)}: RR {orDash(latest.respiratoryRate)}, SpO₂ {orDash(latest.spo2)}%
          {latest.onSupplementalOxygen ? ' on O₂' : latest.onSupplementalOxygen === false ? ' on air' : ''}, HR {orDash(latest.heartRate)},
          BP {latest.systolicBp ? `${latest.systolicBp}/${latest.diastolicBp ?? '—'}` : '—'}, T {orDash(latest.temperatureC)}, {humanize(latest.consciousness)}
        </Typography>
      ) : (
        <Typography variant="body2" color="text.secondary">None recorded</Typography>
      )}
      <Button size="small" component={RouterLink} to={`/patients/${patientId}?tab=respiratory`} sx={{ mt: 1 }}>Update record</Button>
    </Paper>
  );
}

function AssessmentForm({ patientId, initialEncounterId, onCreated }: { patientId: number; initialEncounterId?: number; onCreated: (id: number) => void }) {
  const encounters = useEncounters(patientId);
  const [encounterId, setEncounterId] = useState<number | ''>(initialEncounterId ?? '');
  const [askAsthma, setAskAsthma] = useState(false);
  const [answers, setAnswers] = useState<AsthmaControlAnswers>({});
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => cdssApi.assess(patientId, { encounterId: encounterId || undefined, asthmaControl: askAsthma ? answers : undefined }),
    onSuccess: (a) => {
      qc.setQueryData(cdssKeys.detail(a.id), a);
      void qc.invalidateQueries({ queryKey: cdssKeys.history(patientId) });
      onCreated(a.id);
    },
  });
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2" sx={{ mb: 2 }}>New assessment</Typography>
      <Stack spacing={2}>
        <TextField select size="small" label="Link to encounter (symptoms used)" value={encounterId} onChange={(e) => setEncounterId(e.target.value === '' ? '' : Number(e.target.value))}>
          <MenuItem value="">None</MenuItem>
          {encounters.data?.content.map((e) => (
            <MenuItem key={e.id} value={e.id}>#{e.id} · {formatDateTime(e.startedAt)} · {humanize(e.status)}</MenuItem>
          ))}
        </TextField>
        <FormControlLabel control={<Switch checked={askAsthma} onChange={(e) => setAskAsthma(e.target.checked)} />} label="Include GINA asthma control questions (past 4 weeks)" />
        {askAsthma && ASTHMA_QUESTIONS.map(([k, q]) => (
          <FormControl key={k}>
            <FormLabel sx={{ fontSize: 14 }}>{q}</FormLabel>
            <RadioGroup row value={answers[k] === undefined ? '' : String(answers[k])}
              onChange={(e) => setAnswers((s) => ({ ...s, [k]: e.target.value === 'true' }))}>
              <FormControlLabel value="true" control={<Radio size="small" />} label="Yes" />
              <FormControlLabel value="false" control={<Radio size="small" />} label="No" />
            </RadioGroup>
          </FormControl>
        ))}
        {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
        <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={() => m.mutate()} disabled={m.isPending}>
          {m.isPending ? 'Running…' : 'Run assessment'}
        </Button>
        <Typography variant="caption" color="text.secondary">Uses the current record. Entries marked "entered in error" are excluded.</Typography>
      </Stack>
    </Paper>
  );
}
