import { useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  GlobalStyles,
  Link,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import PrintOutlinedIcon from '@mui/icons-material/PrintOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { billingApi, invoiceStatusColor, money, useInvoice, type Invoice, type PaymentMethod } from './api';
import { InvoiceEditorDialog } from './InvoiceEditorDialog';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, formatDateTime, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { toApiRequestError } from '../shared/api/client';

export function InvoiceDetailPage() {
  const id = Number(useParams().id);
  const { data, isPending, error, refetch } = useInvoice(id);
  if (isPending) return <LoadingState />;
  if (error) return <ErrorState error={error} onRetry={() => void refetch()} />;
  return <InvoiceView inv={data} />;
}

function InvoiceView({ inv }: { inv: Invoice }) {
  const { hasRole } = useAuth();
  const qc = useQueryClient();
  const s = inv.summary;
  const c = s.currency;
  const [editing, setEditing] = useState(false);
  const [paying, setPaying] = useState(false);
  const [reason, setReason] = useState<{ kind: 'void' | 'reverse'; paymentId?: number } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const done = (r: Invoice) => {
    qc.setQueryData(['invoice', r.summary.id], r);
    void qc.invalidateQueries({ queryKey: ['invoices'] });
  };
  const issue = useMutation({ mutationFn: () => billingApi.issue(s.id), onSuccess: done });
  const simulate = useMutation({ mutationFn: () => billingApi.simulatePay(s.id), onSuccess: (r) => { done(r.invoice); setNotice(r.notice); } });
  const actionError = issue.error ?? simulate.error;
  const canPay = (s.status === 'ISSUED' || s.status === 'PARTIALLY_PAID') && s.outstanding > 0;

  return (
    <>
      {/* Print only the invoice sheet. */}
      <GlobalStyles styles={{ '@media print': { 'header, nav, .MuiDrawer-root, .no-print': { display: 'none !important' }, main: { padding: '0 !important' }, '.invoice-sheet': { border: 'none !important' } } }} />
      <Stack direction="row" spacing={1} className="no-print" sx={{ mb: 2, flexWrap: 'wrap', gap: 1 }}>
        <Button component={RouterLink} to="/billing">Back to invoices</Button>
        <Box sx={{ flex: 1 }} />
        <Button startIcon={<PrintOutlinedIcon />} onClick={() => window.print()}>Print</Button>
        {hasRole('ADMIN', 'CLINICIAN') && s.status === 'DRAFT' && <Button onClick={() => setEditing(true)}>Edit draft</Button>}
        {hasRole('ADMIN', 'CLINICIAN') && s.status === 'DRAFT' && <Button variant="contained" disabled={issue.isPending} onClick={() => issue.mutate()}>Issue invoice</Button>}
        {hasRole('ADMIN') && canPay && <Button variant="contained" onClick={() => setPaying(true)}>Record payment</Button>}
        {hasRole('ADMIN') && s.status !== 'VOID' && s.amountPaid === 0 && <Button color="warning" onClick={() => setReason({ kind: 'void' })}>Void</Button>}
        {hasRole('PATIENT') && canPay && <Button variant="contained" color="secondary" disabled={simulate.isPending} onClick={() => simulate.mutate()}>Pay now (simulated)</Button>}
      </Stack>
      {hasRole('PATIENT') && canPay && (
        <Alert severity="info" className="no-print" sx={{ mb: 2 }}>Online payment in this system is a <strong>simulation</strong>: no payment gateway is connected and no money is charged.</Alert>
      )}
      {notice && <Alert severity="warning" className="no-print" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {actionError && <Alert severity="error" className="no-print" sx={{ mb: 2 }}>{toApiRequestError(actionError).message}</Alert>}

      <Paper className="invoice-sheet" sx={{ p: { xs: 2, md: 4 }, maxWidth: 900 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ justifyContent: 'space-between', mb: 3 }} spacing={2}>
          <Box>
            <Typography variant="h2">{inv.clinicName}</Typography>
            <Typography color="text.secondary">Invoice {s.invoiceNumber}</Typography>
          </Box>
          <Box sx={{ textAlign: { sm: 'right' } }}>
            <Chip label={humanize(s.status)} color={invoiceStatusColor(s.status)} sx={{ mb: 1 }} />
            {s.overdue && <Chip label="Overdue" color="error" sx={{ mb: 1, ml: 1 }} />}
            <Typography variant="body2">Issued: {formatDate(s.issueDate)}</Typography>
            <Typography variant="body2">Due: {formatDate(s.dueDate)}</Typography>
          </Box>
        </Stack>
        <Typography variant="subtitle2">Bill to</Typography>
        <Typography sx={{ mb: 2 }}>
          {hasRole('ADMIN', 'CLINICIAN') ? <Link component={RouterLink} to={`/patients/${s.patientId}`}>{s.patientName}</Link> : s.patientName} · {s.patientMrn}
        </Typography>
        {s.status === 'VOID' && <Alert severity="warning" sx={{ mb: 2 }}>Voided {formatDateTime(inv.voidedAt)}: {inv.voidReason}</Alert>}

        <Table size="small" aria-label="Invoice lines">
          <TableHead>
            <TableRow>
              <TableCell>Description</TableCell>
              <TableCell align="right">Qty</TableCell>
              <TableCell align="right">Unit</TableCell>
              <TableCell align="right">Discount</TableCell>
              <TableCell align="right">Tax</TableCell>
              <TableCell align="right">Amount</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {inv.lines.map((l) => (
              <TableRow key={l.id}>
                <TableCell>{l.description}</TableCell>
                <TableCell align="right">{l.quantity}</TableCell>
                <TableCell align="right">{money(l.unitPrice, c)}</TableCell>
                <TableCell align="right">{l.lineDiscount > 0 ? `−${money(l.lineDiscount, c)} (${l.discountPercent}%)` : '—'}</TableCell>
                <TableCell align="right">{l.lineTax > 0 ? money(l.lineTax, c) : '—'}</TableCell>
                <TableCell align="right">{money(l.lineTotal, c)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'end', columnGap: 4, rowGap: 0.5, mt: 2 }}>
          <Typography>Subtotal</Typography><Typography align="right">{money(inv.subtotal, c)}</Typography>
          <Typography>Discounts</Typography><Typography align="right">{inv.discountTotal > 0 ? `−${money(inv.discountTotal, c)}` : '—'}</Typography>
          <Typography>Tax</Typography><Typography align="right">{money(inv.taxTotal, c)}</Typography>
          <Typography sx={{ fontWeight: 700 }}>Total</Typography><Typography align="right" sx={{ fontWeight: 700 }}>{money(s.total, c)}</Typography>
          <Typography>Paid</Typography><Typography align="right">{money(s.amountPaid, c)}</Typography>
          <Typography sx={{ fontWeight: 700 }}>Balance due</Typography><Typography align="right" sx={{ fontWeight: 700 }}>{money(s.outstanding, c)}</Typography>
        </Box>

        {inv.diagnoses.length > 0 && (
          <>
            <Divider sx={{ my: 3 }} />
            <Typography variant="subtitle2">Diagnosis codes (ICD-10-CM)</Typography>
            {inv.diagnoses.map((d) => <Typography key={d.code} variant="body2">{d.sequence}. {d.code} — {d.description}</Typography>)}
          </>
        )}
        {inv.notes && <><Divider sx={{ my: 3 }} /><Typography variant="body2">{inv.notes}</Typography></>}

        <Divider sx={{ my: 3 }} />
        <Typography variant="subtitle2" sx={{ mb: 1 }}>Payments</Typography>
        {inv.payments.length === 0 && <Typography variant="body2" color="text.secondary">No payments recorded.</Typography>}
        {inv.payments.length > 0 && (
          <Table size="small" aria-label="Payments">
            <TableBody>
              {inv.payments.map((p) => (
                <TableRow key={p.id} sx={p.status === 'REVERSED' ? { '& td': { textDecoration: 'line-through', color: 'text.secondary' } } : undefined}>
                  <TableCell>{formatDateTime(p.paidAt)}</TableCell>
                  <TableCell>
                    {humanize(p.method)}{p.reference ? ` · ${p.reference}` : ''}
                    {p.simulated && <Chip size="small" color="warning" label="Simulated – no money moved" sx={{ ml: 1, textDecoration: 'none' }} />}
                  </TableCell>
                  <TableCell align="right">{money(p.amount, c)}</TableCell>
                  <TableCell className="no-print">
                    {p.status === 'REVERSED' ? `Reversed: ${p.reversalReason}` : hasRole('ADMIN') && (
                      <Button size="small" color="warning" onClick={() => setReason({ kind: 'reverse', paymentId: p.id })}>Reverse</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Paper>

      {editing && <InvoiceEditorDialog invoice={inv} onClose={() => setEditing(false)} />}
      {paying && <PaymentDialog inv={inv} onClose={() => setPaying(false)} onDone={done} />}
      {reason && <ReasonDialog inv={inv} kind={reason.kind} paymentId={reason.paymentId} onClose={() => setReason(null)} onDone={done} />}
    </>
  );
}

function PaymentDialog({ inv, onClose, onDone }: { inv: Invoice; onClose: () => void; onDone: (i: Invoice) => void }) {
  const [amount, setAmount] = useState(String(inv.summary.outstanding));
  const [method, setMethod] = useState<PaymentMethod>('CARD');
  const [reference, setReference] = useState('');
  const m = useMutation({ mutationFn: () => billingApi.pay(inv.summary.id, Number(amount), method, reference || undefined), onSuccess: (r) => { onDone(r); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Record payment</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Record a payment received outside RDSS. Outstanding: {money(inv.summary.outstanding, inv.summary.currency)}</Typography>
        {m.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(m.error).message}</Alert>}
        <Stack spacing={2}>
          <TextField label="Amount" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} slotProps={{ htmlInput: { min: 0.01, step: 0.01, max: inv.summary.outstanding } }} />
          <TextField select label="Method" value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
            {(['CARD', 'CASH', 'BANK_TRANSFER', 'INSURANCE'] as const).map((x) => <MenuItem key={x} value={x}>{humanize(x)}</MenuItem>)}
          </TextField>
          <TextField label="Reference" value={reference} onChange={(e) => setReference(e.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!(Number(amount) > 0) || m.isPending} onClick={() => m.mutate()}>Record</Button>
      </DialogActions>
    </Dialog>
  );
}

function ReasonDialog({ inv, kind, paymentId, onClose, onDone }: { inv: Invoice; kind: 'void' | 'reverse'; paymentId?: number; onClose: () => void; onDone: (i: Invoice) => void }) {
  const [reason, setReason] = useState('');
  const m = useMutation({
    mutationFn: () => (kind === 'void' ? billingApi.void(inv.summary.id, reason.trim()) : billingApi.reversePayment(paymentId!, reason.trim())),
    onSuccess: (r) => { onDone(r); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{kind === 'void' ? 'Void invoice' : 'Reverse payment'}</DialogTitle>
      <DialogContent>
        {m.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(m.error).message}</Alert>}
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required multiline minRows={2} autoFocus sx={{ mt: 1 }} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" disabled={!reason.trim() || m.isPending} onClick={() => m.mutate()}>Confirm</Button>
      </DialogActions>
    </Dialog>
  );
}
