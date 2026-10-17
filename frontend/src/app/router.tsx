import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from './AppShell';
import { NotFoundPage } from './NotFoundPage';
import { RequireAuth } from '../auth/RequireAuth';
import { LoginPage } from '../auth/LoginPage';
import { RegisterPage } from '../auth/RegisterPage';
import { DashboardPage } from '../dashboard/DashboardPage';
import { PatientDirectoryPage } from '../patients/PatientDirectoryPage';
import { PatientDetailPage } from '../patients/PatientDetailPage';
import { EncounterPage } from '../encounters/EncounterPage';
import { AppointmentsPage } from '../appointments/AppointmentsPage';
import { UsersPage } from '../settings/UsersPage';
import { SettingsPage } from '../settings/SettingsPage';
import { AuditPage } from '../settings/AuditPage';
import { CdssPage } from '../cdss/CdssPage';
import { RulesPage } from '../cdss/RulesPage';
import { InvoicesPage } from '../billing/InvoicesPage';
import { InvoiceDetailPage } from '../billing/InvoiceDetailPage';
import { CodingAndBillingPage } from '../settings/CodingAndBillingPage';
import { Hl7Page } from '../settings/Hl7Page';
import { ReportsPage } from '../reports/ReportsPage';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'my-record', element: <RequireAuth roles={['PATIENT']}><PatientDetailPage self /></RequireAuth> },
      { path: 'patients', element: <RequireAuth roles={['ADMIN', 'CLINICIAN']}><PatientDirectoryPage /></RequireAuth> },
      { path: 'patients/:id', element: <RequireAuth roles={['ADMIN', 'CLINICIAN']}><PatientDetailPage /></RequireAuth> },
      { path: 'patients/:id/cdss', element: <RequireAuth roles={['CLINICIAN']}><CdssPage /></RequireAuth> },
      { path: 'cdss/rules', element: <RequireAuth roles={['ADMIN', 'CLINICIAN']}><RulesPage /></RequireAuth> },
      { path: 'encounters/:id', element: <RequireAuth roles={['CLINICIAN', 'PATIENT']}><EncounterPage /></RequireAuth> },
      { path: 'appointments', element: <AppointmentsPage /> },
      { path: 'billing', element: <InvoicesPage /> },
      { path: 'billing/invoices/:id', element: <InvoiceDetailPage /> },
      { path: 'reports', element: <RequireAuth roles={['ADMIN', 'CLINICIAN']}><ReportsPage /></RequireAuth> },
      { path: 'admin/billing-coding', element: <RequireAuth roles={['ADMIN']}><CodingAndBillingPage /></RequireAuth> },
      { path: 'admin/hl7', element: <RequireAuth roles={['ADMIN']}><Hl7Page /></RequireAuth> },
      { path: 'admin/users', element: <RequireAuth roles={['ADMIN']}><UsersPage /></RequireAuth> },
      { path: 'admin/settings', element: <RequireAuth roles={['ADMIN']}><SettingsPage /></RequireAuth> },
      { path: 'admin/audit', element: <RequireAuth roles={['ADMIN']}><AuditPage /></RequireAuth> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
