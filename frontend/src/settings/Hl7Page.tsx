import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
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
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { hl7Api, useHl7Messages, useHl7Status, type Hl7Status } from './hl7Api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { DetailList } from '../shared/components/DetailList';
import { formatDateTime } from '../shared/format';
import { toApiRequestError } from '../shared/api/client';

const statusColor = (s: Hl7Status) =>
  ({ PROCESSED: 'success', SENT: 'success', RECEIVED: 'info', PENDING: 'info', FAILED: 'error', REJECTED: 'warning' } as const)[s];

export function Hl7Page() {
  const status = useHl7Status();
  const [direction, setDirection] = useState('');
  const [state, setState] = useState('');
  const [type, setType] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const { data, isPending, error, refetch } = useHl7Messages({ direction: direction || undefined, status: state || undefined, type: type || undefined, page });

  return (
    <>
      <PageHeader title="HL7 integration" subtitle="Message log, acknowledgements and reprocessing" />
      <Paper sx={{ p: 2, mb: 2 }}>
        {status.isPending && <LoadingState />}
        {status.error && <ErrorState error={status.error} />}
        {status.data && (
          <DetailList columns={4} items={[
            { label: 'HTTP inbound', value: status.data.httpInboundEnabled ? 'Enabled (integration key)' : 'Disabled (no key configured)' },
            { label: 'MLLP listener', value: status.data.mllpEnabled ? `Port ${status.data.mllpPort}` : 'Disabled' },
            { label: 'Outbound ADT^A08', value: status.data.outboundEnabled ? 'Enabled' : 'Disabled' },
            { label: 'Version', value: status.data.hl7Version },
            { label: 'Supported inbound', value: status.data.supportedInboundTypes.join(', ') },
          ]} />
        )}
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1 }}>
          Compatibility with a specific EMR must be verified against its interface specification; see docs/HL7.md.
        </Typography>
      </Paper>
      <Paper>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 2 }}>
          <TextField size="small" select label="Direction" value={direction} onChange={(e) => { setDirection(e.target.value); setPage(0); }} sx={{ minWidth: 140 }}>
            <MenuItem value="">All</MenuItem><MenuItem value="INBOUND">Inbound</MenuItem><MenuItem value="OUTBOUND">Outbound</MenuItem>
          </TextField>
          <TextField size="small" select label="Status" value={state} onChange={(e) => { setState(e.target.value); setPage(0); }} sx={{ minWidth: 140 }}>
            <MenuItem value="">All</MenuItem>
            {(['PROCESSED', 'FAILED', 'REJECTED', 'RECEIVED', 'PENDING', 'SENT'] as const).map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
          </TextField>
          <TextField size="small" label="Type (e.g. ORU)" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} />
        </Stack>
        {isPending && <LoadingState />}
        {error && <Box sx={{ p: 2 }}><ErrorState error={error} onRetry={() => void refetch()} /></Box>}
        {data?.totalElements === 0 && <EmptyState title="No HL7 messages" />}
        {!!data?.totalElements && (
          <>
            <TableContainer>
              <Table size="small" aria-label="HL7 messages">
                <TableHead>
                  <TableRow>
                    <TableCell>Received</TableCell><TableCell>Dir</TableCell><TableCell>Type</TableCell><TableCell>Sender</TableCell>
                    <TableCell>Control id</TableCell><TableCell>Status</TableCell><TableCell>ACK</TableCell><TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>Error</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((m) => (
                    <TableRow key={m.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(m.id)} tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && setOpen(m.id)}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(m.createdAt)}</TableCell>
                      <TableCell>{m.direction === 'INBOUND' ? 'IN' : 'OUT'} · {m.transport}</TableCell>
                      <TableCell>{m.messageType ?? '—'}</TableCell>
                      <TableCell>{m.sendingApplication ?? '—'}@{m.sendingFacility ?? '—'}</TableCell>
                      <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>{m.controlId ?? '—'}{m.duplicateCount > 0 ? ` (+${m.duplicateCount} dup)` : ''}</TableCell>
                      <TableCell><Chip size="small" variant="outlined" label={m.status} color={statusColor(m.status)} /></TableCell>
                      <TableCell>{m.ackCode ?? '—'}</TableCell>
                      <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' }, maxWidth: 320 }}>{m.errorMessage ?? ''}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={data.size} rowsPerPageOptions={[data.size]} onPageChange={(_, p) => setPage(p)} />
          </>
        )}
      </Paper>
      {open && <MessageDialog id={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function MessageDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const { data, isPending, error } = useQuery({ queryKey: ['hl7', 'message', id], queryFn: () => hl7Api.message(id) });
  const qc = useQueryClient();
  const reprocess = useMutation({
    mutationFn: () => hl7Api.reprocess(id),
    onSuccess: (r) => { qc.setQueryData(['hl7', 'message', id], r); void qc.invalidateQueries({ queryKey: ['hl7', 'messages'] }); },
  });
  const mono = { fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all', bgcolor: 'action.hover', p: 1, borderRadius: 1 } as const;
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>HL7 message #{id}</DialogTitle>
      <DialogContent dividers>
        <Alert severity="warning" sx={{ mb: 2 }}>Message content may contain patient-identifiable information. This view is audited.</Alert>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} />}
        {reprocess.error && <Alert severity="error" sx={{ mb: 2 }}>{toApiRequestError(reprocess.error).message}</Alert>}
        {data && (
          <Stack spacing={2}>
            <DetailList items={[
              { label: 'Type', value: data.summary.messageType ?? '—' },
              { label: 'Status', value: data.summary.status },
              { label: 'Attempts', value: data.summary.attempts },
              { label: 'Resource', value: data.summary.resourceType ? `${data.summary.resourceType} #${data.summary.resourceId}` : '—' },
              { label: 'Error', value: data.summary.errorMessage ?? '—' },
              { label: 'Next retry', value: data.summary.retryable ? formatDateTime(data.summary.nextAttemptAt) : 'No automatic retry' },
            ]} />
            <Typography variant="subtitle2">Message</Typography>
            <Box sx={mono}>{data.rawMessage.replace(/\r/g, '\n')}</Box>
            <Typography variant="subtitle2">Acknowledgement</Typography>
            <Box sx={mono}>{data.ackMessage?.replace(/\r/g, '\n') ?? '—'}</Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {data && data.summary.direction === 'INBOUND' && (data.summary.status === 'FAILED' || data.summary.status === 'REJECTED') && (
          <Button onClick={() => reprocess.mutate()} disabled={reprocess.isPending}>Reprocess</Button>
        )}
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
