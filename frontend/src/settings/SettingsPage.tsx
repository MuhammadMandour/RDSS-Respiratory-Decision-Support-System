import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import SaveOutlinedIcon from '@mui/icons-material/SaveOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { adminApi, useDepartments, useSettings, type Department, type Setting } from './adminApi';
import { PageHeader } from '../shared/components/PageHeader';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { SystemStatusCard } from './SystemStatusCard';
import { formatDateTime } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';

export function SettingsPage() {
  return (
    <>
      <PageHeader title="System settings" subtitle="Clinic configuration, departments and integration status" />
      <Stack spacing={2}>
        <SystemStatusCard />
        <SettingsTable />
        <Departments />
      </Stack>
    </>
  );
}

function SettingsTable() {
  const { data, isPending, error } = useSettings();
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Settings</Typography>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      {data && (
        <TableContainer>
          <Table size="small" aria-label="Settings">
            <TableHead>
              <TableRow>
                <TableCell>Setting</TableCell>
                <TableCell>Value</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Last change</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>{data.map((s) => <SettingRow key={s.key} s={s} />)}</TableBody>
          </Table>
        </TableContainer>
      )}
    </Paper>
  );
}

function SettingRow({ s }: { s: Setting }) {
  const [value, setValue] = useState(s.value);
  const qc = useQueryClient();
  const m = useMutation({ mutationFn: () => adminApi.updateSetting(s.key, value), onSuccess: () => qc.invalidateQueries({ queryKey: ['settings'] }) });
  return (
    <TableRow>
      <TableCell>
        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{s.key}</Typography>
        <Typography variant="caption" color="text.secondary">{s.description}</Typography>
      </TableCell>
      <TableCell>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <TextField size="small" value={value} onChange={(e) => setValue(e.target.value)} error={!!m.error}
            helperText={m.error ? toApiRequestError(m.error).message : undefined} slotProps={{ htmlInput: { 'aria-label': s.key } }} />
          <Tooltip title="Save">
            <span>
              <IconButton aria-label={`Save ${s.key}`} disabled={value === s.value || m.isPending} onClick={() => m.mutate()}><SaveOutlinedIcon /></IconButton>
            </span>
          </Tooltip>
        </Stack>
      </TableCell>
      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(s.updatedAt)}{s.updatedBy ? ` · ${s.updatedBy}` : ''}</TableCell>
    </TableRow>
  );
}

function Departments() {
  const { data, isPending, error } = useDepartments();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ['departments'] });
  const create = useMutation({ mutationFn: () => adminApi.createDepartment({ name, description, active: true }), onSuccess: () => { setName(''); setDescription(''); void refresh(); } });
  const toggle = useMutation({ mutationFn: (d: Department) => adminApi.updateDepartment(d.id, { ...d, active: !d.active }), onSuccess: refresh });
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2" sx={{ mb: 2 }}>Departments</Typography>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      <Stack spacing={1} sx={{ mb: 2 }}>
        {data?.map((d) => (
          <Stack key={d.id} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Box sx={{ flex: 1 }}>
              <Typography>{d.name} {!d.active && <Chip size="small" label="Inactive" />}</Typography>
              <Typography variant="caption" color="text.secondary">{d.description}</Typography>
            </Box>
            <Button size="small" onClick={() => toggle.mutate(d)}>{d.active ? 'Deactivate' : 'Activate'}</Button>
          </Stack>
        ))}
      </Stack>
      {create.error && <Alert severity="error" sx={{ mb: 1 }}>{toApiRequestError(create.error).message}</Alert>}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField size="small" label="New department" value={name} onChange={(e) => setName(e.target.value)} />
        <TextField size="small" label="Description" value={description} onChange={(e) => setDescription(e.target.value)} sx={{ flex: 1 }} />
        <Button variant="outlined" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>Add</Button>
      </Stack>
    </Paper>
  );
}
