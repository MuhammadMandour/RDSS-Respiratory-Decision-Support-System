import { useState } from 'react';
import { Box, Button, MenuItem, Paper, Stack, Tab, Tabs, TablePagination, TextField } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useAppointments, useClinicians, type AppointmentStatus } from './api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { humanize, toDateInputValue } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { AppointmentsTable } from './AppointmentsTable';
import { BookAppointmentDialog } from './BookAppointmentDialog';
import { AvailabilityEditor } from './AvailabilityEditor';

const STATUSES: AppointmentStatus[] = ['REQUESTED', 'SCHEDULED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

export function AppointmentsPage() {
  const { user, hasRole } = useAuth();
  const [tab, setTab] = useState<'list' | 'availability'>('list');
  const [booking, setBooking] = useState(false);
  const [from, setFrom] = useState(toDateInputValue(new Date()));
  const [to, setTo] = useState('');
  const [status, setStatus] = useState<AppointmentStatus | ''>('');
  const [clinicianId, setClinicianId] = useState<number | ''>(hasRole('CLINICIAN') ? user!.id : '');
  const [page, setPage] = useState(0);
  const clinicians = useClinicians();

  const { data, isPending, error, refetch } = useAppointments({
    from: from ? new Date(`${from}T00:00`).toISOString() : undefined,
    to: to ? new Date(`${to}T23:59:59`).toISOString() : undefined,
    status: status || undefined,
    clinicianId: clinicianId || undefined,
    page,
    size: 25,
  });

  return (
    <>
      <PageHeader
        title="Appointments"
        actions={
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setBooking(true)}>
            {hasRole('PATIENT') ? 'Request appointment' : 'Book appointment'}
          </Button>
        }
      />
      {hasRole('CLINICIAN', 'ADMIN') && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab value="list" label="Appointments" />
          <Tab value="availability" label="Availability" />
        </Tabs>
      )}
      {tab === 'availability' ? (
        <AvailabilityEditor />
      ) : (
        <Paper>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 2 }}>
            <TextField size="small" label="From" type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField size="small" label="To" type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField size="small" select label="Status" value={status} onChange={(e) => { setStatus(e.target.value as AppointmentStatus | ''); setPage(0); }} sx={{ minWidth: 160 }}>
              <MenuItem value="">All</MenuItem>
              {STATUSES.map((s) => <MenuItem key={s} value={s}>{humanize(s)}</MenuItem>)}
            </TextField>
            {!hasRole('PATIENT') && (
              <TextField size="small" select label="Clinician" value={clinicianId} onChange={(e) => { setClinicianId(e.target.value === '' ? '' : Number(e.target.value)); setPage(0); }} sx={{ minWidth: 200 }}>
                <MenuItem value="">All</MenuItem>
                {clinicians.data?.map((c) => <MenuItem key={c.id} value={c.id}>{c.fullName}</MenuItem>)}
              </TextField>
            )}
          </Stack>
          {isPending && <LoadingState />}
          {error && <Box sx={{ p: 2 }}><ErrorState error={error} onRetry={() => void refetch()} /></Box>}
          {data && data.totalElements === 0 && <EmptyState title="No appointments match these filters" />}
          {data && data.totalElements > 0 && (
            <>
              <AppointmentsTable rows={data.content} showPatient={!hasRole('PATIENT')} />
              <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={data.size}
                rowsPerPageOptions={[data.size]} onPageChange={(_, p) => setPage(p)} />
            </>
          )}
        </Paper>
      )}
      {booking && <BookAppointmentDialog onClose={() => setBooking(false)} />}
    </>
  );
}
