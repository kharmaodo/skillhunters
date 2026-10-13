import { useState } from 'react';
import { api, ApiError, message, type Session } from './api';
export type Candidate = { id: string; poolId: string; importId: string; revisionId: string; version: number;
  profile: { displayName: string | null; professionalTitle: string | null;
    availability: { status: 'UNKNOWN' | 'DECLARED'; availableFrom: string | null; declaredOn: string | null; declaration: string | null } };
  contact: { email: string | null; phone: string | null } | null; contactsVisible: boolean; expiresAt: string };
type Edit = { version: number; actorId: string; fields: string[]; provenance: string; occurredAt: string };
type Page<T> = { items: T[]; nextCursor: string | null };
export function CandidateProfile({ initial, session, failed }: { initial: Candidate; session: Session; failed: (e: unknown) => void }) {
  const [saved, setSaved] = useState(initial);
  const [draft, setDraft] = useState(initial);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [conflict, setConflict] = useState(false);
  const [history, setHistory] = useState<Page<Edit> | null>(null);
  const prefix = `candidate-${initial.id}`;
  function profile(field: 'displayName' | 'professionalTitle', value: string) {
    setDraft({ ...draft, profile: { ...draft.profile, [field]: value || null } });
  }
  function availability(field: 'availableFrom' | 'declaredOn' | 'declaration', value: string) {
    setDraft({ ...draft, profile: { ...draft.profile, availability: { ...draft.profile.availability, [field]: value || null } } });
  }
  async function save(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const candidate = await api<Candidate>(`/candidates/${saved.id}`, { method: 'PATCH', headers: { 'If-Match': `"${saved.version}"` },
        body: JSON.stringify({ ...draft.profile, ...(saved.contactsVisible ? { contact: draft.contact } : {}), reason }) }, session.csrfToken);
      setSaved(candidate); setDraft(candidate); setReason(''); setConflict(false); setHistory(null); setNotice('Fiche enregistrée.');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'CANDIDATE_CHANGED') {
        setConflict(true); setError('La fiche a été modifiée par un autre utilisateur. Votre saisie est conservée. Rechargez la fiche avant de reprendre vos corrections.');
      } else { setError(message(e)); failed(e); }
    } finally { setBusy(false); }
  }
  async function reload() {
    setBusy(true); setError('');
    try { const c = await api<Candidate>(`/candidates/${saved.id}`); setSaved(c); setDraft(c); setReason(''); setConflict(false); setHistory(null); setNotice('Dernière version chargée ; la saisie précédente a été remplacée.'); }
    catch (e) { setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  async function audit(cursor = '0') {
    setBusy(true); setError('');
    try { setHistory(await api<Page<Edit>>(`/candidates/${saved.id}/history?cursor=${cursor}`)); }
    catch (e) { setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  return <section className="candidate-profile" aria-label="Fiche candidat">
    <h4>Fiche candidat · version {saved.version}</h4>
    <p>Les propositions restent à vérifier. Enregistrer la fiche ne valide pas les compétences et ne lance aucune indexation.</p>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {conflict && <button className="button" disabled={busy} onClick={() => void reload()}>Recharger et remplacer ma saisie</button>}
    <form onSubmit={e => void save(e)}>
      <fieldset disabled={busy}><legend>Identité professionnelle</legend>
        <label htmlFor={`${prefix}-name`}>Nom (inconnu si vide)</label><input id={`${prefix}-name`} maxLength={200} value={draft.profile.displayName || ''} onChange={e => profile('displayName', e.target.value)}/>
        <label htmlFor={`${prefix}-title`}>Titre professionnel (inconnu si vide)</label><input id={`${prefix}-title`} maxLength={200} value={draft.profile.professionalTitle || ''} onChange={e => profile('professionalTitle', e.target.value)}/>
        <label htmlFor={`${prefix}-availability`}>Disponibilité</label>
        <select id={`${prefix}-availability`} value={draft.profile.availability.status} onChange={e => setDraft({ ...draft, profile: { ...draft.profile,
          availability: { status: e.target.value as 'UNKNOWN' | 'DECLARED', availableFrom: null, declaredOn: null, declaration: null } } })}>
          <option value="UNKNOWN">Inconnue / non documentée</option><option value="DECLARED">Déclarée et datée</option>
        </select>
        {draft.profile.availability.status === 'DECLARED' && <>
          <label htmlFor={`${prefix}-from`}>Disponible à partir du</label><input id={`${prefix}-from`} type="date" required value={draft.profile.availability.availableFrom || ''} onChange={e => availability('availableFrom', e.target.value)}/>
          <label htmlFor={`${prefix}-declared`}>Déclaration recueillie le</label><input id={`${prefix}-declared`} type="date" required value={draft.profile.availability.declaredOn || ''} onChange={e => availability('declaredOn', e.target.value)}/>
          <label htmlFor={`${prefix}-declaration`}>Source de la déclaration</label><input id={`${prefix}-declaration`} maxLength={500} required value={draft.profile.availability.declaration || ''} onChange={e => availability('declaration', e.target.value)}/>
          <p className="fine">Une déclaration passée ne garantit pas la disponibilité actuelle.</p>
        </>}
      </fieldset>
      {saved.contactsVisible && draft.contact ? <fieldset disabled={busy}><legend>Coordonnées · accès responsable recrutement</legend>
        <label htmlFor={`${prefix}-email`}>E-mail</label><input id={`${prefix}-email`} type="email" maxLength={254} value={draft.contact.email || ''} onChange={e => setDraft({ ...draft, contact: { ...draft.contact!, email: e.target.value || null } })}/>
        <label htmlFor={`${prefix}-phone`}>Téléphone</label><input id={`${prefix}-phone`} type="tel" maxLength={40} value={draft.contact.phone || ''} onChange={e => setDraft({ ...draft, contact: { ...draft.contact!, phone: e.target.value || null } })}/>
      </fieldset> : <p>Coordonnées masquées : habilitation de responsable recrutement requise dans ce vivier.</p>}
      <label htmlFor={`${prefix}-reason`}>Provenance / motif de la modification</label><input id={`${prefix}-reason`} required maxLength={500} value={reason} disabled={busy} onChange={e => setReason(e.target.value)}/>
      <p className="fine">Indiquez la source de votre correction, sans recopier de coordonnées dans le motif.</p>
      <button className="button primary" disabled={busy || conflict} type="submit">{busy ? 'En cours…' : 'Enregistrer la fiche'}</button>
    </form>
    <details><summary>Origine et conservation</summary><p className="fine">Révision source : {saved.revisionId}</p><p>Conservation jusqu’au {new Date(saved.expiresAt).toLocaleDateString('fr-FR')}.</p></details>
    <button className="button" disabled={busy} onClick={() => void audit()}>Historique des modifications</button>
    {history && <><ul className="proposal-list">{history.items.map(edit => <li key={edit.version}>
      <p>Version {edit.version} · {new Date(edit.occurredAt).toLocaleString('fr-FR')}</p><p>Champs : {edit.fields.join(', ') || 'Modification de coordonnées masquée'}</p>
      <p>{edit.provenance === 'CONTACT_REDACTED' ? 'Provenance masquée avec les coordonnées' : edit.provenance}</p><p className="fine">Auteur : {edit.actorId}</p>
    </li>)}</ul>{history.nextCursor && <button className="button" disabled={busy} onClick={() => void audit(history.nextCursor!)}>Modifications précédentes</button>}</>}
  </section>;
}
export function Candidates({ poolId, session, failed }: { poolId: string; session: Session; failed: (e: unknown) => void }) {
  const [page, setPage] = useState<Page<Candidate> | null>(null);
  const [selected, setSelected] = useState<Candidate | null>(null);
  const [busy, setBusy] = useState(false);
  async function load(cursor = '0') {
    setBusy(true);
    try { setPage(await api<Page<Candidate>>(`/candidates?poolId=${poolId}&cursor=${cursor}`)); setSelected(null); }
    catch (e) { setPage(null); setSelected(null); failed(e); } finally { setBusy(false); }
  }
  async function open(id: string) {
    setBusy(true);
    try { setSelected(await api<Candidate>(`/candidates/${id}`)); }
    catch (e) { setSelected(null); failed(e); } finally { setBusy(false); }
  }
  return <section><h3>Fiches candidats</h3><button className="button" disabled={busy} onClick={() => void load()}>Actualiser les fiches</button>
    {page && <><ul className="proposal-list">{page.items.map(c => <li key={c.id}><button className="text-button" disabled={busy} onClick={() => void open(c.id)}>{c.profile.displayName || 'Nom inconnu'} · {c.profile.professionalTitle || 'Titre inconnu'}</button></li>)}</ul>
      {!page.items.length && <p>Aucune fiche. Créez une fiche depuis les propositions structurées d’un document analysé.</p>}
      {page.nextCursor && <button className="button" disabled={busy} onClick={() => void load(page.nextCursor!)}>Fiches suivantes</button>}</>}
    {selected && <CandidateProfile key={`${selected.id}-${selected.version}`} initial={selected} session={session} failed={failed}/>}
  </section>;
}
