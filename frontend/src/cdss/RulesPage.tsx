import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Link,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { cdssApi, cdssKeys, useRules, type Rule } from './api';
import { PageHeader } from '../shared/components/PageHeader';
import { ErrorState, LoadingState } from '../shared/components/QueryState';
import { formatDateTime, humanize } from '../shared/format';
import { useAuth } from '../auth/AuthContext';
import { toApiRequestError } from '../shared/api/client';

/** Rule catalogue with evidence references. Administrators can toggle rules and tune declared parameters. */
export function RulesPage() {
  const { data, isPending, error } = useRules();
  return (
    <>
      <PageHeader title="Decision-support rules" subtitle="Deterministic rules, their sources and validation status" />
      {isPending && <LoadingState />}
      {error && <ErrorState error={error} />}
      <Stack spacing={2}>{data?.map((r) => <RuleCard key={r.code} rule={r} />)}</Stack>
    </>
  );
}

function RuleCard({ rule: r }: { rule: Rule }) {
  const { hasRole } = useAuth();
  const admin = hasRole('ADMIN');
  const [params, setParams] = useState<Record<string, string>>(() => Object.fromEntries(Object.entries(r.parameters).map(([k, v]) => [k, String(v)])));
  const qc = useQueryClient();
  const m = useMutation({
    mutationFn: (enabled: boolean) => cdssApi.updateRule(r.code, enabled, Object.fromEntries(Object.entries(params).map(([k, v]) => [k, Number(v)]))),
    onSuccess: () => qc.invalidateQueries({ queryKey: cdssKeys.rules }),
  });
  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', mb: 1 }}>
          <Typography variant="h3" component="h2">{r.name}</Typography>
          <Chip size="small" label={r.code} variant="outlined" />
          <Chip size="small" label={humanize(r.category)} />
          <Chip size="small" color={r.validatedMethod ? 'success' : 'warning'} variant="outlined"
            label={r.validatedMethod ? 'Implements a validated method' : 'Not a validated score'} />
          <Box sx={{ flex: 1 }} />
          {admin ? (
            <Switch checked={r.enabled} onChange={(e) => m.mutate(e.target.checked)} slotProps={{ input: { 'aria-label': `Enable ${r.code}` } }} />
          ) : (
            <Chip size="small" label={r.enabled ? 'Enabled' : 'Disabled'} color={r.enabled ? 'success' : 'default'} />
          )}
        </Stack>
        <Typography variant="body2" sx={{ mb: 1 }}>{r.description}</Typography>
        <Typography variant="caption" color="text.secondary" component="p">
          Registry version {r.ruleVersion} · implementation {r.implementedVersion ?? 'missing'} · updated {formatDateTime(r.updatedAt)}{r.updatedBy ? ` by ${r.updatedBy}` : ''}
        </Typography>
        {r.guidelines.map((g) => (
          <Typography key={g.code} variant="body2">
            {g.url ? <Link href={g.url} target="_blank" rel="noopener noreferrer">{g.title}</Link> : g.title} — {g.publisher}, {g.version}
          </Typography>
        ))}
        {Object.keys(r.parameters).length > 0 && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2, alignItems: { sm: 'center' }, flexWrap: 'wrap' }}>
            {Object.keys(params).map((k) => (
              <TextField key={k} size="small" type="number" label={k} value={params[k]} disabled={!admin}
                onChange={(e) => setParams((s) => ({ ...s, [k]: e.target.value }))} />
            ))}
            {admin && <Button onClick={() => m.mutate(r.enabled)} disabled={m.isPending}>Save parameters</Button>}
          </Stack>
        )}
        {m.error && <Alert severity="error" sx={{ mt: 1 }}>{toApiRequestError(m.error).message}</Alert>}
      </CardContent>
    </Card>
  );
}
