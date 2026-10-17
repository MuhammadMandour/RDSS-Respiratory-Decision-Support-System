import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { invoiceStatusColor, money, useInvoices } from './api';
import { InvoiceEditorDialog } from './InvoiceEditorDialog';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { fullName, type Patient } from '../patients/api';

/** Invoices tab on the patient record. */
export function PatientInvoices({ patient }: { patient: Patient }) {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const { data, isPending, error } = useInvoices({ patientId: patient.id });
  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">Invoices</Typography>
        {hasRole('ADMIN', 'CLINICIAN') && <Button onClick={() => setCreating(true)}>New invoice</Button>}
      </Stack>
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      {data?.totalElements === 0 && <EmptyState title="No invoices" />}
      {!!data?.totalElements && (
        <Table size="small" aria-label="Patient invoices">
          <TableHead>
            <TableRow><TableCell>Invoice</TableCell><TableCell>Issued</TableCell><TableCell align="right">Total</TableCell><TableCell align="right">Outstanding</TableCell><TableCell>Status</TableCell></TableRow>
          </TableHead>
          <TableBody>
            {data.content.map((i) => (
              <TableRow key={i.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/billing/invoices/${i.id}`)}>
                <TableCell>{i.invoiceNumber}</TableCell>
                <TableCell>{formatDate(i.issueDate)}</TableCell>
                <TableCell align="right">{money(i.total, i.currency)}</TableCell>
                <TableCell align="right">{money(i.outstanding, i.currency)}</TableCell>
                <TableCell><Chip size="small" variant="outlined" label={humanize(i.status)} color={invoiceStatusColor(i.status)} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {creating && <InvoiceEditorDialog fixedPatient={{ id: patient.id, name: fullName(patient) }} onClose={() => setCreating(false)}
        onSaved={(id) => navigate(`/billing/invoices/${id}`)} />}
    </Paper>
  );
}
