import { useMemo, useState } from 'react';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { billingApi, money, useServices, type Invoice, type LineRequest } from './api';
import { PatientPicker } from '../appointments/BookAppointmentDialog';
import { useIcd10Search, type Icd10Code } from '../diagnoses/api';
import { toApiRequestError } from '../shared/api/client';
import { fieldErrors } from '../shared/forms';

interface Row {
  serviceId?: number;
  description: string;
  unitPrice: string;
  quantity: string;
  discountPercent: string;
  taxable: boolean;
}

const emptyRow = (): Row => ({ description: '', unitPrice: '', quantity: '1', discountPercent: '0', taxable: false });

/** Creates a draft, or edits an existing draft (all lines and codes are replaced on save). */
export function InvoiceEditorDialog({ invoice, fixedPatient, onClose, onSaved }: {
  invoice?: Invoice;
  fixedPatient?: { id: number; name: string };
  onClose: () => void;
  onSaved?: (id: number) => void;
}) {
  const services = useServices();
  const qc = useQueryClient();
  const [patient, setPatient] = useState<{ id: number; name: string } | null>(
    invoice ? { id: invoice.summary.patientId, name: invoice.summary.patientName ?? '' } : fixedPatient ?? null);
  const [rows, setRows] = useState<Row[]>(() => invoice
    ? invoice.lines.map((l) => ({ serviceId: l.serviceId, description: l.description, unitPrice: String(l.unitPrice), quantity: String(l.quantity), discountPercent: String(l.discountPercent), taxable: l.taxRate > 0 }))
    : [emptyRow()]);
  const [codes, setCodes] = useState<Icd10Code[]>(() => (invoice?.diagnoses ?? []).map((d) => ({
    code: d.code, description: d.description ?? '', billable: true, codeSystem: 'ICD-10-CM', codeVersion: '', source: 'CURATED_SUBSET', active: true })));
  const [notes, setNotes] = useState(invoice?.notes ?? '');
  const [codeInput, setCodeInput] = useState('');
  const codeSearch = useIcd10Search(codeInput);

  const estimate = useMemo(() => rows.reduce((sum, r) => {
    const sub = (Number(r.quantity) || 0) * (Number(r.unitPrice) || 0);
    return sum + sub - sub * (Number(r.discountPercent) || 0) / 100;
  }, 0), [rows]);

  const save = useMutation({
    mutationFn: () => {
      const lines: LineRequest[] = rows.map((r) => ({
        serviceId: r.serviceId,
        description: r.description || undefined,
        unitPrice: r.unitPrice === '' ? undefined : Number(r.unitPrice),
        quantity: Number(r.quantity),
        discountPercent: Number(r.discountPercent) || 0,
        taxable: r.serviceId ? undefined : r.taxable,
      }));
      const body = { patientId: patient?.id, lines, diagnosisCodes: codes.map((c) => c.code), notes: notes.trim() || undefined };
      return invoice ? billingApi.update(invoice.summary.id, { ...body, version: invoice.version }) : billingApi.create(body);
    },
    onSuccess: (inv) => {
      qc.setQueryData(['invoice', inv.summary.id], inv);
      void qc.invalidateQueries({ queryKey: ['invoices'] });
      onSaved?.(inv.summary.id);
      onClose();
    },
  });
  const errs = fieldErrors(save.error);
  const update = (i: number, patch: Partial<Row>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{invoice ? `Edit draft ${invoice.summary.invoiceNumber}` : 'New invoice (draft)'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {save.error && <Alert severity="error">{toApiRequestError(save.error).message}{Object.keys(errs).length ? `: ${Object.entries(errs).map(([k, v]) => `${k} ${v}`).join('; ')}` : ''}</Alert>}
          {!invoice && !fixedPatient && <PatientPicker value={patient} onChange={setPatient} />}
          {(invoice || fixedPatient) && <Typography>Patient: <strong>{patient?.name}</strong></Typography>}
          <Typography variant="subtitle2">Lines</Typography>
          {rows.map((r, i) => (
            <Box key={i} sx={{ display: 'grid', gap: 1, alignItems: 'center', gridTemplateColumns: { xs: '1fr 1fr', md: '2fr 2fr 1fr 0.8fr 0.8fr auto' } }}>
              <TextField size="small" select label="Service" value={r.serviceId ?? ''}
                onChange={(e) => {
                  const s = services.data?.find((x) => x.id === Number(e.target.value));
                  update(i, s ? { serviceId: s.id, description: s.name, unitPrice: String(s.unitPrice), taxable: s.taxable } : { serviceId: undefined });
                }}>
                <MenuItem value="">Custom line</MenuItem>
                {services.data?.filter((s) => s.active).map((s) => <MenuItem key={s.id} value={s.id}>{s.code} · {s.name}</MenuItem>)}
              </TextField>
              <TextField size="small" label="Description" value={r.description} onChange={(e) => update(i, { description: e.target.value })} />
              <TextField size="small" label="Unit price" type="number" value={r.unitPrice} onChange={(e) => update(i, { unitPrice: e.target.value })} slotProps={{ htmlInput: { min: 0, step: 0.01 } }} />
              <TextField size="small" label="Qty" type="number" value={r.quantity} onChange={(e) => update(i, { quantity: e.target.value })} slotProps={{ htmlInput: { min: 0.01, step: 0.5 } }} />
              <TextField size="small" label="Disc %" type="number" value={r.discountPercent} onChange={(e) => update(i, { discountPercent: e.target.value })} slotProps={{ htmlInput: { min: 0, max: 100 } }} />
              <IconButton aria-label="Remove line" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))} disabled={rows.length === 1}><DeleteOutlinedIcon /></IconButton>
              {!r.serviceId && (
                <FormControlLabel sx={{ gridColumn: '1 / -1' }} control={<Switch size="small" checked={r.taxable} onChange={(e) => update(i, { taxable: e.target.checked })} />} label="Taxable (default tax rate)" />
              )}
            </Box>
          ))}
          <Box><Button startIcon={<AddIcon />} onClick={() => setRows((rs) => [...rs, emptyRow()])}>Add line</Button></Box>
          <Autocomplete
            multiple
            options={codeSearch.data?.content ?? []}
            value={codes}
            onChange={(_, v) => setCodes(v.slice(0, 12))}
            inputValue={codeInput}
            onInputChange={(_, v) => setCodeInput(v)}
            filterOptions={(x) => x}
            getOptionLabel={(o) => `${o.code} — ${o.description}`}
            isOptionEqualToValue={(a, b) => a.code === b.code}
            renderInput={(params) => <TextField {...params} label="ICD-10-CM diagnosis codes (max 12)" placeholder="Search code or description" />}
          />
          <TextField label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} />
          <Typography variant="body2" color="text.secondary">Estimated total before tax: <strong>{money(estimate)}</strong> (final amounts are calculated by the server)</Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!patient || save.isPending || rows.some((r) => !r.serviceId && (!r.description || r.unitPrice === ''))} onClick={() => save.mutate()}>
          Save draft
        </Button>
      </DialogActions>
    </Dialog>
  );
}
