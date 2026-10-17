import { useState } from 'react';
import {
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
import { useAudit } from './adminApi';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDateTime } from '../shared/format';

const outcomeColor = { SUCCESS: 'success', DENIED: 'error', FAILURE: 'warning' } as const;

export function AuditPage() {
  const [actor, setActor] = useState('');
  const [patientId, setPatientId] = useState('');
  const [outcome, setOutcome] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [page, setPage] = useState(0);
  const { data, isPending, error, refetch } = useAudit({
    actor: actor.trim() || undefined,
    patientId: patientId ? Number(patientId) : undefined,
    outcome: outcome || undefined,
    resourceType: resourceType.trim() || undefined,
    page,
    size: 50,
  });

  return (
    <>
      <PageHeader title="Audit log" subtitle="Access to sensitive records and administrative changes. Identifiers only — no clinical content." />
      <Paper>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 2 }}>
          <TextField size="small" label="Actor username" value={actor} onChange={(e) => { setActor(e.target.value); setPage(0); }} />
          <TextField size="small" label="Patient ID" type="number" value={patientId} onChange={(e) => { setPatientId(e.target.value); setPage(0); }} />
          <TextField size="small" label="Resource type" value={resourceType} onChange={(e) => { setResourceType(e.target.value); setPage(0); }} placeholder="e.g. Patient" />
          <TextField size="small" select label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }} sx={{ minWidth: 140 }}>
            <MenuItem value="">All</MenuItem>
            <MenuItem value="SUCCESS">Success</MenuItem>
            <MenuItem value="DENIED">Denied</MenuItem>
            <MenuItem value="FAILURE">Failure</MenuItem>
          </TextField>
        </Stack>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} onRetry={() => void refetch()} />}
        {data?.totalElements === 0 && <EmptyState title="No audit entries match" />}
        {!!data?.totalElements && (
          <>
            <TableContainer>
              <Table size="small" aria-label="Audit log">
                <TableHead>
                  <TableRow>
                    <TableCell>Time</TableCell>
                    <TableCell>Actor</TableCell>
                    <TableCell>Action</TableCell>
                    <TableCell>Resource</TableCell>
                    <TableCell>Patient</TableCell>
                    <TableCell>Outcome</TableCell>
                    <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>Detail</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((a) => (
                    <TableRow key={a.id}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(a.occurredAt)}</TableCell>
                      <TableCell>{a.actorUsername ?? '—'}{a.actorRole ? ` (${a.actorRole})` : ''}</TableCell>
                      <TableCell>{a.action}</TableCell>
                      <TableCell>{a.resourceType}{a.resourceId ? ` #${a.resourceId}` : ''}</TableCell>
                      <TableCell>{a.patientId ?? '—'}</TableCell>
                      <TableCell><Chip size="small" label={a.outcome} color={outcomeColor[a.outcome]} variant="outlined" /></TableCell>
                      <TableCell sx={{ display: { xs: 'none', lg: 'table-cell' } }}>{a.detail ?? ''}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={data.totalElements} page={data.page} rowsPerPage={data.size} rowsPerPageOptions={[data.size]} onPageChange={(_, p) => setPage(p)} />
          </>
        )}
      </Paper>
    </>
  );
}
