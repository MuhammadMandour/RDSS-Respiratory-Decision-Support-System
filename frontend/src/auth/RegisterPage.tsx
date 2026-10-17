import { useState, type FormEvent } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { Alert, Button, Link, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { authApi, PASSWORD_HINT, PASSWORD_RULE, type RegisterRequest, type Sex } from './api';
import { useAuth } from './AuthContext';
import { AuthLayout } from './AuthLayout';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { toDateInputValue } from '../shared/format';

const FIELDS = ['username', 'email', 'password', 'firstName', 'lastName', 'dateOfBirth', 'sex', 'phone'];

export function RegisterPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<RegisterRequest & { confirm: string }>({
    username: '',
    email: '',
    password: '',
    confirm: '',
    firstName: '',
    lastName: '',
    dateOfBirth: '',
    sex: 'UNKNOWN',
    phone: '',
  });
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});

  const register = useMutation({
    mutationFn: authApi.register,
    onSuccess: (r) => {
      signIn(r);
      navigate('/', { replace: true });
    },
  });

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (!PASSWORD_RULE.test(form.password)) errs.password = PASSWORD_HINT;
    if (form.password !== form.confirm) errs.confirm = 'Passwords do not match';
    if (!form.dateOfBirth) errs.dateOfBirth = 'Required';
    setClientErrors(errs);
    if (Object.keys(errs).length) return;
    const { confirm: _confirm, ...body } = form;
    register.mutate({ ...body, phone: body.phone || undefined });
  };

  const server = fieldErrors(register.error);
  const err = (k: string) => clientErrors[k] ?? server[k];

  return (
    <AuthLayout title="Create patient account">
      <Stack component="form" spacing={2} onSubmit={submit} noValidate>
        {formLevelError(register.error, FIELDS) && (
          <Alert severity="error">{formLevelError(register.error, FIELDS)}</Alert>
        )}
        <FormGrid>
          <TextField label="First name" value={form.firstName} onChange={set('firstName')} required error={!!err('firstName')} helperText={err('firstName')} />
          <TextField label="Last name" value={form.lastName} onChange={set('lastName')} required error={!!err('lastName')} helperText={err('lastName')} />
          <TextField
            label="Date of birth"
            type="date"
            value={form.dateOfBirth}
            onChange={set('dateOfBirth')}
            required
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: toDateInputValue(new Date()) } }}
            error={!!err('dateOfBirth')}
            helperText={err('dateOfBirth')}
          />
          <TextField select label="Sex" value={form.sex} onChange={(e) => setForm((f) => ({ ...f, sex: e.target.value as Sex }))}>
            <MenuItem value="FEMALE">Female</MenuItem>
            <MenuItem value="MALE">Male</MenuItem>
            <MenuItem value="OTHER">Other</MenuItem>
            <MenuItem value="UNKNOWN">Prefer not to say</MenuItem>
          </TextField>
          <FullRow>
            <TextField fullWidth label="Email" type="email" value={form.email} onChange={set('email')} required autoComplete="email" error={!!err('email')} helperText={err('email')} />
          </FullRow>
          <TextField label="Phone (optional)" value={form.phone} onChange={set('phone')} autoComplete="tel" error={!!err('phone')} helperText={err('phone')} />
          <TextField label="Username" value={form.username} onChange={set('username')} required autoComplete="username" error={!!err('username')} helperText={err('username')} />
          <TextField label="Password" type="password" value={form.password} onChange={set('password')} required autoComplete="new-password" error={!!err('password')} helperText={err('password') ?? PASSWORD_HINT} />
          <TextField label="Confirm password" type="password" value={form.confirm} onChange={set('confirm')} required autoComplete="new-password" error={!!err('confirm')} helperText={err('confirm')} />
        </FormGrid>
        <Button type="submit" variant="contained" size="large" disabled={register.isPending}>
          {register.isPending ? 'Creating account…' : 'Create account'}
        </Button>
        <Typography variant="body2">
          Already registered?{' '}
          <Link component={RouterLink} to="/login">
            Sign in
          </Link>
        </Typography>
      </Stack>
    </AuthLayout>
  );
}
