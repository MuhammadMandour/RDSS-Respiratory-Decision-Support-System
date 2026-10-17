import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import PersonRemoveOutlinedIcon from '@mui/icons-material/PersonRemoveOutlined';
import PsychologyOutlinedIcon from '@mui/icons-material/PsychologyOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { fullName, patientKeys, patientsApi, useCareTeam, usePatient, type ContactUpdate, type Patient } from './api';
import { useAuth } from '../auth/AuthContext';
import { PageHeader } from '../shared/components/PageHeader';
import { DetailList } from '../shared/components/DetailList';
import { ErrorState, LoadingState, EmptyState } from '../shared/components/QueryState';
import { FormGrid } from '../shared/components/FormGrid';
import { emptyToUndefined, formatDate, formatDateTime, humanize, orDash } from '../shared/format';
import { fieldErrors } from '../shared/forms';
import { PatientFormDialog } from './PatientFormDialog';
import { RespiratoryHistoryPanel } from '../respiratory/RespiratoryHistoryPanel';
import { ObservationsPanel } from '../respiratory/ObservationsPanel';
import { PftPanel } from '../respiratory/PftPanel';
import { EncountersPanel } from '../encounters/EncountersPanel';
import { PrescriptionsPanel, TreatmentPlansPanel } from '../encounters/TreatmentPanel';
import { useAppointments, useClinicians } from '../appointments/api';
import { AppointmentsTable } from '../appointments/AppointmentsTable';
import { BookAppointmentDialog } from '../appointments/BookAppointmentDialog';
import { ImagingPanel } from '../imaging/ImagingPanel';
import { DiagnosesPanel } from '../diagnoses/DiagnosesPanel';
import { PatientInvoices } from '../billing/PatientInvoices';

type TabKey = 'overview' | 'respiratory' | 'encounters' | 'diagnoses' | 'treatment' | 'imaging' | 'appointments' | 'billing' | 'care-team';

/** Staff route: /patients/:id. Patient route: /my-record (always the signed-in patient's own record). */
export function PatientDetailPage({ self = false }: { self?: boolean }) {
  const { user } = useAuth();
  const params = useParams();
  const id = self ? (user?.patientId ?? 0) : Number(params.id);
  const { data, isPending, error, refetch } = usePatient(id);
  if (self && !user?.patientId) return <Alert severity="warning">Your account is not linked to a patient record.</Alert>;
  if (isPending) return <LoadingState />;
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />;
  return <PatientView patient={data} self={self} />;
}

function PatientView({ patient: p, self }: { patient: Patient; self: boolean }) {
  const { hasRole } = useAuth();
  const [search, setSearch] = useSearchParams();
  const isClinician = hasRole('CLINICIAN');
  const isAdmin = hasRole('ADMIN');
  const clinical = !isAdmin; // admins see demographics only (enforced server-side too)
  const tabs: { key: TabKey; label: string }[] = [
    { key: 'overview', label: 'Overview' },
    ...(clinical ? [{ key: 'respiratory' as const, label: 'Respiratory' }, { key: 'encounters' as const, label: 'Encounters' }, { key: 'diagnoses' as const, label: 'Diagnoses' }, { key: 'treatment' as const, label: 'Treatment' }, { key: 'imaging' as const, label: 'Imaging' }] : []),
    { key: 'appointments', label: 'Appointments' },
    { key: 'billing', label: 'Billing' },
    ...(!self ? [{ key: 'care-team' as const, label: 'Care team' }] : []),
  ];
  const tab = (tabs.find((t) => t.key === search.get('tab'))?.key ?? 'overview') as TabKey;
  const [editing, setEditing] = useState(false);

  return (
    <>
      <PageHeader
        title={self ? 'My health record' : fullName(p)}
        subtitle={`${p.mrn} · ${formatDate(p.dateOfBirth)} (${p.age} y) · ${humanize(p.sex)}`}
        crumbs={self ? undefined : [{ label: 'Patients', to: '/patients' }, { label: fullName(p) }]}
        actions={
          <>
            {!p.active && <Chip label="Inactive" />}
            {isClinician && <Button variant="contained" startIcon={<PsychologyOutlinedIcon />} component={RouterLink} to={`/patients/${p.id}/cdss`}>Decision support</Button>}
            {!self && <Button startIcon={<EditOutlinedIcon />} onClick={() => setEditing(true)}>Edit</Button>}
            {self && <Button startIcon={<EditOutlinedIcon />} onClick={() => setEditing(true)}>Update contact details</Button>}
            {isAdmin && <ActiveToggle patient={p} />}
          </>
        }
      />
      <Tabs value={tab} onChange={(_, v: TabKey) => setSearch(v === 'overview' ? {} : { tab: v }, { replace: true })} variant="scrollable" allowScrollButtonsMobile sx={{ mb: 2 }}>
        {tabs.map((t) => <Tab key={t.key} value={t.key} label={t.label} />)}
      </Tabs>

      {tab === 'overview' && <Overview p={p} clinical={clinical} />}
      {tab === 'respiratory' && (
        <Stack spacing={2}>
          <RespiratoryHistoryPanel patientId={p.id} canWrite={isClinician} />
          <ObservationsPanel patientId={p.id} canWrite={isClinician} />
          <PftPanel patientId={p.id} canWrite={isClinician} />
        </Stack>
      )}
      {tab === 'encounters' && <EncountersPanel patientId={p.id} canWrite={isClinician} />}
      {tab === 'treatment' && (
        <Stack spacing={2}>
          <TreatmentPlansPanel patientId={p.id} canWrite={isClinician} />
          <PrescriptionsPanel patientId={p.id} canWrite={isClinician} />
        </Stack>
      )}
      {tab === 'imaging' && <ImagingPanel patientId={p.id} canWrite={isClinician} />}
      {tab === 'diagnoses' && <DiagnosesPanel patientId={p.id} canWrite={isClinician} />}
      {tab === 'billing' && <PatientInvoices patient={p} />}
      {tab === 'appointments' && <PatientAppointments patient={p} />}
      {tab === 'care-team' && <CareTeam patientId={p.id} canRemove={isAdmin} />}

      {editing && !self && <PatientFormDialog open patient={p} clinicalFields={isClinician} onClose={() => setEditing(false)} />}
      {editing && self && <ContactDialog patient={p} onClose={() => setEditing(false)} />}
    </>
  );
}

function Overview({ p, clinical }: { p: Patient; clinical: boolean }) {
  return (
    <Stack spacing={2}>
      <Paper sx={{ p: 2 }}>
        <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Contact</Typography>
        <DetailList items={[
          { label: 'Phone', value: orDash(p.phone) },
          { label: 'Email', value: orDash(p.email) },
          { label: 'Address', value: [p.addressLine, p.city, p.postalCode, p.country].filter(Boolean).join(', ') || '—' },
          { label: 'Emergency contact', value: p.emergencyContactName ? `${p.emergencyContactName} (${orDash(p.emergencyContactRelation)})` : '—' },
          { label: 'Emergency phone', value: orDash(p.emergencyContactPhone) },
          { label: 'Primary clinician', value: orDash(p.primaryClinician?.fullName) },
          { label: 'Portal account', value: p.hasPortalAccount ? 'Yes' : 'No' },
          { label: 'Registered', value: formatDateTime(p.createdAt) },
        ]} />
      </Paper>
      {clinical && (
        <Paper sx={{ p: 2 }}>
          <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Clinical background</Typography>
          {p.allergies && p.allergies.toLowerCase() !== 'none known' && <Alert severity="warning" sx={{ mb: 2 }}>Allergies: {p.allergies}</Alert>}
          <DetailList items={[
            { label: 'Known respiratory conditions', value: orDash(p.knownRespiratoryConditions) },
            { label: 'Smoking', value: `${humanize(p.smokingStatus)}${p.packYears != null ? ` · ${p.packYears} pack-years` : ''}` },
            { label: 'Environmental exposures', value: orDash(p.environmentalExposures) },
            { label: 'Allergies', value: orDash(p.allergies) },
            { label: 'Current medications', value: orDash(p.currentMedications) },
            { label: 'Medical history', value: orDash(p.medicalHistory) },
          ]} />
        </Paper>
      )}
    </Stack>
  );
}

function PatientAppointments({ patient }: { patient: Patient }) {
  const { data, isPending, error } = useAppointments({ patientId: patient.id, size: 50, sort: 'desc' });
  const [booking, setBooking] = useState(false);
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Appointments</Typography>
        <Button onClick={() => setBooking(true)}>Book</Button>
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      {data && data.totalElements === 0 && <EmptyState title="No appointments" />}
      {data && data.totalElements > 0 && <AppointmentsTable rows={data.content} showPatient={false} />}
      {booking && <BookAppointmentDialog onClose={() => setBooking(false)} fixedPatient={{ id: patient.id, name: fullName(patient) }} />}
    </Paper>
  );
}

function CareTeam({ patientId, canRemove }: { patientId: number; canRemove: boolean }) {
  const { data, isPending, error } = useCareTeam(patientId);
  const clinicians = useClinicians();
  const [selected, setSelected] = useState<number | ''>('');
  const qc = useQueryClient();
  const onDone = (team: unknown) => {
    qc.setQueryData(patientKeys.careTeam(patientId), team);
    setSelected('');
  };
  const assign = useMutation({ mutationFn: (cid: number) => patientsApi.assign(patientId, cid), onSuccess: onDone });
  const remove = useMutation({ mutationFn: (cid: number) => patientsApi.unassign(patientId, cid), onSuccess: onDone });
  const memberIds = new Set(data?.map((m) => m.clinicianId));
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2" sx={{ mb: 1 }}>Care team</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Clinicians on the care team can access this patient's clinical record.</Typography>
      {isPending && <LoadingState />}
      {(error || assign.error || remove.error) && <ErrorState error={error ?? assign.error ?? remove.error} />}
      <List dense>
        {data?.map((m) => (
          <ListItem key={m.clinicianId} secondaryAction={canRemove && m.reason !== 'PRIMARY' ? (
            <Tooltip title="Remove from care team">
              <IconButton edge="end" aria-label={`Remove ${m.fullName}`} onClick={() => remove.mutate(m.clinicianId)}><PersonRemoveOutlinedIcon /></IconButton>
            </Tooltip>
          ) : undefined}>
            <ListItemText primary={m.fullName} secondary={`${humanize(m.reason)} · since ${formatDate(m.addedAt)}`} />
          </ListItem>
        ))}
      </List>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
        <TextField select size="small" label="Add clinician" value={selected} onChange={(e) => setSelected(Number(e.target.value) || '')} sx={{ minWidth: 260 }}>
          {clinicians.data?.filter((c) => !memberIds.has(c.id)).map((c) => <MenuItem key={c.id} value={c.id}>{c.fullName}</MenuItem>)}
        </TextField>
        <Button variant="outlined" disabled={!selected || assign.isPending} onClick={() => selected && assign.mutate(selected)}>Assign</Button>
      </Stack>
    </Paper>
  );
}

function ActiveToggle({ patient }: { patient: Patient }) {
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => patientsApi.setActive(patient.id, !patient.active),
    onSuccess: (p) => { qc.setQueryData(patientKeys.detail(p.id), p); void qc.invalidateQueries({ queryKey: ['patients', 'list'] }); },
  });
  return <Button color={patient.active ? 'warning' : 'primary'} onClick={() => m.mutate()} disabled={m.isPending}>{patient.active ? 'Deactivate' : 'Reactivate'}</Button>;
}

function ContactDialog({ patient, onClose }: { patient: Patient; onClose: () => void }) {
  const [f, setF] = useState<Record<keyof ContactUpdate, string>>({
    phone: patient.phone ?? '', email: patient.email ?? '', addressLine: patient.addressLine ?? '', city: patient.city ?? '',
    postalCode: patient.postalCode ?? '', country: patient.country ?? '', emergencyContactName: patient.emergencyContactName ?? '',
    emergencyContactPhone: patient.emergencyContactPhone ?? '', emergencyContactRelation: patient.emergencyContactRelation ?? '',
  });
  const qc = useQueryClient();
  const m = useMutation({
    // Blank fields are sent as absent (cleared) rather than "" which fails format validation.
    mutationFn: () => patientsApi.updateContact(patient.id, emptyToUndefined(f)),
    onSuccess: (p) => { qc.setQueryData(patientKeys.detail(p.id), p); onClose(); },
  });
  const errs = fieldErrors(m.error);
  const field = (k: keyof ContactUpdate, label: string) => (
    <TextField label={label} value={f[k]} onChange={(e) => setF((s) => ({ ...s, [k]: e.target.value }))} error={!!errs[k]} helperText={errs[k]} fullWidth />
  );
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Update contact details</DialogTitle>
      <DialogContent dividers>
        {m.error && !Object.keys(errs).length && <ErrorState error={m.error} />}
        <Box sx={{ mt: 1 }}>
          <FormGrid>
            {field('phone', 'Phone')}
            {field('email', 'Email')}
            {field('addressLine', 'Address')}
            {field('city', 'City')}
            {field('postalCode', 'Postal code')}
            {field('country', 'Country')}
            {field('emergencyContactName', 'Emergency contact')}
            {field('emergencyContactPhone', 'Emergency phone')}
            {field('emergencyContactRelation', 'Relationship')}
          </FormGrid>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => m.mutate()} disabled={m.isPending}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}
