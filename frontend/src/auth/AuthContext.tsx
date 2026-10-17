import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { configureAuth } from '../shared/api/client';
import type { AuthResponse, Role, UserInfo } from './api';

interface Session {
  token: string;
  expiresAt: string;
  user: UserInfo;
}

interface AuthContextValue {
  user: UserInfo | null;
  isAuthenticated: boolean;
  signIn: (response: AuthResponse) => void;
  signOut: () => void;
  hasRole: (...roles: Role[]) => boolean;
}

const STORAGE_KEY = 'rdss.session';
const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Session storage (not localStorage) limits the token to the current tab session.
 * The token is only a convenience for the UI: every authorization decision is made server-side.
 */
function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    if (!s.token || new Date(s.expiresAt).getTime() <= Date.now()) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return s;
  } catch {
    return null;
  }
}

// Module-level references so the axios interceptor reads the token synchronously and is
// configured before any component (and therefore any query) renders.
let currentToken: string | null = loadSession()?.token ?? null;
let onUnauthorized: () => void = () => {};
configureAuth(() => currentToken, () => onUnauthorized());

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    const s = loadSession();
    currentToken = s?.token ?? null;
    return s;
  });
  const queryClient = useQueryClient();

  const signOut = useCallback(() => {
    currentToken = null;
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable */
    }
    setSession(null);
    queryClient.clear();
  }, [queryClient]);

  const signIn = useCallback(
    (r: AuthResponse) => {
      const s: Session = { token: r.accessToken, expiresAt: r.expiresAt, user: r.user };
      currentToken = s.token;
      try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s));
      } catch {
        /* storage unavailable: session lasts until reload */
      }
      queryClient.clear();
      setSession(s);
    },
    [queryClient],
  );

  useEffect(() => {
    onUnauthorized = signOut;
  }, [signOut]);

  // Expire the session client-side when the token's lifetime ends.
  useEffect(() => {
    if (!session) return;
    const ms = new Date(session.expiresAt).getTime() - Date.now();
    const id = window.setTimeout(signOut, Math.max(ms, 0));
    return () => window.clearTimeout(id);
  }, [session, signOut]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      isAuthenticated: !!session,
      signIn,
      signOut,
      hasRole: (...roles) => !!session && roles.includes(session.user.role),
    }),
    [session, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

/** Narrowing helper for pages that are only reachable when signed in. */
export function useCurrentUser(): UserInfo {
  const { user } = useAuth();
  if (!user) throw new Error('No authenticated user');
  return user;
}
