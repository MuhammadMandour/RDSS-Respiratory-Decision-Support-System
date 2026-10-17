import { useMemo, useState, type ReactNode } from 'react';
import {
  Alert, Box, Button, Chip, InputAdornment, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow,
  TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined';
import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis,
} from 'recharts';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDateTime, humanize, toDateInputValue } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';
import { money } from '../billing/api';
import { DATASET_LABELS, downloadExport, useExportDatasets, useReportSummary, type Count, type Period } from './api';

const PRESETS = [7, 30, 90, 365] as const;

function presetPeriod(days: number): Period {
  const to = new Date();
  const from = new Date();
  from.setDate(from.getDate() - (days - 1));
  return { from: toDateInputValue(from), to: toDateInputValue(to) };
}

const pct = (v?: number) => (v === undefined || v === null ? '—' : `${Math.round(v * 1000) / 10}%`);

export function ReportsPage() {
  const [period, setPeriod] = useState<Period>(() => presetPeriod(30));
  const [preset, setPreset] = useState<number | null>(30);
  const summary = useReportSummary(period);
  const s = summary.data;

  return (
    <>
      <PageHeader
        title="Reports"
        subtitle={s ? (s.scope === 'CARE_TEAM' ? 'Aggregates for patients on your care team' : 'Organisation-wide aggregates') : undefined}
      />
      <Paper sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ alignItems: { md: 'center' } }}>
          <ToggleButtonGroup size="small" exclusive value={preset}
            onChange={(_, v: number | null) => { if (v) { setPreset(v); setPeriod(presetPeriod(v)); } }}
            aria-label="Period preset">
            {PRESETS.map((d) => <ToggleButton key={d} value={d}>{d === 365 ? '12 months' : `${d} days`}</ToggleButton>)}
          </ToggleButtonGroup>
          <TextField label="From" type="date" size="small" value={period.from}
            slotProps={{ inputLabel: { shrink: true } }}
            onChange={(e) => { setPreset(null); setPeriod((p) => ({ ...p, from: e.target.value })); }} />
          <TextField label="To" type="date" size="small" value={period.to}
            slotProps={{ inputLabel: { shrink: true } }}
            onChange={(e) => { setPreset(null); setPeriod((p) => ({ ...p, to: e.target.value })); }} />
          {s && <Typography variant="body2" color="text.secondary">Generated {formatDateTime(s.generatedAt)}</Typography>}
        </Stack>
      </Paper>

      {summary.isPending && <LoadingState label="Calculating report…" />}
      {summary.error && <ErrorState error={summary.error} onRetry={() => summary.refetch()} />}
      {s && (
        <Stack spacing={2} sx={{ opacity: summary.isPlaceholderData ? 0.6 : 1 }}>
          <Alert severity="info">{Object.values(s.notes).join(' ')}</Alert>

          <KpiGrid items={[
            ['Patients', s.patients.total, `${s.patients.active} active · ${s.patients.newInPeriod} new in period`],
            ['Appointments', s.appointments.total, `Completion ${pct(s.appointments.completionRate)} · no-show ${pct(s.appointments.noShowRate)}`],
            ['CDSS assessments', s.cdss.assessments, `${s.cdss.pendingReview} awaiting clinician review`],
            ['Imaging studies', s.imaging.studies, `${s.imaging.pendingOrders} open imaging orders`],
          ]} />

          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
            <Section title="Appointments per day" subtitle="Excludes cancellations">
              {s.appointments.perDay.length === 0 ? <EmptyState title="No appointments in this period" /> : (
                <Chart>
                  <BarChart data={s.appointments.perDay}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="date" fontSize={12} />
                    <YAxis allowDecimals={false} fontSize={12} />
                    <ChartTooltip />
                    <Bar dataKey="count" name="Appointments" fill="#1565c0" />
                  </BarChart>
                </Chart>
              )}
            </Section>
            <Section title="Appointment status">
              <CountTable rows={s.appointments.byStatus} humanizeLabels empty="No appointments" />
            </Section>
          </Box>

          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Section title="Diagnosis distribution" subtitle="Top 10 ICD-10-CM codes recorded in the period">
              <HorizontalBars rows={s.topDiagnoses} labelKey="key" color="#6a1b9a" />
              <SearchableCountTable rows={s.topDiagnoses} codeHeader="ICD-10" empty="No diagnoses recorded" />
            </Section>
            <Section title="Respiratory conditions" subtitle="Current respiratory history of active patients">
              <HorizontalBars rows={s.patients.conditions} labelKey="label" color="#00838f" />
              <Typography variant="subtitle2" sx={{ mt: 2 }}>Smoking status</Typography>
              <CountTable rows={s.patients.smoking} humanizeLabels empty="No patients" />
            </Section>
          </Box>

          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Section title="Decision support" subtitle={`${s.cdss.reviewed} reviewed · ${s.cdss.dismissedAlerts} alerts dismissed by clinicians`}>
              <Typography variant="subtitle2">Highest severity per assessment</Typography>
              <CountTable rows={s.cdss.byHighestSeverity} humanizeLabels empty="No assessments" />
              <Typography variant="subtitle2" sx={{ mt: 2 }}>Alerts by rule</Typography>
              <SearchableCountTable rows={s.cdss.alertsByRule} codeHeader="Rule" empty="No alerts" hideLabel />
            </Section>
            <Section title="Imaging">
              <Typography variant="subtitle2">Studies by modality</Typography>
              <CountTable rows={s.imaging.byModality} empty="No studies linked in this period" />
              <Typography variant="subtitle2" sx={{ mt: 2 }}>Documented findings</Typography>
              <CountTable rows={s.imaging.findingsByType} humanizeLabels empty="No findings recorded" />
            </Section>
          </Box>

          {s.billing && <BillingSection billing={s.billing} />}
          {s.integration && (
            <Section title="Integration and security">
              <KpiGrid items={[
                ['HL7 received', s.integration.hl7Received, `${s.integration.hl7Processed} processed · ${s.integration.hl7Duplicates} duplicate replays`],
                ['HL7 errors', s.integration.hl7Failed + s.integration.hl7Rejected, `${s.integration.hl7Failed} failed · ${s.integration.hl7Rejected} rejected`],
                ['HL7 outbound', s.integration.outboundSent, `${s.integration.outboundFailed} pending or failed`],
                ['Denied access', s.integration.deniedAccess, 'Audit events with outcome DENIED'],
              ]} />
            </Section>
          )}

          <ExportsSection period={period} />
        </Stack>
      )}
    </>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Paper sx={{ p: 2 }} component="section" aria-label={title}>
      <Typography variant="h3" component="h2">{title}</Typography>
      {subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography>}
      <Box sx={{ mt: 1.5 }}>{children}</Box>
    </Paper>
  );
}

function Chart({ children, height = 260 }: { children: React.ReactElement; height?: number }) {
  return <Box sx={{ width: '100%', height }}><ResponsiveContainer>{children}</ResponsiveContainer></Box>;
}

function KpiGrid({ items }: { items: [string, ReactNode, string][] }) {
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' } }}>
      {items.map(([label, value, hint]) => (
        <Paper key={label} variant="outlined" sx={{ p: 2 }}>
          <Typography variant="body2" color="text.secondary">{label}</Typography>
          <Typography variant="h2" component="p" data-testid={`kpi-${label}`}>{value}</Typography>
          <Typography variant="caption" color="text.secondary">{hint}</Typography>
        </Paper>
      ))}
    </Box>
  );
}

function HorizontalBars({ rows, labelKey, color }: { rows: Count[]; labelKey: 'key' | 'label'; color: string }) {
  if (rows.length === 0 || rows.every((r) => r.count === 0)) return null;
  return (
    <Chart height={Math.max(120, rows.length * 30 + 40)}>
      <BarChart data={rows} layout="vertical" margin={{ left: 24 }}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis type="number" allowDecimals={false} fontSize={12} />
        <YAxis type="category" dataKey={labelKey} width={labelKey === 'key' ? 70 : 170} fontSize={12} />
        <ChartTooltip />
        <Bar dataKey="count" name="Count" fill={color} />
      </BarChart>
    </Chart>
  );
}

function CountTable({ rows, humanizeLabels, empty }: { rows: Count[]; humanizeLabels?: boolean; empty: string }) {
  if (rows.length === 0) return <Typography variant="body2" color="text.secondary">{empty}</Typography>;
  return (
    <Table size="small">
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.key}>
            <TableCell>{humanizeLabels ? humanize(r.label) : r.label}</TableCell>
            <TableCell align="right">{r.count}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function SearchableCountTable({ rows, codeHeader, empty, hideLabel }: {
  rows: Count[]; codeHeader: string; empty: string; hideLabel?: boolean;
}) {
  const [q, setQ] = useState('');
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return needle ? rows.filter((r) => r.key.toLowerCase().includes(needle) || r.label.toLowerCase().includes(needle)) : rows;
  }, [rows, q]);
  if (rows.length === 0) return <Typography variant="body2" color="text.secondary">{empty}</Typography>;
  return (
    <>
      <TextField size="small" placeholder={`Filter ${codeHeader.toLowerCase()}…`} value={q} onChange={(e) => setQ(e.target.value)}
        sx={{ mt: 1 }} fullWidth
        slotProps={{ htmlInput: { 'aria-label': `Filter ${codeHeader}` }, input: { startAdornment: <InputAdornment position="start"><SearchOutlinedIcon fontSize="small" /></InputAdornment> } }} />
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>{codeHeader}</TableCell>
            {!hideLabel && <TableCell>Description</TableCell>}
            <TableCell align="right">Count</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {filtered.map((r) => (
            <TableRow key={r.key}>
              <TableCell sx={{ fontFamily: 'monospace' }}>{r.key}</TableCell>
              {!hideLabel && <TableCell>{r.label}</TableCell>}
              <TableCell align="right">{r.count}</TableCell>
            </TableRow>
          ))}
          {filtered.length === 0 && <TableRow><TableCell colSpan={3}>No matches</TableCell></TableRow>}
        </TableBody>
      </Table>
    </>
  );
}

function BillingSection({ billing: b }: { billing: NonNullable<import('./api').ReportSummary['billing']> }) {
  return (
    <Section title="Billing" subtitle="Invoiced = issued in period; collected = payments received in period">
      <KpiGrid items={[
        ['Invoiced', money(b.invoiced, b.currency), 'Issued, partially paid and paid invoices'],
        ['Collected', money(b.collected, b.currency), `${money(b.simulatedPayments, b.currency)} via simulated payments`],
        ['Outstanding', money(b.outstanding, b.currency), 'On invoices issued in the period'],
        ['Overdue invoices', b.overdueInvoices, 'Past due date with a balance (all time)'],
      ]} />
      {b.simulatedPayments > 0 && (
        <Alert severity="warning" sx={{ mt: 2 }}>
          Simulated payments are included in collections. No real payment gateway is connected.
        </Alert>
      )}
      <Box sx={{ display: 'grid', gap: 2, mt: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
        <Box>
          <Typography variant="subtitle2">By month</Typography>
          {b.byMonth.length === 0 ? <Typography variant="body2" color="text.secondary">No billing activity</Typography> : (
            <Chart>
              <BarChart data={b.byMonth}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="month" fontSize={12} />
                <YAxis fontSize={12} />
                <ChartTooltip formatter={(v) => money(Number(v), b.currency)} />
                <Legend />
                <Bar dataKey="invoiced" name="Invoiced" fill="#1565c0" />
                <Bar dataKey="collected" name="Collected" fill="#2e7d32" />
              </BarChart>
            </Chart>
          )}
        </Box>
        <Box>
          <Typography variant="subtitle2">By service</Typography>
          {b.byService.length === 0 ? <Typography variant="body2" color="text.secondary">No invoiced services</Typography> : (
            <Table size="small">
              <TableHead><TableRow><TableCell>Service</TableCell><TableCell align="right">Lines</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
              <TableBody>
                {b.byService.map((r) => (
                  <TableRow key={r.code}>
                    <TableCell>{r.name} <Typography component="span" variant="caption" color="text.secondary">{r.code}</Typography></TableCell>
                    <TableCell align="right">{r.lines}</TableCell>
                    <TableCell align="right">{money(r.amount, b.currency)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          <Typography variant="subtitle2" sx={{ mt: 2 }}>Invoices created in period by status</Typography>
          <CountTable rows={b.byStatus} humanizeLabels empty="No invoices" />
        </Box>
      </Box>
    </Section>
  );
}

function ExportsSection({ period }: { period: Period }) {
  const datasets = useExportDatasets();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = async (slug: string) => {
    setBusy(slug);
    setError(null);
    try {
      await downloadExport(slug, period);
    } catch (e) {
      setError(toApiRequestError(e).message);
    } finally {
      setBusy(null);
    }
  };
  return (
    <Section title="CSV exports" subtitle="Aggregate datasets for the selected period. Exports are recorded in the audit log.">
      {datasets.error && <ErrorState error={datasets.error} />}
      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
        {datasets.data?.map((d) => (
          <Button key={d.slug} variant="outlined" size="small" startIcon={<DownloadOutlinedIcon />}
            disabled={busy !== null} onClick={() => run(d.slug)}>
            {DATASET_LABELS[d.slug] ?? d.slug}
            {d.adminOnly && <Chip label="Admin" size="small" sx={{ ml: 1 }} />}
          </Button>
        ))}
      </Stack>
    </Section>
  );
}
