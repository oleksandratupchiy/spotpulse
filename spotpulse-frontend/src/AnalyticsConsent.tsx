import { useEffect, useId, useState, useSyncExternalStore } from 'react';
import { useLocation } from 'react-router-dom';
import { createPortal } from 'react-dom';
import { createUsageAnalytics, usageAnalytics } from './usageAnalytics';

export function AnalyticsConsent({ analytics = usageAnalytics, portal }: { analytics?: ReturnType<typeof createUsageAnalytics>; portal?: HTMLElement | null }) {
  const location = useLocation();
  const choicesId = useId();
  const choice = useSyncExternalStore(analytics.subscribe, analytics.getChoice);
  const [editing, setEditing] = useState(false);
  const [privacy, setPrivacy] = useState(analytics.blocked);
  useEffect(() => {
    analytics.setEnabled(true);
    analytics.page(location.pathname);
  }, [analytics, location.pathname]);
  useEffect(() => {
    const sync = () => { analytics.sync(); setPrivacy(analytics.blocked()); };
    const resume = () => { analytics.resume(); setPrivacy(analytics.blocked()); };
    window.addEventListener('storage', sync); document.addEventListener('visibilitychange', resume);
    return () => { window.removeEventListener('storage', sync); document.removeEventListener('visibilitychange', resume); };
  }, [analytics]);
  if (!analytics.configured) return null;
  const visible = choice === 'unset' || editing;
  const preferences = <aside className="privacy-preferences" aria-label="Optional analytics">
    <button className="sp-button sp-button--small sp-button--ghost" aria-expanded={visible} aria-controls={visible ? choicesId : undefined} onClick={() => setEditing(v => !v)}>Analytics preferences</button>
    {!visible && <p className="small mt-2">Analytics {choice === 'accepted' && !privacy ? 'accepted' : 'off'}. You can change your choice here.</p>}
  </aside>;
  const choices = visible && <section id={choicesId} className="privacy-choices" role="region" aria-label="Analytics consent">
      <h2 className="h5">Your privacy, your choice</h2>
      <p>Allow optional Google Analytics to measure use of SpotPulse? We send coarse page categories and actions, without account details, search text or booking details. Google processes device/network metadata and may set analytics cookies after acceptance. Login uses separate necessary cookies.</p>
      {privacy && <p>Your browser privacy preference blocks analytics.</p>}
      <div className="privacy-actions">
        <button className="sp-button sp-button--primary" disabled={privacy} onClick={() => { analytics.choose('accepted'); setEditing(false); }}>Accept analytics</button>
        <button className="sp-button sp-button--ghost" onClick={() => { analytics.choose('rejected'); setEditing(false); }}>Reject analytics</button>
        {editing && choice !== 'unset' && <button className="sp-button sp-button--ghost" onClick={() => setEditing(false)}>Close preferences</button>}
      </div>
    </section>;
  // Keep the persistent control in the footer and the prompt within the viewport.
  // The controller remains before page effects to preserve tracking event ordering.
  return <>{portal === undefined ? preferences : portal ? createPortal(preferences, portal) : null}{createPortal(choices, document.body)}</>;
}
