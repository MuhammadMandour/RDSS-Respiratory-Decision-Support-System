import type { ReactNode } from 'react';
import { Box } from '@mui/material';

/** Responsive form layout: one column on phones, `columns` on wider screens. */
export function FormGrid({ children, columns = 2 }: { children: ReactNode; columns?: number }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gap: 2,
        gridTemplateColumns: { xs: '1fr', sm: `repeat(${columns}, minmax(0, 1fr))` },
      }}
    >
      {children}
    </Box>
  );
}

/** Spans the full width of a FormGrid. */
export function FullRow({ children }: { children: ReactNode }) {
  return <Box sx={{ gridColumn: '1 / -1' }}>{children}</Box>;
}
