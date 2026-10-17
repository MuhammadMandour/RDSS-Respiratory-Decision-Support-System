import { useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import {
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Link,
  ListItemText,
  Menu,
  MenuItem,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
} from '@mui/material';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { appointmentsApi, statusColor, type Appointment, type AppointmentStatus } from './api';
import { useAuth } from '../auth/AuthContext';
import { formatDateTime, formatTime, humanize } from '../shared/format';
import { ErrorState } from '../shared/components/QueryState';
import { BookAppointmentDialog } from './BookAppointmentDialog';

export function AppointmentsTable({ rows, showPatient = true }: { rows: Appointment[]; showPatient?: boolean }) {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [menu, setMenu] = useState<{ anchor: HTMLElement; a: Appointment } | null>(null);
  const [cancelling, setCancelling] = useState<Appointment | null>(null);
  const [rescheduling, setRescheduling] = useState<Appointment | null>(null);

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['appointments'] });
    void qc.invalidateQueries({ queryKey: ['slots'] });
  };
  const status = useMutation({
    mutationFn: ({ id, s }: { id: number; s: AppointmentStatus }) => appointmentsApi.setStatus(id, s),
    onSuccess: refresh,
  });
  const start = useMutation({
    mutationFn: (id: number) => appointmentsApi.startEncounter(id),
    onSuccess: (e) => {
      refresh();
      navigate(`/encounters/${e.id}`);
    },
  });

  const staff = hasRole('ADMIN', 'CLINICIAN');
  const actionsFor = (a: Appointment) => {
    const open = a.status === 'REQUESTED' || a.status === 'SCHEDULED';
    const items: { label: string; run: () => void }[] = [];
    if (staff && a.status === 'REQUESTED') items.push({ label: 'Confirm', run: () => status.mutate({ id: a.id, s: 'SCHEDULED' }) });
    if (staff && a.status === 'SCHEDULED') items.push({ label: 'Check in', run: () => status.mutate({ id: a.id, s: 'CHECKED_IN' }) });
    if (hasRole('CLINICIAN') && !a.encounterId && (open || a.status === 'CHECKED_IN'))
      items.push({ label: 'Start encounter', run: () => start.mutate(a.id) });
    if (staff && (a.status === 'SCHEDULED' || a.status === 'CHECKED_IN')) items.push({ label: 'Mark completed', run: () => status.mutate({ id: a.id, s: 'COMPLETED' }) });
    if (staff && open && new Date(a.startsAt) < new Date()) items.push({ label: 'Mark no-show', run: () => status.mutate({ id: a.id, s: 'NO_SHOW' }) });
    if (open) items.push({ label: 'Reschedule', run: () => setRescheduling(a) });
    if (open) items.push({ label: 'Cancel', run: () => setCancelling(a) });
    return items;
  };

  return (
    <>
      {(status.error || start.error) && <ErrorState error={status.error ?? start.error} />}
      <TableContainer>
        <Table size="small" aria-label="Appointments">
          <TableHead>
            <TableRow>
              <TableCell>When</TableCell>
              {showPatient && <TableCell>Patient</TableCell>}
              <TableCell>Clinician</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Type</TableCell>
              <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>Reason</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.id}>
                <TableCell>{formatDateTime(a.startsAt)}–{formatTime(a.endsAt)}</TableCell>
                {showPatient && (
                  <TableCell>
                    {staff ? <Link component={RouterLink} to={`/patients/${a.patientId}`}>{a.patientName}</Link> : a.patientName}
                    <ListItemText secondary={a.patientMrn} sx={{ m: 0 }} />
                  </TableCell>
                )}
                <TableCell>{a.clinicianName}</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{humanize(a.type)}</TableCell>
                <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>{a.reason ?? '—'}</TableCell>
                <TableCell>
                  <Chip size="small" label={humanize(a.status)} color={statusColor(a.status)} variant="outlined" />
                  {a.encounterId && (
                    <Link component={RouterLink} to={`/encounters/${a.encounterId}`} sx={{ ml: 1 }} variant="body2">Encounter</Link>
                  )}
                </TableCell>
                <TableCell align="right">
                  {actionsFor(a).length > 0 && (
                    <IconButton size="small" aria-label={`Actions for appointment ${a.id}`} onClick={(e) => setMenu({ anchor: e.currentTarget, a })}>
                      <MoreVertIcon fontSize="small" />
                    </IconButton>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Menu anchorEl={menu?.anchor} open={!!menu} onClose={() => setMenu(null)}>
        {menu && actionsFor(menu.a).map((i) => (
          <MenuItem key={i.label} onClick={() => { setMenu(null); i.run(); }}>{i.label}</MenuItem>
        ))}
      </Menu>
      {cancelling && <CancelDialog appointment={cancelling} onClose={() => setCancelling(null)} onDone={refresh} />}
      {rescheduling && (
        <BookAppointmentDialog
          onClose={() => setRescheduling(null)}
          rescheduleOf={{ id: rescheduling.id, clinicianId: rescheduling.clinicianId, type: rescheduling.type, reason: rescheduling.reason }}
        />
      )}
    </>
  );
}

function CancelDialog({ appointment, onClose, onDone }: { appointment: Appointment; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const m = useMutation({ mutationFn: () => appointmentsApi.cancel(appointment.id, reason.trim()), onSuccess: () => { onDone(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Cancel appointment</DialogTitle>
      <DialogContent>
        {m.error && <ErrorState error={m.error} />}
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required multiline minRows={2} autoFocus sx={{ mt: 1 }} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Keep appointment</Button>
        <Button color="error" variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>Cancel appointment</Button>
      </DialogActions>
    </Dialog>
  );
}
