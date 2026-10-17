import { Link as RouterLink } from 'react-router-dom';
import { Alert, Box, Button, Chip, List, ListItemButton, ListItemText, Paper, Stack, Typography } from '@mui/material';
import { useAuth } from '../auth/AuthContext';
import { useAppointments } from '../appointments/api';
import { AppointmentsTable } from '../appointments/AppointmentsTable';
import { useMyEncounters } from '../encounters/api';
import { usePrescriptions } from '../encounters/api';
import { SystemStatusCard } from '../settings/SystemStatusCard';
import { useAudit } from '../settings/adminApi';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { PageHeader } from '../shared/components/PageHeader';
import { formatDateTime, humanize } from '../shared/format';

const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d; };
const endOfToday = () => { const d = new Date(); d.setHours(23, 59, 59, 999); return d; };

export function DashboardPage() {
  const { user } = useAuth();
  return (
    <>
      <PageHeader title={`Welcome, ${user!.fullName}`} subtitle={humanize(user!.role)}
        actions={user!.role !== 'PATIENT' ? <Button component={RouterLink} to="/reports" variant="outlined">Reports</Button> : undefined} />
      <Alert severity="info" sx={{ mb: 2 }}>
        Development system. Decision-support output is advisory only and must be reviewed by a qualified clinician.
      </Alert>
      {user!.role === 'CLINICIAN' && <ClinicianDashboard />}
      {user!.role === 'PATIENT' && <PatientDashboard patientId={user!.patientId} />}
      {user!.role === 'ADMIN' && <AdminDashboard />}
    </>
  );
}

function Section({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
        <Typography variant="h3" component="h2">{title}</Typography>
        {action}
      </Stack>
      {children}
    </Paper>
  );
}

function ClinicianDashboard() {
  const { user } = useAuth();
  const today = useAppointments({ clinicianId: user!.id, from: startOfToday().toISOString(), to: endOfToday().toISOString(), size: 50 });
  const upcoming = useAppointments({ clinicianId: user!.id, from: endOfToday().toISOString(), size: 10 });
  const open = useMyEncounters('IN_PROGRESS');
  return (
    <Stack spacing={2}>
      <Section title="Today's appointments" action={<Button component={RouterLink} to="/appointments">All appointments</Button>}>
        {today.isPending && <LoadingState />}
        {today.error && <ErrorState error={today.error} />}
        {today.data?.totalElements === 0 && <EmptyState title="No appointments today" />}
        {!!today.data?.totalElements && <AppointmentsTable rows={today.data.content} />}
      </Section>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <Section title="Open encounters">
          {open.isPending && <LoadingState />}
          {open.error && <ErrorState error={open.error} />}
          {open.data?.totalElements === 0 && <EmptyState title="No draft encounters" />}
          <List dense>
            {open.data?.content.map((e) => (
              <ListItemButton key={e.id} component={RouterLink} to={`/encounters/${e.id}`}>
                <ListItemText primary={`${e.patientName ?? 'Patient'} — ${e.chiefComplaint ?? 'No chief complaint'}`} secondary={`Started ${formatDateTime(e.startedAt)}`} />
              </ListItemButton>
            ))}
          </List>
        </Section>
        <Section title="Upcoming">
          {upcoming.isPending && <LoadingState />}
          {upcoming.data?.totalElements === 0 && <EmptyState title="Nothing scheduled" />}
          <List dense>
            {upcoming.data?.content.map((a) => (
              <ListItemButton key={a.id} component={RouterLink} to={`/patients/${a.patientId}?tab=appointments`}>
                <ListItemText primary={`${formatDateTime(a.startsAt)} · ${a.patientName}`} secondary={`${humanize(a.type)} · ${humanize(a.status)}`} />
              </ListItemButton>
            ))}
          </List>
        </Section>
      </Box>
    </Stack>
  );
}

function PatientDashboard({ patientId }: { patientId?: number }) {
  const upcoming = useAppointments({ from: new Date().toISOString(), size: 10 });
  const rx = usePrescriptions(patientId ?? 0);
  if (!patientId) return <Alert severity="warning">Your account is not linked to a patient record.</Alert>;
  const active = rx.data?.filter((p) => p.status === 'ACTIVE') ?? [];
  return (
    <Stack spacing={2}>
      <Section title="Upcoming appointments" action={<Button component={RouterLink} to="/appointments">Manage</Button>}>
        {upcoming.isPending && <LoadingState />}
        {upcoming.error && <ErrorState error={upcoming.error} />}
        {upcoming.data?.totalElements === 0 && <EmptyState title="No upcoming appointments" action={<Button component={RouterLink} to="/appointments" variant="outlined">Request one</Button>} />}
        {!!upcoming.data?.totalElements && <AppointmentsTable rows={upcoming.data.content} showPatient={false} />}
      </Section>
      <Section title="Active medications" action={<Button component={RouterLink} to="/my-record?tab=treatment">View all</Button>}>
        {rx.isPending && <LoadingState />}
        {rx.error && <ErrorState error={rx.error} />}
        {rx.data && active.length === 0 && <EmptyState title="No active prescriptions" />}
        <List dense>
          {active.map((p) => (
            <ListItemText key={p.id} primary={p.medicationName} secondary={`${p.dose} · ${p.route} · ${p.frequency}`} sx={{ px: 2 }} />
          ))}
        </List>
      </Section>
    </Stack>
  );
}

function AdminDashboard() {
  const denied = useAudit({ outcome: 'DENIED', size: 8 });
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
      <SystemStatusCard />
      <Section title="Recent denied access attempts" action={<Button component={RouterLink} to="/admin/audit">Audit log</Button>}>
        {denied.isPending && <LoadingState />}
        {denied.error && <ErrorState error={denied.error} />}
        {denied.data?.totalElements === 0 && <EmptyState title="No denied access recorded" />}
        <List dense>
          {denied.data?.content.map((a) => (
            <ListItemText key={a.id} sx={{ px: 1 }}
              primary={<>{a.actorUsername ?? 'anonymous'} · {a.action} {a.resourceType} {a.resourceId ?? ''} <Chip size="small" label={a.outcome} color="error" variant="outlined" /></>}
              secondary={formatDateTime(a.occurredAt)} />
          ))}
        </List>
      </Section>
    </Box>
  );
}
