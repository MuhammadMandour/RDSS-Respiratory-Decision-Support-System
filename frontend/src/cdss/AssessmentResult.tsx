import { useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert as MuiAlert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Link,
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { alertSeverity, cdssApi, cdssKeys, news2RiskLabel, severityColor, type Alert, type Assessment } from './api';
import { formatDateTime, humanize } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';

/** Renders one stored assessment. Everything here is system-generated except the review section. */
export function AssessmentResult({ assessment: a }: { assessment: Assessment }) {
  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 2, borderLeft: 4, borderLeftColor: `${severityColor(a.highestSeverity) === 'default' ? 'grey.400' : `${severityColor(a.highestSeverity)}.main`}` }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ justifyContent: 'space-between' }}>
          <Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
              <Chip icon={<SmartToyOutlinedIcon />} label="System-generated" size="small" variant="outlined" />
              <Typography variant="caption" color="text.secondary">
                Assessment #{a.id} · {formatDateTime(a.assessedAt)} · engine v{a.engineVersion} · requested by {a.requestedByName}
                {a.encounterId ? ` · encounter #${a.encounterId}` : ''}
              </Typography>
            </Stack>
            <Typography variant="h2">
              {a.alerts.length === 0 ? 'No findings' : `${a.alerts.length} finding${a.alerts.length > 1 ? 's' : ''}`}
            </Typography>
          </Box>
          {a.news2 ? (
            <Box sx={{ textAlign: { sm: 'right' } }} aria-label={`NEWS2 score ${a.news2.score}`}>
              <Typography variant="overline" color="text.secondary">NEWS2 (scale {a.news2.scale})</Typography>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: { sm: 'flex-end' } }}>
                <Typography sx={{ fontSize: 40, fontWeight: 700, lineHeight: 1 }}>{a.news2.score}</Typography>
                <Chip label={`${news2RiskLabel(a.news2.risk)} risk`} color={a.news2.risk === 'LOW' ? 'success' : a.news2.risk === 'HIGH' || a.news2.risk === 'MEDIUM' ? 'error' : 'warning'} />
              </Stack>
            </Box>
          ) : (
            <Typography color="text.secondary" variant="body2">NEWS2 not calculated (see missing data)</Typography>
          )}
        </Stack>
        <MuiAlert severity="info" variant="outlined" sx={{ mt: 2 }}>{a.disclaimer}</MuiAlert>
      </Paper>

      {a.missingData.length > 0 && (
        <MuiAlert severity="warning">
          <AlertTitle>Missing information and uncertainty</AlertTitle>
          <List dense disablePadding>
            {a.missingData.map((m) => <ListItem key={m} disablePadding><ListItemText primary={`• ${m}`} /></ListItem>)}
          </List>
        </MuiAlert>
      )}

      {a.alerts.map((alert) => <AlertCard key={alert.id} alert={alert} />)}

      {a.inference && (
        <MuiAlert severity="warning" icon={<SmartToyOutlinedIcon />}>
          <AlertTitle>Model inference — {String(a.inference.label ?? 'unvalidated')}</AlertTitle>
          <Typography variant="body2">{String(a.inference.message ?? '')}</Typography>
          <Typography variant="caption">Provider: {String(a.inference.provider)} · validated: {String(a.inference.validated ?? false)}</Typography>
        </MuiAlert>
      )}

      <Accordion disableGutters>
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Typography>Inputs used and rules applied (provenance)</Typography>
        </AccordionSummary>
        <AccordionDetails>
          <InputSnapshot snapshot={a.inputSnapshot} />
          <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>Rules applied</Typography>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
            {a.rulesApplied.map((r) => (
              <Chip key={r.code} size="small" variant="outlined" label={`${r.code} v${r.version}: ${humanize(r.outcome.split(':')[0])}`} />
            ))}
          </Stack>
        </AccordionDetails>
      </Accordion>

      <ReviewPanel assessment={a} />
    </Stack>
  );
}

function AlertCard({ alert }: { alert: Alert }) {
  return (
    <MuiAlert severity={alertSeverity(alert.severity)} variant="outlined" sx={{ '& .MuiAlert-message': { width: '100%' } }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 0.5 }}>
        <AlertTitle sx={{ m: 0 }}>{alert.title}</AlertTitle>
        <Chip size="small" label={alert.severity} color={severityColor(alert.severity)} />
        <Chip size="small" variant="outlined" label={`${humanize(alert.category)} · ${alert.ruleCode} v${alert.ruleVersion}`} />
        {alert.disposition !== 'PENDING' && (
          <Chip size="small" variant="outlined" color={alert.disposition === 'ACCEPTED' ? 'success' : 'default'}
            label={`${humanize(alert.disposition)} by ${alert.dispositionByName ?? '—'}`} />
        )}
      </Stack>
      <Typography variant="body2" sx={{ mb: 1 }}><strong>Why: </strong>{alert.explanation}</Typography>
      {alert.suggestion && (
        <Typography variant="body2" sx={{ mb: 1 }}><strong>For clinician consideration: </strong>{alert.suggestion}</Typography>
      )}
      {alert.dispositionReason && (
        <Typography variant="body2" sx={{ mb: 1 }}><strong>Dismissal reason: </strong>{alert.dispositionReason}</Typography>
      )}
      <Accordion disableGutters elevation={0} sx={{ bgcolor: 'transparent', '&:before': { display: 'none' } }}>
        <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0, minHeight: 0 }}>
          <Typography variant="body2" color="text.secondary">Evidence and references</Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ px: 0 }}>
          <Table size="small" aria-label="Evidence">
            <TableBody>
              {Object.entries(alert.evidence).map(([k, v]) => (
                <TableRow key={k}>
                  <TableCell sx={{ width: '40%', color: 'text.secondary' }}>{k}</TableCell>
                  <TableCell sx={{ wordBreak: 'break-word' }}>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {alert.guidelines.length > 0 ? (
            <List dense>
              {alert.guidelines.map((g) => (
                <ListItem key={g.code} disableGutters>
                  <ListItemText
                    primary={g.url ? <Link href={g.url} target="_blank" rel="noopener noreferrer">{g.title}</Link> : g.title}
                    secondary={`${g.publisher} · ${g.version}${g.citation ? ` — ${g.citation}` : ''}`}
                  />
                </ListItem>
              ))}
            </List>
          ) : (
            <Typography variant="caption" color="text.secondary">No guideline reference: heuristic rule, not a validated method.</Typography>
          )}
        </AccordionDetails>
      </Accordion>
    </MuiAlert>
  );
}

function InputSnapshot({ snapshot }: { snapshot: Record<string, unknown> }) {
  const sections = Object.entries(snapshot).filter(([k]) => k !== 'assessedAt');
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      {sections.map(([key, value]) => (
        <Box key={key}>
          <Typography variant="subtitle2">{humanize(key.replace(/([A-Z])/g, '_$1'))}</Typography>
          {value === null || value === undefined ? (
            <Typography variant="body2" color="text.secondary">Not available</Typography>
          ) : (
            <Table size="small">
              <TableBody>
                {Object.entries(value as Record<string, unknown>).filter(([, v]) => v !== null && v !== undefined).map(([k, v]) => (
                  <TableRow key={k}>
                    <TableCell sx={{ color: 'text.secondary', width: '55%' }}>{k}</TableCell>
                    <TableCell>{String(v)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </Box>
      ))}
    </Box>
  );
}

function ReviewPanel({ assessment: a }: { assessment: Assessment }) {
  const qc = useQueryClient();
  const [decisions, setDecisions] = useState<Record<number, { disposition?: 'ACCEPTED' | 'DISMISSED'; reason: string }>>({});
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: () =>
      cdssApi.review(a.id, {
        note: note.trim() || undefined,
        alerts: a.alerts.map((x) => ({ alertId: x.id, disposition: decisions[x.id]!.disposition!, reason: decisions[x.id]!.reason.trim() || undefined })),
      }),
    onSuccess: (r) => {
      qc.setQueryData(cdssKeys.detail(r.id), r);
      void qc.invalidateQueries({ queryKey: cdssKeys.history(r.patientId) });
    },
  });

  // Prefer the server's response to the review so the outcome shows immediately.
  const review = m.data?.review ?? a.review;
  if (review.status === 'REVIEWED') {
    return (
      <MuiAlert severity="success">
        <AlertTitle>Reviewed by {review.reviewedByName} · {formatDateTime(review.reviewedAt)}</AlertTitle>
        {review.note ?? 'No review note.'}
      </MuiAlert>
    );
  }

  const complete = a.alerts.every((x) => {
    const d = decisions[x.id];
    return d?.disposition && (d.disposition === 'ACCEPTED' || d.reason.trim());
  });

  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2">Clinician review</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Accept or dismiss each finding. Dismissals require a reason. Your review is recorded separately from the system output.
      </Typography>
      <Stack spacing={2}>
        {a.alerts.map((x) => {
          const d = decisions[x.id] ?? { reason: '' };
          return (
            <Box key={x.id} sx={{ display: 'grid', gap: 1, gridTemplateColumns: { xs: '1fr', md: '1fr auto' }, alignItems: 'center' }}>
              <Typography variant="body2"><Chip size="small" label={x.severity} color={severityColor(x.severity)} sx={{ mr: 1 }} />{x.title}</Typography>
              <ToggleButtonGroup size="small" exclusive value={d.disposition ?? null} aria-label={`Disposition for ${x.title}`}
                onChange={(_, v) => v && setDecisions((s) => ({ ...s, [x.id]: { ...d, disposition: v } }))}>
                <ToggleButton value="ACCEPTED">Accept</ToggleButton>
                <ToggleButton value="DISMISSED">Dismiss</ToggleButton>
              </ToggleButtonGroup>
              {d.disposition === 'DISMISSED' && (
                <Box sx={{ gridColumn: '1 / -1' }}>
                  <TextField size="small" fullWidth required label="Reason for dismissal" value={d.reason}
                    onChange={(e) => setDecisions((s) => ({ ...s, [x.id]: { ...d, reason: e.target.value } }))} />
                </Box>
              )}
            </Box>
          );
        })}
        <TextField label="Review note (optional)" value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2} />
        {m.error && <MuiAlert severity="error">{toApiRequestError(m.error).message}</MuiAlert>}
        <Box>
          <Button variant="contained" disabled={!complete || m.isPending} onClick={() => m.mutate()}>
            {a.alerts.length === 0 ? 'Acknowledge assessment' : 'Complete review'}
          </Button>
        </Box>
      </Stack>
    </Paper>
  );
}
