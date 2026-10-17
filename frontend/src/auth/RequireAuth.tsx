import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Alert } from '@mui/material';
import { useAuth } from './AuthContext';
import type { Role } from './api';

/**
 * Route guard for UX only. Hiding a page does not protect data — the backend enforces
 * authorization on every request.
 */
export function RequireAuth({ children, roles }: { children: ReactNode; roles?: Role[] }) {
  const { isAuthenticated, hasRole } = useAuth();
  const location = useLocation();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (roles && !hasRole(...roles)) {
    return <Alert severity="warning">You do not have access to this page.</Alert>;
  }
  return <>{children}</>;
}
