import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, useSearchParams } from 'react-router-dom';
import { request, type User } from './api';
import { ArrowUpRight, CalendarDays, LogOut, UserRound } from 'lucide-react';
import { Failure, Loading, PageHeader } from './components';
import { TelegramAccount } from './Telegram';

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
  return <div className="session-navigation">
    {auth.user ? <><NavLink to="/bookings">My bookings</NavLink><NavLink to="/account" className="account-link"><UserRound aria-hidden="true" size={16}/>{auth.user.name}</NavLink>
      {['OWNER', 'ADMIN'].includes(auth.user.role) && <><NavLink to="/owner">Manage spaces</NavLink><NavLink to="/analytics">Analytics</NavLink></>}
      {auth.user.role === 'ADMIN' && <NavLink to="/admin">Administration</NavLink>}
      <button className="sp-button sp-button--small sp-button--ghost" disabled={pending} onClick={async () => { setPending(true); setError(undefined); try { await auth.logout(); } catch (error) { setError(error as Error); } finally { setPending(false); } }}><LogOut aria-hidden="true"/> Sign out</button></>
      : <Link to="/login">Sign in with Google</Link>}
    {(error || auth.error) && <span role="alert">Session unavailable. <button className="sp-button sp-button--small sp-button--ghost" onClick={auth.refresh}>Retry session</button></span>}
  </div>;
}

export function RequireRole({ roles, children }: { roles?: string[]; children: ReactNode }) {
  const auth = useAuth();
  if (auth.loading) return <Loading/>;
  if (auth.error) return <Failure error={auth.error} retry={auth.refresh}/>;
  if (!auth.user) return <section className="auth-panel"><p className="eyebrow">YOUR NEXT WORKING DAY</p><h1>Sign in to continue</h1><Link to="/login" className="sp-button sp-button--primary">Sign in with Google</Link></section>;
  if (roles && !roles.includes(auth.user.role)) return <section className="auth-panel"><p className="eyebrow">A PRIVATE CORNER</p><h1>Access denied</h1><p>This page requires a different role.</p><Link to="/spaces">Explore spaces</Link></section>;
  return children;
}

export function Login() {
  const auth = useAuth(); const [params] = useSearchParams();
  return <section className="auth-layout"><div className="auth-panel"><p className="eyebrow">MAKE ROOM FOR GOOD WORK</p><h1>Sign in to SpotPulse</h1><p>Sign in with Google to book a room and keep track of your plans.</p>
    {params.get('error') && <p role="alert" className="alert alert-danger">{params.get('error') === 'account_migration_required' ? 'An existing account uses this email. Automatic linking is disabled; contact the platform administrator for a reviewed migration.' : 'Google sign-in failed or was cancelled. Please try again.'}</p>}
    {auth.loading ? <Loading/> : auth.user ? <Link to="/account">View your profile</Link> : auth.loginEnabled ? <a className="sp-button sp-button--primary" href={loginUrl}>Continue with Google</a> : <p>Google sign-in is currently unavailable. Please try again later.</p>}
  </div><aside className="auth-aside" aria-hidden="true"><span>HELLO, POSSIBILITY.</span><p>Good days<br/>start here.</p><ArrowUpRight strokeWidth={1}/></aside></section>;
}

export function Profile() {
  const { user } = useAuth();
  return <><PageHeader eyebrow="YOUR CORNER" title="Your profile" description="A little about you. Everything for your next visit."/>
    <div className="account-layout"><section className="identity-card"><span className="profile-avatar" aria-hidden="true">{user?.name.slice(0, 1)}</span><p className="eyebrow">NICE TO SEE YOU</p><h2>{user?.name}</h2><p className="profile-email">{user?.email}</p><dl className="account-details"><div><dt>Membership</dt><dd>{user?.role === 'USER' ? 'Member' : user?.role === 'OWNER' ? 'Space owner' : 'Administrator'}</dd></div><div><dt>Account</dt><dd>#{user?.id}</dd></div></dl><p className="account-reference">Role: {user?.role}</p><Link className="sp-button sp-button--primary mt-4" to="/bookings"><CalendarDays aria-hidden="true"/> My bookings</Link></section><TelegramAccount key={user?.id}/></div>
  </>;
}
