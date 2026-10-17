import type { ReactNode } from 'react';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import PeopleAltOutlinedIcon from '@mui/icons-material/PeopleAltOutlined';
import EventOutlinedIcon from '@mui/icons-material/EventOutlined';
import FolderSharedOutlinedIcon from '@mui/icons-material/FolderSharedOutlined';
import ManageAccountsOutlinedIcon from '@mui/icons-material/ManageAccountsOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import PolicyOutlinedIcon from '@mui/icons-material/PolicyOutlined';
import RuleOutlinedIcon from '@mui/icons-material/RuleOutlined';
import ReceiptLongOutlinedIcon from '@mui/icons-material/ReceiptLongOutlined';
import LocalOfferOutlinedIcon from '@mui/icons-material/LocalOfferOutlined';
import SyncAltOutlinedIcon from '@mui/icons-material/SyncAltOutlined';
import InsightsOutlinedIcon from '@mui/icons-material/InsightsOutlined';
import type { Role } from '../auth/api';

export interface NavItem {
  label: string;
  to: string;
  icon: ReactNode;
  roles: Role[];
}

/** Role-aware menu. Cosmetic only: the backend authorizes every request independently. */
const ITEMS: NavItem[] = [
  { label: 'Dashboard', to: '/', icon: <DashboardOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN', 'PATIENT'] },
  { label: 'My record', to: '/my-record', icon: <FolderSharedOutlinedIcon />, roles: ['PATIENT'] },
  { label: 'Patients', to: '/patients', icon: <PeopleAltOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN'] },
  { label: 'Appointments', to: '/appointments', icon: <EventOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN', 'PATIENT'] },
  { label: 'Billing', to: '/billing', icon: <ReceiptLongOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN', 'PATIENT'] },
  { label: 'Reports', to: '/reports', icon: <InsightsOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN'] },
  { label: 'Decision rules', to: '/cdss/rules', icon: <RuleOutlinedIcon />, roles: ['ADMIN', 'CLINICIAN'] },
  { label: 'Users', to: '/admin/users', icon: <ManageAccountsOutlinedIcon />, roles: ['ADMIN'] },
  { label: 'Audit log', to: '/admin/audit', icon: <PolicyOutlinedIcon />, roles: ['ADMIN'] },
  { label: 'Billing & coding', to: '/admin/billing-coding', icon: <LocalOfferOutlinedIcon />, roles: ['ADMIN'] },
  { label: 'HL7 integration', to: '/admin/hl7', icon: <SyncAltOutlinedIcon />, roles: ['ADMIN'] },
  { label: 'Settings', to: '/admin/settings', icon: <SettingsOutlinedIcon />, roles: ['ADMIN'] },
];

export function navItems(role: Role | undefined): NavItem[] {
  return role ? ITEMS.filter((i) => i.roles.includes(role)) : [];
}
