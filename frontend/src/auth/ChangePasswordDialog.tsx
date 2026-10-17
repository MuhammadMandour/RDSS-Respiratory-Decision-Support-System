import { useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { authApi, PASSWORD_HINT, PASSWORD_RULE } from './api';
import { toApiRequestError } from '../shared/api/client';

export function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const m = useMutation({ mutationFn: () => authApi.changePassword(current, next) });
  const valid = PASSWORD_RULE.test(next) && next === confirm && current.length > 0;
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Change password</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          {m.isSuccess && <Alert severity="success">Password changed.</Alert>}
          <TextField label="Current password" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
          <TextField label="New password" type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" helperText={PASSWORD_HINT} />
          <TextField label="Confirm new password" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password"
            error={!!confirm && confirm !== next} helperText={confirm && confirm !== next ? 'Passwords do not match' : undefined} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!valid || m.isPending} onClick={() => m.mutate()}>Change</Button>
      </DialogActions>
    </Dialog>
  );
}
