import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  Chip,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { invoiceStatusColor, money, useInvoices, type InvoiceStatus } from './api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { InvoiceEditorDialog } from './InvoiceEditorDialog';

const STATUSES: InvoiceStatus[] = ['DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID'];

export function InvoicesPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const staff = hasRole('ADMIN', 'CLINICIAN');
  const [status, setStatus] = useState<InvoiceStatus | ''>('');
  const [q, setQ] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const { data, isPending, error, refetch } = useInvoices({ status: status || undefined, q: q || undefined, from: from || undefined, to: to || undefined, page });

  return (
    <>
      <PageHeader
        title={staff ? 'Billing' : 'My invoices'}
        subtitle={staff ? 'Invoices and payments' : 'Your invoices and payment status'}
        actions={staff && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>New invoice</Button>}
      />
      <Paper>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 2 }}>
          {staff && <TextField size="small" label="Invoice number" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />}
          <TextField size="small" select label="Status" value={status} onChange={(e) => { setStatus(e.target.value as InvoiceStatus | ''); setPage(0); }} sx={{ minWidth: 160 }}>
            <MenuItem value="">All</MenuItem>
            {STATUSES.filter((s) => staff || s !== 'DRAFT').map((s) => <MenuItem key={s} value={s}>{humanize(s)}</MenuItem>)}
          </TextField>
          <TextField size="small" type="date" label="Issued from" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label="Issued to" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
        </Stack>
        {isPending && <LoadingState />}
        {error && <Box sx={{ p: 2 }}><ErrorState error={error} onRetry={() => void refetch()} /></Box>}
        {data?.totalElements === 0 && <EmptyState title="No invoices" />}
        {!!data?.totalElements && (
          <>
            <TableContainer>
              <Table size="small" aria-label="Invoices">
                <TableHead>
                  <TableRow>
                    <TableCell>Invoice</TableCell>
                    {staff && <TableCell>Patient</TableCell>}
                    <TableCell>Issued</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Due</TableCell>
                    <TableCell align="right">Total</TableCell>
                    <TableCell align="right">Outstanding</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((i) => (
                    <TableRow key={i.id} hover sx={{ cursor: 'pointer' }} tabIndex={0}
                      onClick={() => navigate(`/billing/invoices/${i.id}`)} onKeyDown={(e) => e.key === 'Enter' && navigate(`/billing/invoices/${i.id}`)}>
                      <TableCell sx={{ fontWeight: 600 }}>{i.invoiceNumber}</TableCell>
                      {staff && <TableCell>{i.patientName} <Box component="span" sx={{ color: 'text.secondary' }}>· {i.patientMrn}</Box></TableCell>}
                      <TableCell>{formatDate(i.issueDate)}</TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDate(i.dueDate)}</TableCell>
                      <TableCell align="right">{money(i.total, i.currency)}</TableCell>
                      <TableCell align="right">{money(i.outstanding, i.currency)}</TableCell>
                      <TableCell>
                        <Chip size="small" variant="outlined" label={humanize(i.status)} color={invoiceStatusColor(i.status)} />
                        {i.overdue && <Chip size="small" label="Overdue" color="error" sx={{ ml: 1 }} />}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={data.size} rowsPerPageOptions={[data.size]} onPageChange={(_, p) => setPage(p)} />
          </>
        )}
      </Paper>
      {creating && <InvoiceEditorDialog onClose={() => setCreating(false)} onSaved={(id) => navigate(`/billing/invoices/${id}`)} />}
    </>
  );
}
