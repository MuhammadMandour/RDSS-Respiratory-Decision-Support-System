import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Chip,
  FormControlLabel,
  InputAdornment,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import PersonAddAltOutlinedIcon from '@mui/icons-material/PersonAddAltOutlined';
import { fullName, usePatientSearch } from './api';
import { PageHeader } from '../shared/components/PageHeader';
import { EmptyState, ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDate, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { PatientFormDialog } from './PatientFormDialog';

export function PatientDirectoryPage() {
  const { hasRole } = useAuth();
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [creating, setCreating] = useState(false);

  // Debounce typing so the directory is not queried on every keystroke.
  useEffect(() => {
    const id = window.setTimeout(() => {
      setQ(input.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(id);
  }, [input]);

  const { data, isPending, error, refetch } = usePatientSearch(q, page, includeInactive);

  return (
    <>
      <PageHeader
        title="Patients"
        subtitle={hasRole('CLINICIAN') ? 'Patients on your care team' : 'All registered patients'}
        actions={
          <Button variant="contained" startIcon={<PersonAddAltOutlinedIcon />} onClick={() => setCreating(true)}>
            Register patient
          </Button>
        }
      />
      <Paper>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ p: 2, alignItems: { sm: 'center' } }}>
          <TextField
            size="small"
            placeholder="Search by name or MRN"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            sx={{ flex: 1, maxWidth: 420 }}
            slotProps={{
              htmlInput: { 'aria-label': 'Search patients' },
              input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> },
            }}
          />
          <FormControlLabel
            control={<Switch checked={includeInactive} onChange={(e) => { setIncludeInactive(e.target.checked); setPage(0); }} />}
            label="Include inactive"
          />
        </Stack>
        {isPending && <LoadingState />}
        {error && <ErrorState error={error} onRetry={() => void refetch()} />}
        {data && data.totalElements === 0 && (
          <EmptyState title="No patients found" description={q ? 'Try a different search.' : 'Register a patient to get started.'} />
        )}
        {data && data.totalElements > 0 && (
          <>
            <TableContainer>
              <Table size="small" aria-label="Patient directory">
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell>MRN</TableCell>
                    <TableCell>Date of birth</TableCell>
                    <TableCell>Age / sex</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Smoking</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Primary clinician</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((p) => (
                    <TableRow
                      key={p.id}
                      hover
                      sx={{ cursor: 'pointer' }}
                      onClick={() => navigate(`/patients/${p.id}`)}
                      onKeyDown={(e) => e.key === 'Enter' && navigate(`/patients/${p.id}`)}
                      tabIndex={0}
                    >
                      <TableCell sx={{ fontWeight: 600 }}>{fullName(p)}</TableCell>
                      <TableCell>{p.mrn}</TableCell>
                      <TableCell>{formatDate(p.dateOfBirth)}</TableCell>
                      <TableCell>{p.age} · {humanize(p.sex)}</TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{humanize(p.smokingStatus)}</TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{p.primaryClinician?.fullName ?? '—'}</TableCell>
                      <TableCell>
                        <Chip size="small" label={p.active ? 'Active' : 'Inactive'} color={p.active ? 'success' : 'default'} variant="outlined" />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination
              component="div"
              count={data.totalElements}
              page={data.page}
              rowsPerPage={data.size}
              rowsPerPageOptions={[data.size]}
              onPageChange={(_, p) => setPage(p)}
            />
          </>
        )}
      </Paper>
      {creating && (
        <PatientFormDialog
          open
          onClose={() => setCreating(false)}
          clinicalFields={hasRole('CLINICIAN')}
          onSaved={(p) => navigate(`/patients/${p.id}`)}
        />
      )}
    </>
  );
}
