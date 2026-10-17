import type { ReactNode } from 'react';
import { Box, Paper, Typography } from '@mui/material';

export function AuthLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', p: 2, bgcolor: 'background.default' }}>
      <Paper component="main" sx={{ p: { xs: 3, sm: 4 }, width: '100%', maxWidth: 460 }}>
        <Typography variant="overline" color="primary" sx={{ fontWeight: 700 }}>
          RDSS · Respiratory Decision Support
        </Typography>
        <Typography variant="h1" sx={{ mb: 3 }}>
          {title}
        </Typography>
        {children}
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 3 }}>
          Development system — not validated for clinical use. Use synthetic data only.
        </Typography>
      </Paper>
    </Box>
  );
}
