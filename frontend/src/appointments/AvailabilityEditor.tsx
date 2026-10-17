import { useEffect, useState } from 'react';
import { Alert, Box, Button, IconButton, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { appointmentsApi, DAYS, useAvailability, useClinicians, type AvailabilityWindow, type DayOfWeek } from './api';
import { useAuth } from '../auth/AuthContext';
import { humanize } from '../shared/format';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { toApiRequestError } from '../shared/api/client';

/** Weekly schedule editor: clinicians edit their own, administrators any clinician's. */
export function AvailabilityEditor() {
  const { user, hasRole } = useAuth();
  const clinicians = useClinicians();
  const [clinicianId, setClinicianId] = useState<number | undefined>(hasRole('CLINICIAN') ? user!.id : undefined);
  const current = useAvailability(clinicianId);
  const [rows, setRows] = useState<AvailabilityWindow[]>([]);
  const qc = useQueryClient();

  useEffect(() => {
    if (current.data) setRows(current.data.map((w) => ({ ...w, startTime: w.startTime.slice(0, 5), endTime: w.endTime.slice(0, 5) })));
  }, [current.data]);

  const save = useMutation({
    mutationFn: () => appointmentsApi.replaceAvailability(clinicianId!, rows.map(({ dayOfWeek, startTime, endTime, slotMinutes }) => ({ dayOfWeek, startTime, endTime, slotMinutes }))),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['availability', clinicianId] });
      void qc.invalidateQueries({ queryKey: ['slots'] });
    },
  });

  const update = (i: number, patch: Partial<AvailabilityWindow>) => setRows((r) => r.map((w, j) => (j === i ? { ...w, ...patch } : w)));

  return (
    <Paper sx={{ p: 2 }}>
      <Stack spacing={2}>
        <Typography variant="h3" component="h2">Weekly availability</Typography>
        <Typography variant="body2" color="text.secondary">
          Times are in the clinic time zone. Saving replaces the whole schedule; existing appointments are not affected.
        </Typography>
        {hasRole('ADMIN') && (
          <TextField select label="Clinician" value={clinicianId ?? ''} onChange={(e) => setClinicianId(Number(e.target.value) || undefined)} sx={{ maxWidth: 360 }}>
            {clinicians.data?.map((c) => <MenuItem key={c.id} value={c.id}>{c.fullName}</MenuItem>)}
          </TextField>
        )}
        {current.isFetching && <LoadingState />}
        {current.error && <ErrorState error={current.error} />}
        {clinicianId && !current.isFetching && (
          <>
            {rows.map((w, i) => (
              <Box key={i} sx={{ display: 'grid', gap: 1, gridTemplateColumns: { xs: '1fr 1fr', md: '180px 140px 140px 140px 48px' }, alignItems: 'center' }}>
                <TextField select size="small" label="Day" value={w.dayOfWeek} onChange={(e) => update(i, { dayOfWeek: e.target.value as DayOfWeek })}>
                  {DAYS.map((d) => <MenuItem key={d} value={d}>{humanize(d)}</MenuItem>)}
                </TextField>
                <TextField size="small" type="time" label="Start" value={w.startTime} onChange={(e) => update(i, { startTime: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                <TextField size="small" type="time" label="End" value={w.endTime} onChange={(e) => update(i, { endTime: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                <TextField size="small" type="number" label="Slot (min)" value={w.slotMinutes} onChange={(e) => update(i, { slotMinutes: Number(e.target.value) })} slotProps={{ htmlInput: { min: 5, max: 240 } }} />
                <IconButton aria-label="Remove window" onClick={() => setRows((r) => r.filter((_, j) => j !== i))}><DeleteOutlinedIcon /></IconButton>
              </Box>
            ))}
            {rows.length === 0 && <Typography color="text.secondary">No availability defined — patients cannot book online.</Typography>}
            {save.error && <Alert severity="error">{toApiRequestError(save.error).message}</Alert>}
            {save.isSuccess && !save.isPending && <Alert severity="success">Availability saved.</Alert>}
            <Stack direction="row" spacing={1}>
              <Button startIcon={<AddIcon />} onClick={() => setRows((r) => [...r, { dayOfWeek: 'MONDAY', startTime: '09:00', endTime: '12:00', slotMinutes: 30 }])}>Add window</Button>
              <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save schedule</Button>
            </Stack>
          </>
        )}
      </Stack>
    </Paper>
  );
}
