import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, ApiError, message, type Account, type Membership, type Pool, type Session } from './api';
import './style.css';

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [page, setPage] = useState('pools');
  const [menu, setMenu] = useState(false);
  const [busy, setBusy] = useState(false);
  async function refresh() {
    setLoading(true); setError('');
    try { setSession(await api<Session>('/session')); }
    catch (e) {
      setSession(null);
      if (!(e instanceof ApiError && e.status === 401)) setError(message(e));
    } finally { setLoading(false); }
  }
  useEffect(() => { void refresh(); }, []);
  function failed(e: unknown) {
    setError(message(e));
    if (e instanceof ApiError && e.status === 401) setSession(null);
  }
  async function logout() {
    if (!session) return;
    setBusy(true);
    try { await api<void>('/session', { method: 'DELETE' }, session.csrfToken); setSession(null); setPage('pools'); }
    catch (e) { failed(e); }
    finally { setBusy(false); }
  }
  const loginError = new URLSearchParams(window.location.search).get('login');
  if (loading) return <main className="login"><div className="brand"><b>s</b>skill hunters.</div><p role="status">Vérification de votre session…</p></main>;
  if (!session) return <main className="login"><div className="brand"><b>s</b>skill hunters.</div><p className="eyebrow">Espace recrutement</p><h1>Vos talents.<br/>Un espace sécurisé.</h1><p className="muted">Connectez-vous pour retrouver les viviers auxquels votre équipe vous a donné accès.</p>{(error || loginError) && <p className="alert" role="alert">{error || (loginError === 'not-authorized' ? 'Votre compte n’est pas encore habilité. Contactez votre administrateur.' : 'La connexion n’a pas abouti. Réessayez.')}</p>}<a className="button primary" href="/oauth2/authorization/skillhunters">Se connecter avec le compte professionnel</a><p className="fine">L’authentification est assurée par le fournisseur d’identité de votre organisation.</p><button className="text-button" onClick={() => void refresh()}>Vérifier à nouveau la connexion</button></main>;
  const admin = session.roles.includes('ADMIN');
  return <div className="shell">
    <aside className={menu ? 'sidebar open' : 'sidebar'} aria-label="Navigation principale">
      <a className="brand" href="#" onClick={e => { e.preventDefault(); setPage('pools'); setMenu(false); }}><b>s</b>skill hunters.</a>
      <div className="workspace">Équipe Talent<span>Espace de recrutement</span></div>
      <p className="nav-label">Recrutement</p>
      <button className={page === 'pools' ? 'nav active' : 'nav'} onClick={() => { setPage('pools'); setMenu(false); }}>▤ &nbsp; Mes viviers <span>{session.poolIds.length}</span></button>
      {admin && <><p className="nav-label">Administration</p><button className={page === 'access' ? 'nav active' : 'nav'} onClick={() => { setPage('access'); setMenu(false); }}>⚙ &nbsp; Habilitations</button></>}
      <div className="side-foot"><strong>{session.displayName}</strong><span>{admin ? 'Administrateur' : 'Membre de l’équipe'}</span><button className="nav" onClick={() => void logout()} disabled={busy}>{busy ? 'Déconnexion…' : 'Se déconnecter'}</button></div>
    </aside>
    {menu && <button className="backdrop" aria-label="Fermer le menu" onClick={() => setMenu(false)}/>}
    <main className="main"><header><button className="mobile" aria-label="Ouvrir le menu" onClick={() => setMenu(true)}>☰</button><span>Espace Talent <span className="muted">/ {page === 'access' ? 'Habilitations' : 'Mes viviers'}</span></span><span className="badge">MVP · Identité & accès</span></header>
      <div className="content">{error && <div className="alert" role="alert">{error}<button className="text-button" onClick={() => setError('')}>Fermer</button></div>}
      {page === 'access' && admin ? <Access session={session} failed={failed}/> : <Pools failed={failed}/>}
      </div>
    </main>
  </div>;
}
function Pools({ failed }: { failed: (e: unknown) => void }) {
  const [pools, setPools] = useState<Pool[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<Pool | null>(null);
  useEffect(() => { let active = true; api<Pool[]>('/pools').then(p => { if (active) setPools(p); }).catch(e => { if (active) failed(e); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, []);
  async function open(id: string) { try { setSelected(await api<Pool>(`/pools/${id}`)); } catch (e) { setSelected(null); failed(e); } }
  return <><p className="eyebrow">Votre espace de travail</p><h1>Mes viviers</h1><p className="muted">Choisissez un vivier pour consulter votre périmètre de recrutement.</p>
    {loading ? <p role="status">Chargement des viviers…</p> : pools.length ? <div className="grid">{pools.map(pool => <article className="card" key={pool.id}><div className="pool-icon">▤</div><h2>{pool.name}</h2><p className="muted">Accès attribué par votre organisation.</p><button className="button" onClick={() => void open(pool.id)}>Ouvrir le vivier</button></article>)}</div> : <section className="card empty"><h2>Aucun vivier attribué</h2><p className="muted">Demandez à votre administrateur de vous affecter à un vivier. Un rôle d’administration ne donne pas automatiquement accès aux CV.</p></section>}
    {selected && <section className="card" aria-live="polite"><div className="row"><h2>{selected.name}</h2><button className="text-button" onClick={() => setSelected(null)}>Fermer</button></div><span className="badge green">Accès vérifié</span><p className="muted">Votre habilitation est active. L’import et la gestion des candidatures seront disponibles dans le prochain incrément.</p></section>}
    <section className="milestone"><strong>Première étape du MVP</strong><p>La connexion et les habilitations sont opérationnelles dans cet incrément. Les parcours d’import, de recherche et de contact restent à développer.</p></section></>;
}
function Access({ session, failed }: { session: Session; failed: (e: unknown) => void }) {
  const [users, setUsers] = useState<Account[]>([]); const [pools, setPools] = useState<Pool[]>([]);
  const [userId, setUserId] = useState(''); const [poolId, setPoolId] = useState('');
  const [members, setMembers] = useState<Membership[]>([]); const [role, setRole] = useState('');
  const [pending, setPending] = useState(true); const [saving, setSaving] = useState(false); const [notice, setNotice] = useState('');
  useEffect(() => { let active = true; Promise.all([api<Account[]>('/admin/users'), api<Pool[]>('/admin/pools')]).then(([u,p]) => { if (active) { setUsers(u); setPools(p); setUserId(u[0]?.id || ''); setPoolId(p[0]?.id || ''); } }).catch(e => { if (active) failed(e); }).finally(() => { if (active) setPending(false); }); return () => { active = false; }; }, []);
  useEffect(() => { if (!userId) return; let active = true; setPending(true); setNotice(''); api<Membership[]>(`/admin/users/${userId}/memberships`).then(m => { if (active) setMembers(m); }).catch(e => { if (active) failed(e); }).finally(() => { if (active) setPending(false); }); return () => { active = false; }; }, [userId]);
  useEffect(() => { setRole(members.find(m => m.poolId === poolId)?.role || ''); setNotice(''); }, [members, poolId]);
  async function submit(e: React.FormEvent) {
    e.preventDefault(); setSaving(true); setNotice('');
    try {
      await api<void>('/admin/memberships', { method: 'PUT', body: JSON.stringify({ userId, poolId, roles: role ? [role] : [] }) }, session.csrfToken);
      setMembers(await api<Membership[]>(`/admin/users/${userId}/memberships`)); setNotice('Habilitation enregistrée. Elle s’applique dès la prochaine requête du membre.');
    } catch (e) { failed(e); } finally { setSaving(false); }
  }
  return <><p className="eyebrow">Administration</p><h1>Les bons accès, au bon périmètre.</h1><p className="muted">Attribuez ou retirez les accès aux viviers. Chaque modification est tracée.</p>
    <div className="columns"><section className="card"><h2>Modifier une habilitation</h2><form onSubmit={e => void submit(e)}><label htmlFor="user">Membre de l’équipe</label><select id="user" value={userId} onChange={e => setUserId(e.target.value)} disabled={saving}>{users.map(u => <option key={u.id} value={u.id}>{u.displayName}{!u.enabled ? ' · désactivé' : ''}</option>)}</select><label htmlFor="pool">Vivier</label><select id="pool" value={poolId} onChange={e => setPoolId(e.target.value)} disabled={saving}>{pools.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select><label htmlFor="role">Rôle dans ce vivier</label><select id="role" value={role} onChange={e => setRole(e.target.value)} disabled={pending || saving}><option value="">Aucun accès</option><option value="RECRUITER">Recruteur</option><option value="RECRUITMENT_LEAD">Responsable recrutement</option></select><p className="fine">« Aucun accès » retire toutes les habilitations de ce membre dans le vivier sélectionné.</p><button className="button primary" type="submit" disabled={pending || saving || !userId || !poolId}>{saving ? 'Enregistrement…' : 'Enregistrer l’habilitation'}</button></form>{notice && <p className="success" role="status">{notice}</p>}</section><aside className="card"><h2>Accès actuels</h2>{pending ? <p role="status">Chargement…</p> : members.length ? <ul className="grants">{members.map(m => <li key={m.poolId+m.role}><strong>{pools.find(p => p.id === m.poolId)?.name || 'Vivier'}</strong><span>{m.role === 'RECRUITER' ? 'Recruteur' : 'Responsable recrutement'}</span></li>)}</ul> : <p className="muted">Aucun vivier attribué à ce membre.</p>}<hr/><p className="fine">Les rôles globaux d’administration et de conformité ne sont pas attribués depuis ce formulaire. Ils ne donnent pas de droit implicite de consultation des CV.</p><p className="fine">Cette première version affiche au maximum 100 membres et 100 viviers.</p></aside></div></>;
}
createRoot(document.getElementById('root')!).render(<React.StrictMode><App/></React.StrictMode>);
