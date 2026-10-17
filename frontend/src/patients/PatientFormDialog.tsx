import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  TextField,
  Typography,
} from '@mui/material';
import { useSavePatient, type Patient, type PatientRequest, type SmokingStatus } from './api';
import { useClinicians } from '../appointments/api';
import { FormGrid, FullRow } from '../shared/components/FormGrid';
import { fieldErrors, formLevelError } from '../shared/forms';
import { toDateInputValue } from '../shared/format';
import type { Sex } from '../auth/api';

type FormState = Record<keyof PatientRequest, string>;

const EMPTY: FormState = {
  firstName: '', lastName: '', dateOfBirth: '', sex: 'UNKNOWN', phone: '', email: '', addressLine: '', city: '',
  postalCode: '', country: '', emergencyContactName: '', emergencyContactPhone: '', emergencyContactRelation: '',
  medicalHistory: '', allergies: '', currentMedications: '', smokingStatus: 'UNKNOWN', packYears: '',
  environmentalExposures: '', knownRespiratoryConditions: '', primaryClinicianId: '',
};

function fromPatient(p: Patient): FormState {
  const s = (v: unknown) => (v === undefined || v === null ? '' : String(v));
  return {
    firstName: p.firstName, lastName: p.lastName, dateOfBirth: p.dateOfBirth, sex: p.sex, phone: s(p.phone),
    email: s(p.email), addressLine: s(p.addressLine), city: s(p.city), postalCode: s(p.postalCode),
    country: s(p.country), emergencyContactName: s(p.emergencyContactName),
    emergencyContactPhone: s(p.emergencyContactPhone), emergencyContactRelation: s(p.emergencyContactRelation),
    medicalHistory: s(p.medicalHistory), allergies: s(p.allergies), currentMedications: s(p.currentMedications),
    smokingStatus: p.smokingStatus ?? 'UNKNOWN', packYears: s(p.packYears),
    environmentalExposures: s(p.environmentalExposures), knownRespiratoryConditions: s(p.knownRespiratoryConditions),
    primaryClinicianId: s(p.primaryClinician?.id),
  };
}

function toRequest(f: FormState): PatientRequest {
  const opt = (v: string) => (v.trim() === '' ? undefined : v.trim());
  return {
    firstName: f.firstName.trim(), lastName: f.lastName.trim(), dateOfBirth: f.dateOfBirth, sex: f.sex as Sex,
    phone: opt(f.phone), email: opt(f.email), addressLine: opt(f.addressLine), city: opt(f.city),
    postalCode: opt(f.postalCode), country: opt(f.country), emergencyContactName: opt(f.emergencyContactName),
    emergencyContactPhone: opt(f.emergencyContactPhone), emergencyContactRelation: opt(f.emergencyContactRelation),
    medicalHistory: opt(f.medicalHistory), allergies: opt(f.allergies), currentMedications: opt(f.currentMedications),
    smokingStatus: f.smokingStatus as SmokingStatus, packYears: f.packYears ? Number(f.packYears) : undefined,
    environmentalExposures: opt(f.environmentalExposures), knownRespiratoryConditions: opt(f.knownRespiratoryConditions),
    primaryClinicianId: f.primaryClinicianId ? Number(f.primaryClinicianId) : undefined,
  };
}

/**
 * Registration / edit form. `clinicalFields` is false for administrators, who maintain
 * demographics only (the backend ignores clinical fields from them regardless).
 */
export function PatientFormDialog({
  open,
  onClose,
  patient,
  clinicalFields,
  onSaved,
}: {
  open: boolean;
  onClose: () => void;
  patient?: Patient;
  clinicalFields: boolean;
  onSaved?: (p: Patient) => void;
}) {
  const [form, setForm] = useState<FormState>(() => (patient ? fromPatient(patient) : EMPTY));
  const save = useSavePatient(patient?.id);
  const clinicians = useClinicians();

  const set = (k: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const errs = fieldErrors(save.error);
  const field = (k: keyof FormState, label: string, extra: object = {}) => (
    <TextField label={label} value={form[k]} onChange={set(k)} error={!!errs[k]} helperText={errs[k]} fullWidth {...extra} />
  );

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate(toRequest(form), {
      onSuccess: (p) => {
        onSaved?.(p);
        onClose();
      },
    });
  };

  const topError = formLevelError(save.error, Object.keys(EMPTY));

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <form onSubmit={submit} noValidate>
        <DialogTitle>{patient ? `Edit ${patient.firstName} ${patient.lastName}` : 'Register patient'}</DialogTitle>
        <DialogContent dividers>
          {topError && <Alert severity="error" sx={{ mb: 2 }}>{topError}</Alert>}
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Demographics</Typography>
          <FormGrid columns={3}>
            {field('firstName', 'First name', { required: true })}
            {field('lastName', 'Last name', { required: true })}
            {field('dateOfBirth', 'Date of birth', {
              type: 'date', required: true,
              slotProps: { inputLabel: { shrink: true }, htmlInput: { max: toDateInputValue(new Date()) } },
            })}
            <TextField select label="Sex" value={form.sex} onChange={set('sex')}>
              {['FEMALE', 'MALE', 'OTHER', 'UNKNOWN'].map((s) => <MenuItem key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</MenuItem>)}
            </TextField>
            {field('phone', 'Phone')}
            {field('email', 'Email', { type: 'email' })}
            <FullRow>{field('addressLine', 'Address')}</FullRow>
            {field('city', 'City')}
            {field('postalCode', 'Postal code')}
            {field('country', 'Country')}
            {field('emergencyContactName', 'Emergency contact')}
            {field('emergencyContactPhone', 'Emergency phone')}
            {field('emergencyContactRelation', 'Relationship')}
            <FullRow>
              <TextField select label="Primary clinician" value={form.primaryClinicianId} onChange={set('primaryClinicianId')} fullWidth error={!!errs.primaryClinicianId} helperText={errs.primaryClinicianId}>
                <MenuItem value="">None</MenuItem>
                {clinicians.data?.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.fullName}{c.specialty ? ` — ${c.specialty}` : ''}</MenuItem>)}
              </TextField>
            </FullRow>
          </FormGrid>
          {clinicalFields && (
            <>
              <Divider sx={{ my: 3 }} />
              <Typography variant="subtitle2" sx={{ mb: 1 }}>Clinical background</Typography>
              <FormGrid>
                <TextField select label="Smoking status" value={form.smokingStatus} onChange={set('smokingStatus')}>
                  {['NEVER', 'FORMER', 'CURRENT', 'UNKNOWN'].map((s) => <MenuItem key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</MenuItem>)}
                </TextField>
                {field('packYears', 'Pack-years', { type: 'number', slotProps: { htmlInput: { min: 0, max: 300, step: 0.5 } } })}
                {field('knownRespiratoryConditions', 'Known respiratory conditions', { multiline: true, minRows: 2 })}
                {field('environmentalExposures', 'Environmental exposures', { multiline: true, minRows: 2 })}
                {field('allergies', 'Allergies', { multiline: true, minRows: 2 })}
                {field('currentMedications', 'Current medications', { multiline: true, minRows: 2 })}
                <FullRow>{field('medicalHistory', 'Relevant medical history', { multiline: true, minRows: 3 })}</FullRow>
              </FormGrid>
            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={save.isPending || !form.firstName || !form.lastName || !form.dateOfBirth}>
            {save.isPending ? 'Saving…' : 'Save'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
