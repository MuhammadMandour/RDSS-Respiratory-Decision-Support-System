import { Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { useSystemStatus } from './systemApi';

export function SystemStatusCard() {
  const { data, isPending, error, refetch } = useSystemStatus();

  return (
    <Paper sx={{ p: 3 }}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
        <Typography variant="h3" component="h2">
          System status
        </Typography>
        {data && <Chip label={data.status} color={data.status === 'UP' ? 'success' : 'warning'} size="small" />}
      </Stack>
      {isPending && <LoadingState label="Checking services…" />}
      {error && <ErrorState error={error} onRetry={() => void refetch()} />}
      {data && (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {data.application} {data.version} · checked {new Date(data.checkedAt).toLocaleTimeString()}
          </Typography>
          <Table size="small" aria-label="Dependency status">
            <TableHead>
              <TableRow>
                <TableCell>Service</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Detail</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.dependencies.map((d) => (
                <TableRow key={d.name}>
                  <TableCell sx={{ textTransform: 'capitalize' }}>{d.name}</TableCell>
                  <TableCell>
                    <Chip label={d.status} size="small" color={d.status === 'UP' ? 'success' : 'error'} variant="outlined" />
                  </TableCell>
                  <TableCell>{d.detail ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </>
      )}
    </Paper>
  );
}
