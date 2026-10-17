import { useEffect, useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  MenuItem,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import PersonAddAltOutlinedIcon from '@mui/icons-material/PersonAddAltOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { adminApi, useDepartments, useUsers, type User } from './adminApi';
import { PASSWORD_HINT, type Role } from '../auth/api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { formatDateTime, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { toApiRequestError } from '../shared/api/client';

export function UsersPage() {
  const [role, setRole] = useState<Role | ''>('');
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<User | 'new' | null>(null);
  useEffect(() => {
    const id = window.setTimeout(() => { setQ(input.trim()); setPage(0); }, 300);
    return () => window.clearTimeout(id);
  }, [input]);
  const { data, isPending, error, refetch } = useUsers({ role: role || undefined, q: q || undefined, page });

  return (
    <>
      <PageHeader title="Users" subtitle="Accounts and role assignments"
        actions={<Button variant="contained" startIcon={<PersonAddAltOutlinedIcon />} onClick={() => setEditing('new')}>New user</Button>} />
      <Paper>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ p: 2 }}>
          <TextField size="small" label="Search" value={input} onChange={(e) => setInput(e.target.value)} />
          <TextField size="small" select label="Role" value={role} onChange={(e) => { setRole(e.target.value as Role | ''); setPage(0); }} sx={{ minWidth: 160 }}>
            <MenuItem value="">All</MenuItem>
            {(['ADMIN', 'CLINICIAN', 'PATIENT'] as const).map((r) => <MenuItem key={r} value={r}>{humanize(r)}</MenuItem>)}
          </TextField>
        </Stack>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} onRetry={() => void refetch()} />}
        {data?.totalElements === 0 && <EmptyState title="No users found" />}
        {!!data?.totalElements && (
          <>
            <TableContainer>
              <Table size="small" aria-label="Users">
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell>Username</TableCell>
                    <TableCell>Role</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Last login</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((u) => (
                    <TableRow key={u.id}>
                      <TableCell>{u.fullName}<Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{u.email}</Typography></TableCell>
                      <TableCell>{u.username}</TableCell>
                      <TableCell>{humanize(u.role)}{u.clinicianProfile?.specialty ? ` · ${u.clinicianProfile.specialty}` : ''}</TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(u.lastLoginAt)}</TableCell>
                      <TableCell><Chip size="small" label={u.enabled ? 'Enabled' : 'Disabled'} color={u.enabled ? 'success' : 'default'} variant="outlined" /></TableCell>
                      <TableCell><Button size="small" onClick={() => setEditing(u)}>Manage</Button></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={data.size} rowsPerPageOptions={[data.size]} onPageChange={(_, p) => setPage(p)} />
          </>
        )}
      </Paper>
      {editing && <UserDialog user={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function UserDialog({ user, onClose }: { user?: User; onClose: () => void }) {
  const { user: me } = useAuth();
  const departments = useDepartments();
  const qc = useQueryClient();
  const cp = user?.clinicianProfile;
  const [f, setF] = useState({
    username: user?.username ?? '', email: user?.email ?? '', fullName: user?.fullName ?? '', role: (user?.role ?? 'CLINICIAN') as Role,
    initialPassword: '', patientId: '', departmentId: cp?.departmentId ? String(cp.departmentId) : '', title: cp?.title ?? '',
    specialty: cp?.specialty ?? '', licenseNumber: cp?.licenseNumber ?? '', phone: cp?.phone ?? '', accepting: cp?.acceptingAppointments ?? true,
  });
  const [newPassword, setNewPassword] = useState('');
  const refresh = () => qc.invalidateQueries({ queryKey: ['users'] });

  const profile = () => f.role === 'CLINICIAN' ? {
    departmentId: f.departmentId ? Number(f.departmentId) : undefined, title: f.title || undefined, specialty: f.specialty || undefined,
    licenseNumber: f.licenseNumber || undefined, phone: f.phone || undefined, acceptingAppointments: f.accepting,
  } : undefined;
  const save = useMutation({
    mutationFn: () => user
      ? adminApi.updateUser(user.id, { email: f.email, fullName: f.fullName, role: f.role, clinicianProfile: profile() })
      : adminApi.createUser({ username: f.username, email: f.email, fullName: f.fullName, role: f.role, initialPassword: f.initialPassword,
          patientId: f.patientId ? Number(f.patientId) : undefined, clinicianProfile: profile() }),
    onSuccess: () => { void refresh(); onClose(); },
  });
  const toggle = useMutation({ mutationFn: () => adminApi.setEnabled(user!.id, !user!.enabled), onSuccess: () => { void refresh(); onClose(); } });
  const reset = useMutation({ mutationFn: () => adminApi.resetPassword(user!.id, newPassword), onSuccess: () => setNewPassword('') });

  const errs = fieldErrors(save.error);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((s) => ({ ...s, [k]: e.target.value }));
  const field = (k: keyof typeof f, label: string, extra: object = {}) => (
    <TextField label={label} value={f[k] as string} onChange={set(k)} error={!!errs[k]} helperText={errs[k]} fullWidth {...extra} />
  );
  const top = formLevelError(save.error, ['username', 'email', 'fullName', 'role', 'initialPassword', 'patientId']);

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); save.mutate(); }} noValidate>
        <DialogTitle>{user ? `Manage ${user.fullName}` : 'New user'}</DialogTitle>
        <DialogContent dividers>
          {top && <Alert severity="error" sx={{ mb: 2 }}>{top}</Alert>}
          <FormGrid>
            {field('username', 'Username', { disabled: !!user, required: true })}
            <TextField select label="Role" value={f.role} onChange={set('role')} disabled={user?.role === 'PATIENT'}>
              {(['ADMIN', 'CLINICIAN', ...(user ? (user.role === 'PATIENT' ? ['PATIENT'] : []) : ['PATIENT'])] as Role[]).map((r) => <MenuItem key={r} value={r}>{humanize(r)}</MenuItem>)}
            </TextField>
            {field('fullName', 'Full name', { required: true })}
            {field('email', 'Email', { required: true, type: 'email' })}
            {!user && field('initialPassword', 'Initial password', { type: 'password', required: true, helperText: errs.initialPassword ?? PASSWORD_HINT })}
            {!user && f.role === 'PATIENT' && field('patientId', 'Link to patient record ID', { type: 'number', required: true })}
            {f.role === 'CLINICIAN' && (
              <>
                <FullRow><Divider><Typography variant="caption">Clinician profile</Typography></Divider></FullRow>
                <TextField select label="Department" value={f.departmentId} onChange={set('departmentId')}>
                  <MenuItem value="">None</MenuItem>
                  {departments.data?.map((d) => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}
                </TextField>
                {field('title', 'Title')}
                {field('specialty', 'Specialty')}
                {field('licenseNumber', 'Licence number')}
                {field('phone', 'Phone')}
                <FormControlLabel control={<Switch checked={f.accepting} onChange={(e) => setF((s) => ({ ...s, accepting: e.target.checked }))} />} label="Accepting appointments" />
              </>
            )}
          </FormGrid>
          {user && (
            <>
              <Divider sx={{ my: 3 }} />
              <Typography variant="subtitle2" sx={{ mb: 1 }}>Account actions</Typography>
              {(toggle.error || reset.error) && <Alert severity="error" sx={{ mb: 1 }}>{toApiRequestError(toggle.error ?? reset.error).message}</Alert>}
              {reset.isSuccess && <Alert severity="success" sx={{ mb: 1 }}>Password reset.</Alert>}
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ alignItems: { sm: 'center' } }}>
                <TextField size="small" type="password" label="New password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} helperText={PASSWORD_HINT} />
                <Button onClick={() => reset.mutate()} disabled={!newPassword || reset.isPending}>Reset password</Button>
              </Stack>
              <Button sx={{ mt: 2 }} color={user.enabled ? 'warning' : 'primary'} variant="outlined" disabled={user.id === me?.id || toggle.isPending} onClick={() => toggle.mutate()}>
                {user.enabled ? 'Disable account' : 'Enable account'}
              </Button>
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Close</Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
