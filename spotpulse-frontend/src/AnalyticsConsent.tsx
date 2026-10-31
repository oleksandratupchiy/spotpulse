import { useEffect, useState, useSyncExternalStore } from 'react';
import { useLocation } from 'react-router-dom';
import { createUsageAnalytics, usageAnalytics } from './usageAnalytics';

export function AnalyticsConsent({ analytics = usageAnalytics }: { analytics?: ReturnType<typeof createUsageAnalytics> }) {
  const location = useLocation();
  const choice = useSyncExternalStore(analytics.subscribe, analytics.getChoice);
  const [editing, setEditing] = useState(false);
  const [privacy, setPrivacy] = useState(analytics.blocked);
  useEffect(() => { analytics.page(location.pathname); }, [analytics, location.pathname]);
  useEffect(() => {
    const sync = () => { analytics.sync(); setPrivacy(analytics.blocked()); };
    const resume = () => { analytics.resume(); setPrivacy(analytics.blocked()); };
    window.addEventListener('storage', sync); document.addEventListener('visibilitychange', resume);
    return () => { window.removeEventListener('storage', sync); document.removeEventListener('visibilitychange', resume); };
  }, [analytics]);
  if (!analytics.configured) return null;
  const visible = choice === 'unset' || editing;
  return <aside className="container py-3 border-top" aria-label="Optional analytics">
    <button className="btn btn-sm btn-outline-secondary" aria-expanded={visible} onClick={() => setEditing(v => !v)}>Analytics preferences</button>
    {visible && <div className="mt-2" role="group" aria-label="Analytics consent">
      <p>Allow optional Google Analytics to measure use of SpotPulse? We send coarse page categories and actions, without account details, search text or booking details. Google processes device/network metadata and may set analytics cookies after acceptance. Login uses separate necessary cookies.</p>
      {privacy && <p>Your browser privacy preference blocks analytics.</p>}
      <button className="btn btn-primary me-2" disabled={privacy} onClick={() => { analytics.choose('accepted'); setEditing(false); }}>Accept analytics</button>
      <button className="btn btn-outline-secondary" onClick={() => { analytics.choose('rejected'); setEditing(false); }}>Reject analytics</button>
    </div>}
    {!visible && <p className="small mt-2">Analytics {choice === 'accepted' && !privacy ? 'accepted' : 'off'}. You can change your choice here.</p>}
  </aside>;
}
