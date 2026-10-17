import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { APPOINTMENT_TYPES, appointmentsApi, useClinicians, useSlots, type AppointmentType } from './api';
import { patientsApi, type PatientSummary } from '../patients/api';
import { useAuth } from '../auth/AuthContext';
import { toApiRequestError } from '../shared/api/client';
import { formatTime, humanize, localInputToIso, toDateInputValue } from '../shared/format';
import { LoadingState } from '../shared/components/QueryState';

/**
 * Patients choose from offered slots. Staff may also pick a free-form time
 * (the backend still rejects overlaps).
 */
export function BookAppointmentDialog({
  onClose,
  fixedPatient,
  rescheduleOf,
}: {
  onClose: () => void;
  fixedPatient?: { id: number; name: string };
  rescheduleOf?: { id: number; clinicianId: number; type: AppointmentType; reason?: string };
}) {
  const { hasRole } = useAuth();
  const isPatient = hasRole('PATIENT');
  const qc = useQueryClient();
  const clinicians = useClinicians();
  const [clinicianId, setClinicianId] = useState<number | undefined>(rescheduleOf?.clinicianId);
  const [patient, setPatient] = useState<{ id: number; name: string } | null>(fixedPatient ?? null);
  const [date, setDate] = useState(toDateInputValue(new Date(Date.now() + 86_400_000)));
  const [slot, setSlot] = useState<string | null>(null);
  const [custom, setCustom] = useState(false);
  const [customTime, setCustomTime] = useState('');
  const [duration, setDuration] = useState('30');
  const [type, setType] = useState<AppointmentType>(rescheduleOf?.type ?? 'CONSULTATION');
  const [reason, setReason] = useState(rescheduleOf?.reason ?? '');
  const slots = useSlots(custom ? undefined : clinicianId, date, date);

  useEffect(() => setSlot(null), [clinicianId, date]);

  const m = useMutation({
    mutationFn: () => {
      const startsAt = custom ? localInputToIso(customTime) : slot!;
      const durationMinutes = custom ? Number(duration) : undefined;
      if (rescheduleOf) return appointmentsApi.reschedule(rescheduleOf.id, startsAt, durationMinutes);
      return appointmentsApi.book({ patientId: patient?.id, clinicianId: clinicianId!, startsAt, durationMinutes, type, reason: reason.trim() || undefined });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['appointments'] });
      void qc.invalidateQueries({ queryKey: ['slots'] });
      void qc.invalidateQueries({ queryKey: ['patients'] });
      onClose();
    },
  });

  const ready = !!clinicianId && (isPatient || !!patient) && (custom ? !!customTime : !!slot);
  const bookable = clinicians.data?.filter((c) => c.acceptingAppointments) ?? [];

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{rescheduleOf ? 'Reschedule appointment' : isPatient ? 'Request an appointment' : 'Book appointment'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          {!isPatient && !fixedPatient && !rescheduleOf && <PatientPicker value={patient} onChange={setPatient} />}
          <TextField select label="Clinician" value={clinicianId ?? ''} disabled={!!rescheduleOf}
            onChange={(e) => setClinicianId(Number(e.target.value) || undefined)}>
            {bookable.map((c) => <MenuItem key={c.id} value={c.id}>{c.fullName}{c.specialty ? ` — ${c.specialty}` : ''}</MenuItem>)}
          </TextField>
          {!isPatient && (
            <FormControlLabel control={<Switch checked={custom} onChange={(e) => setCustom(e.target.checked)} />} label="Choose a custom time (outside offered slots)" />
          )}
          {custom ? (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField label="Start" type="datetime-local" value={customTime} onChange={(e) => setCustomTime(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} fullWidth />
              <TextField label="Minutes" type="number" value={duration} onChange={(e) => setDuration(e.target.value)} slotProps={{ htmlInput: { min: 5, max: 480 } }} sx={{ width: { sm: 140 } }} />
            </Stack>
          ) : (
            <>
              <TextField label="Date" type="date" value={date} onChange={(e) => setDate(e.target.value)}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: toDateInputValue(new Date()) } }} />
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>Available times</Typography>
                {!clinicianId && <Typography color="text.secondary" variant="body2">Select a clinician first.</Typography>}
                {slots.isFetching && <LoadingState label="Loading slots…" />}
                {slots.error && <Alert severity="error">{toApiRequestError(slots.error).message}</Alert>}
                {slots.data && slots.data.length === 0 && <Typography color="text.secondary" variant="body2">No free slots on this date.</Typography>}
                <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }} role="listbox" aria-label="Available times">
                  {slots.data?.map((s) => (
                    <Chip key={s.startsAt} label={formatTime(s.startsAt)} clickable role="option" aria-selected={slot === s.startsAt}
                      color={slot === s.startsAt ? 'primary' : 'default'} variant={slot === s.startsAt ? 'filled' : 'outlined'} onClick={() => setSlot(s.startsAt)} />
                  ))}
                </Stack>
              </Box>
            </>
          )}
          {!rescheduleOf && (
            <>
              <TextField select label="Type" value={type} onChange={(e) => setType(e.target.value as AppointmentType)}>
                {APPOINTMENT_TYPES.map((t) => <MenuItem key={t} value={t}>{humanize(t)}</MenuItem>)}
              </TextField>
              <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2} />
            </>
          )}
          {isPatient && <Alert severity="info">Your request will be confirmed by the clinic.</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ready || m.isPending} onClick={() => m.mutate()}>
          {rescheduleOf ? 'Reschedule' : isPatient ? 'Request' : 'Book'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export function PatientPicker({ value, onChange }: { value: { id: number; name: string } | null; onChange: (v: { id: number; name: string } | null) => void }) {
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const id = window.setTimeout(() => setQ(input.trim()), 300);
    return () => window.clearTimeout(id);
  }, [input]);
  const results = useQuery({ queryKey: ['patients', 'picker', q], queryFn: () => patientsApi.search(q, 0, 10) });
  const options = useMemo(
    () => (results.data?.content ?? []).map((p: PatientSummary) => ({ id: p.id, name: `${p.firstName} ${p.lastName} (${p.mrn})` })),
    [results.data],
  );
  return (
    <Autocomplete
      options={options}
      value={value}
      onChange={(_, v) => onChange(v)}
      inputValue={input}
      onInputChange={(_, v) => setInput(v)}
      getOptionLabel={(o) => o.name}
      isOptionEqualToValue={(a, b) => a.id === b.id}
      filterOptions={(x) => x}
      loading={results.isFetching}
      renderInput={(params) => <TextField {...params} label="Patient" placeholder="Search name or MRN" />}
    />
  );
}
