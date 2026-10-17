import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';

/** Read-only label/value pairs laid out in a responsive grid. */
export function DetailList({ items, columns = 3 }: { items: { label: string; value: ReactNode }[]; columns?: number }) {
  return (
    <Box
      component="dl"
      sx={{
        m: 0,
        display: 'grid',
        gap: 2,
        gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', md: `repeat(${columns}, minmax(0, 1fr))` },
      }}
    >
      {items.map((i) => (
        <Box key={i.label}>
          <Typography component="dt" variant="caption" color="text.secondary">
            {i.label}
          </Typography>
          <Typography component="dd" sx={{ m: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {i.value ?? '—'}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}
