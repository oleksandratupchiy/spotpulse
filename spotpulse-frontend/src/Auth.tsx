import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, useSearchParams } from 'react-router-dom';
import { request, type User } from './api';
import { Failure, Loading } from './components';

interface Session { authenticated: boolean; user: User | null; loginEnabled: boolean }
interface AuthState { user: User | null; loading: boolean; loginEnabled: boolean; error?: Error; refresh: () => void; logout: () => Promise<void> }
const Context = createContext<AuthState>({ user: null, loading: true, loginEnabled: false, refresh() {}, async logout() {} });
export const useAuth = () => useContext(Context);
export const loginUrl = (import.meta.env.VITE_AUTH_BASE_URL ?? (import.meta.env.DEV ? 'http://localhost:8080' : '')).replace(/\/$/, '') + '/oauth2/authorization/google';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ loading: boolean; user: User | null; loginEnabled: boolean; error?: Error }>({ loading: true, user: null, loginEnabled: false });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    request<Session>('/api/auth/me', { signal: controller.signal }).then(session => {
      if (!controller.signal.aborted) setState({ loading: false, user: session.authenticated ? session.user : null, loginEnabled: session.loginEnabled });
    }).catch(error => { if (!controller.signal.aborted) setState({ loading: false, user: null, loginEnabled: false, error }); });
    return () => controller.abort();
  }, [version]);
  async function logout() {
    await request('/api/auth/logout', { method: 'POST' });
    setState({ loading: false, user: null, loginEnabled: state.loginEnabled });
  }
  return <Context.Provider value={{ ...state, refresh: () => setVersion(value => value + 1), logout }}>{children}</Context.Provider>;
}

export function SessionNavigation() {
  const auth = useAuth(); const [error, setError] = useState<Error>(); const [pending, setPending] = useState(false);
  if (auth.loading) return <span className="small">Checking session…</span>;
  return <div className="d-flex flex-wrap align-items-center gap-3">
    {auth.user ? <><NavLink to="/bookings">My bookings</NavLink><NavLink to="/account">{auth.user.name}</NavLink>
      {['OWNER', 'ADMIN'].includes(auth.user.role) && <><NavLink to="/owner">Manage spaces</NavLink><NavLink to="/analytics">Analytics</NavLink></>}
      {auth.user.role === 'ADMIN' && <NavLink to="/admin">Administration</NavLink>}
      <button className="btn btn-sm btn-light" disabled={pending} onClick={async () => { setPending(true); setError(undefined); try { await auth.logout(); } catch (error) { setError(error as Error); } finally { setPending(false); } }}>Sign out</button></>
      : <Link to="/login">Sign in with Google</Link>}
    {(error || auth.error) && <span role="alert">Session unavailable. <button className="btn btn-sm btn-light" onClick={auth.refresh}>Retry session</button></span>}
  </div>;
}

export function RequireRole({ roles, children }: { roles?: string[]; children: ReactNode }) {
  const auth = useAuth();
  if (auth.loading) return <Loading/>;
  if (auth.error) return <Failure error={auth.error} retry={auth.refresh}/>;
  if (!auth.user) return <section><h1>Sign in to continue</h1><Link to="/login" className="btn btn-primary">Sign in with Google</Link></section>;
  if (roles && !roles.includes(auth.user.role)) return <section><h1>Access denied</h1><p>This page requires a different role.</p><Link to="/spaces">Explore spaces</Link></section>;
  return children;
}

export function Login() {
  const auth = useAuth(); const [params] = useSearchParams();
  return <section className="hero"><h1>Sign in to SpotPulse</h1><p>Use your Google account to book meetings. New accounts receive the USER role.</p>
    {params.get('error') && <p role="alert" className="alert alert-danger">{params.get('error') === 'account_migration_required' ? 'An existing account uses this email. Automatic linking is disabled; contact the platform administrator for a reviewed migration.' : 'Google sign-in failed or was cancelled. Please try again.'}</p>}
    {auth.loading ? <Loading/> : auth.user ? <Link to="/account">View your profile</Link> : auth.loginEnabled ? <a className="btn btn-primary" href={loginUrl}>Continue with Google</a> : <p>Google sign-in is unavailable. The server must be launched from the PowerShell session containing the Google environment variables.</p>}
  </section>;
}

export function Profile() {
  const { user } = useAuth();
  return <section><h1>Your profile</h1><p>{user?.name}</p><p>{user?.email}</p><p>Role: {user?.role}</p><p>Account ID: {user?.id}</p><Link to="/bookings">My bookings</Link></section>;
}
