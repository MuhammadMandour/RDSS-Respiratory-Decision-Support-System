import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import LogoutIcon from '@mui/icons-material/Logout';
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined';
import { navItems } from './navigation';
import { useAuth } from '../auth/AuthContext';
import { humanize } from '../shared/format';
import { ChangePasswordDialog } from '../auth/ChangePasswordDialog';

const DRAWER_WIDTH = 240;

export function AppShell() {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'));
  const [mobileOpen, setMobileOpen] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [changingPassword, setChangingPassword] = useState(false);
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const items = navItems(user?.role);

  const drawer = (
    <Box component="nav" aria-label="Main navigation">
      <Toolbar />
      <List>
        {items.map((item) => (
          <ListItemButton
            key={item.to}
            component={NavLink}
            to={item.to}
            end={item.to === '/'}
            onClick={() => setMobileOpen(false)}
            sx={{ '&.active': { bgcolor: 'action.selected', '& .MuiListItemText-primary': { fontWeight: 600 } } }}
          >
            <ListItemIcon sx={{ minWidth: 36 }}>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} />
          </ListItemButton>
        ))}
      </List>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}>
        <Toolbar>
          {!isDesktop && (
            <IconButton color="inherit" edge="start" aria-label="Open navigation" onClick={() => setMobileOpen(true)} sx={{ mr: 1 }}>
              <MenuIcon />
            </IconButton>
          )}
          <Typography variant="h6" component="div" sx={{ flexGrow: 1, fontWeight: 600 }}>
            RDSS
            <Typography component="span" variant="body2" sx={{ ml: 1, opacity: 0.8, display: { xs: 'none', sm: 'inline' } }}>
              Respiratory Decision Support
            </Typography>
          </Typography>
          {user && (
            <Tooltip title="Account">
              <IconButton color="inherit" onClick={(e) => setMenuAnchor(e.currentTarget)} aria-label="Account menu">
                <Avatar sx={{ width: 32, height: 32, bgcolor: 'secondary.main', fontSize: 14 }}>
                  {user.fullName.split(' ').map((s) => s[0]).slice(0, 2).join('')}
                </Avatar>
              </IconButton>
            </Tooltip>
          )}
        </Toolbar>
      </AppBar>
      <Menu anchorEl={menuAnchor} open={!!menuAnchor} onClose={() => setMenuAnchor(null)}>
        <Box sx={{ px: 2, py: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>{user?.fullName}</Typography>
          <Typography variant="body2" color="text.secondary">{user?.username} · {humanize(user?.role)}</Typography>
        </Box>
        <Divider />
        <MenuItem onClick={() => { setMenuAnchor(null); setChangingPassword(true); }}>
          <ListItemIcon><KeyOutlinedIcon fontSize="small" /></ListItemIcon>Change password
        </MenuItem>
        <MenuItem onClick={() => { setMenuAnchor(null); signOut(); navigate('/login'); }}>
          <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>Sign out
        </MenuItem>
      </Menu>
      <Drawer
        variant={isDesktop ? 'permanent' : 'temporary'}
        open={isDesktop || mobileOpen}
        onClose={() => setMobileOpen(false)}
        sx={{ width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' } }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, minWidth: 0 }}>
        <Toolbar />
        <Outlet />
      </Box>
      {changingPassword && <ChangePasswordDialog onClose={() => setChangingPassword(false)} />}
    </Box>
  );
}
