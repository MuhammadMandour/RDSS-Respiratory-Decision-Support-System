import { useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { billingApi, money, useServices, type BillingService, type ServiceCategory } from '../billing/api';
import { diagnosesApi, useIcd10Search } from '../diagnoses/api';
import { PageHeader } from '../shared/components/PageHeader';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { humanize } from '../shared/format';
import { http, toApiRequestError } from '../shared/api/client';

/** Administrator configuration of the service catalogue and supported diagnosis codes. */
export function CodingAndBillingPage() {
  return (
    <>
      <PageHeader title="Billing & coding" subtitle="Service catalogue, prices and supported ICD-10-CM codes" />
      <Stack spacing={2}>
        <ServiceCatalogue />
        <Icd10Admin />
      </Stack>
    </>
  );
}

const CATEGORIES: ServiceCategory[] = ['CONSULTATION', 'DIAGNOSTIC_TEST', 'IMAGING', 'PROCEDURE', 'THERAPY', 'OTHER'];

function ServiceCatalogue() {
  const { data, isPending, error } = useServices();
  const [editing, setEditing] = useState<BillingService | 'new' | null>(null);
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
        <Typography variant="h3" component="h2">Service catalogue</Typography>
        <Button onClick={() => setEditing('new')}>New service</Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>Price changes apply to new invoice lines only; existing invoices keep their prices.</Typography>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      {data && (
        <Table size="small" aria-label="Billing services">
          <TableHead>
            <TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell>Category</TableCell><TableCell align="right">Price</TableCell><TableCell>Taxable</TableCell><TableCell>Status</TableCell><TableCell /></TableRow>
          </TableHead>
          <TableBody>
            {data.map((s) => (
              <TableRow key={s.id}>
                <TableCell sx={{ fontFamily: 'monospace' }}>{s.code}</TableCell>
                <TableCell>{s.name}</TableCell>
                <TableCell>{humanize(s.category)}</TableCell>
                <TableCell align="right">{money(s.unitPrice)}</TableCell>
                <TableCell>{s.taxable ? 'Yes' : 'No'}</TableCell>
                <TableCell><Chip size="small" variant="outlined" label={s.active ? 'Active' : 'Inactive'} color={s.active ? 'success' : 'default'} /></TableCell>
                <TableCell><Button size="small" onClick={() => setEditing(s)}>Edit</Button></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {editing && <ServiceDialog service={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Paper>
  );
}

function ServiceDialog({ service, onClose }: { service?: BillingService; onClose: () => void }) {
  const [f, setF] = useState({ code: service?.code ?? '', name: service?.name ?? '', category: service?.category ?? 'CONSULTATION',
    unitPrice: String(service?.unitPrice ?? ''), taxable: service?.taxable ?? false, active: service?.active ?? true });
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: () => {
      const body = { ...f, code: f.code.trim().toUpperCase(), unitPrice: Number(f.unitPrice), category: f.category as ServiceCategory };
      return service ? billingApi.updateService(service.id, body) : billingApi.createService(body);
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['billing', 'services'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{service ? 'Edit service' : 'New service'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {m.error && <Alert severity="error">{toApiRequestError(m.error).message}</Alert>}
          <TextField label="Code" value={f.code} disabled={!!service} onChange={(e) => setF((s) => ({ ...s, code: e.target.value }))} helperText="A-Z, 0-9 and dashes" />
          <TextField label="Name" value={f.name} onChange={(e) => setF((s) => ({ ...s, name: e.target.value }))} />
          <TextField select label="Category" value={f.category} onChange={(e) => setF((s) => ({ ...s, category: e.target.value as ServiceCategory }))}>
            {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{humanize(c)}</MenuItem>)}
          </TextField>
          <TextField label="Unit price" type="number" value={f.unitPrice} onChange={(e) => setF((s) => ({ ...s, unitPrice: e.target.value }))} slotProps={{ htmlInput: { min: 0, step: 0.01 } }} />
          <FormControlLabel control={<Switch checked={f.taxable} onChange={(e) => setF((s) => ({ ...s, taxable: e.target.checked }))} />} label="Taxable" />
          <FormControlLabel control={<Switch checked={f.active} onChange={(e) => setF((s) => ({ ...s, active: e.target.checked }))} />} label="Active" />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.code || !f.name || f.unitPrice === '' || m.isPending} onClick={() => m.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

function Icd10Admin() {
  const [q, setQ] = useState('J45');
  const search = useIcd10Search(q, false);
  const qc = useQueryClient();
  const toggle = useMutation({
    mutationFn: ({ code, active }: { code: string; active: boolean }) => diagnosesApi.setCodeActive(code, active),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['icd10'] }),
  });
  const [file, setFile] = useState<File | null>(null);
  const [version, setVersion] = useState('FY2026');
  const importer = useMutation({
    mutationFn: async () => {
      const form = new FormData();
      form.append('file', file!);
      return (await http.post<{ imported: number; billable: number; rejectedLines: number }>('/icd10/import', form, { params: { codeVersion: version }, timeout: 600_000 })).data;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['icd10'] }),
  });
  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h3" component="h2" sx={{ mb: 1 }}>ICD-10-CM codes</Typography>
      <Alert severity="info" sx={{ mb: 2 }}>
        RDSS ships a small curated subset of respiratory-related ICD-10-CM codes for development. Before production use, import the official
        CDC/NCHS release (the <code>icd10cm-order</code> text file) below.
      </Alert>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ mb: 2, alignItems: { md: 'center' } }}>
        <Button component="label" variant="outlined">
          {file ? file.name : 'Choose order file'}
          <input hidden type="file" accept=".txt" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </Button>
        <TextField size="small" label="Code version" value={version} onChange={(e) => setVersion(e.target.value)} />
        <Button variant="contained" disabled={!file || !version || importer.isPending} onClick={() => importer.mutate()}>Import</Button>
      </Stack>
      {importer.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(importer.error).message}</Alert>}
      {importer.data && <Alert severity="success" sx={{ mb: 2 }}>Imported {importer.data.imported} codes ({importer.data.billable} billable); {importer.data.rejectedLines} lines rejected.</Alert>}
      <TextField size="small" label="Search codes" value={q} onChange={(e) => setQ(e.target.value)} sx={{ mb: 1 }} />
      {search.isFetching && <LoadingState />}
      {toggle.error && <ErrorState error={toggle.error} />}
      {search.data && (
        <Table size="small" aria-label="ICD-10 codes">
          <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Description</TableCell><TableCell>Version</TableCell><TableCell>Source</TableCell><TableCell>Enabled</TableCell></TableRow></TableHead>
          <TableBody>
            {search.data.content.map((c) => (
              <TableRow key={c.code}>
                <TableCell sx={{ fontFamily: 'monospace' }}>{c.code}</TableCell>
                <TableCell>{c.description}</TableCell>
                <TableCell>{c.codeVersion}</TableCell>
                <TableCell>{humanize(c.source)}</TableCell>
                <TableCell><Switch size="small" checked={c.active} onChange={(e) => toggle.mutate({ code: c.code, active: e.target.checked })} slotProps={{ input: { 'aria-label': `Enable ${c.code}` } }} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
}
