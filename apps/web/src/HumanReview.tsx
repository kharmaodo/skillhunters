import { useState } from 'react';
import { api, ApiError, message, type Session } from './api';
type Claim = { field: string; item: number; raw: string | null; normalized: string | null; status: string; warnings: string[];
  evidence: { source: string; start: number; end: number; quote: string }[] };
type Period = { start: string | null; end: string | null; precision: 'UNKNOWN' | 'DAY' | 'MONTH' | 'YEAR'; kind: string };
type Item = { sourceIndex: number; value: string | null; decision: 'PENDING' | 'VALIDATED' | 'REJECTED' | 'UNKNOWN'; precision: string; period: Period | null; reason: string | null };
type Revision = { id: string; version: number; status: string; actorId: string; createdAt: string; justification: string; items: Item[];
  changes: { sourceIndex: number; before: Item; after: Item }[] };
type State = { version: number; source: { id: string; documentSha256: string; proposal: { claims: Claim[] } }; latest: Revision | null; publishedRevisionId: string | null };
type Summary = { id: string; version: number; status: string; actorId: string; createdAt: string };
const labels: Record<string, string> = { FULL_NAME: 'Nom', TITLE: 'Titre', AVAILABILITY: 'Mention de disponibilité', SKILL: 'Compétence', DECLARED_LEVEL: 'Niveau déclaré', EXPERIENCE: 'Expérience', EDUCATION: 'Formation', LANGUAGE: 'Langue', CERTIFICATION_DECLARATION: 'Certification ou examen mentionné' };
const decisions: Record<string, string> = { PENDING: 'À examiner', VALIDATED: 'Validée', REJECTED: 'Écartée', UNKNOWN: 'Inconnue' };
function initial(claim: Claim, i: number): Item { return { sourceIndex: i, value: claim.normalized ?? claim.raw,
  decision: claim.status === 'UNKNOWN' ? 'UNKNOWN' : 'PENDING', precision: claim.status === 'UNKNOWN' ? 'UNKNOWN' : claim.field === 'DECLARED_LEVEL' ? 'DECLARED_LEVEL' : 'TEXT', period: null, reason: null }; }
export function HumanReview({ candidateId, session, failed, versionChanged }: { candidateId: string; session: Session; failed: (e: unknown) => void; versionChanged: (v: number) => void }) {
  const [state, setState] = useState<State | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [justification, setJustification] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [conflict, setConflict] = useState(false);
  const [history, setHistory] = useState<{ items: Summary[]; nextCursor: string | null } | null>(null);
  const [inspected, setInspected] = useState<Revision | null>(null);
  async function load() {
    setBusy(true); setError(''); setNotice('');
    try {
      const s = await api<State>(`/candidates/${candidateId}/review`); setState(s); setItems(s.latest?.items ?? s.source.proposal.claims.map(initial));
      setJustification(''); setConflict(false); versionChanged(s.version);
    } catch (e) { setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  function change(i: number, patch: Partial<Item>) { setItems(items.map((item, n) => n === i ? { ...item, ...patch } : item)); }
  function decision(i: number, value: Item['decision']) {
    const claim = state!.source.proposal.claims[i];
    const empty = value === 'UNKNOWN' || value === 'REJECTED';
    change(i, { decision: value, value: empty ? null : items[i].value ?? claim.normalized ?? claim.raw,
      precision: empty ? 'UNKNOWN' : claim.field === 'DECLARED_LEVEL' ? 'DECLARED_LEVEL' : 'TEXT', period: empty ? null : items[i].period });
  }
  function period(i: number, patch: Partial<Period>) {
    change(i, { period: { start: null, end: null, precision: 'UNKNOWN', kind: 'UNKNOWN', ...items[i].period, ...patch } });
  }
  async function save(publish: boolean) {
    if (!state) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const r = await api<Revision>(`/candidates/${candidateId}/${publish ? 'validate' : 'revisions'}`, { method: 'POST', headers: { 'If-Match': `"${state.version}"` }, body: JSON.stringify({ items, justification }) }, session.csrfToken);
      setState({ ...state, version: r.version, latest: r, publishedRevisionId: publish ? r.id : state.publishedRevisionId });
      setItems(r.items); setJustification(''); setHistory(null); setInspected(null); versionChanged(r.version);
      setNotice(publish ? 'Révision publiée. L’index professionnel a été mis à jour.' : 'Brouillon enregistré. La publication précédente est conservée.');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'CANDIDATE_CHANGED') { setConflict(true); setError('La fiche ou la revue a changé. Votre saisie est conservée ; rechargez la revue pour repartir de la dernière version.'); }
      else { setError(message(e)); failed(e); }
    } finally { setBusy(false); }
  }
  async function revisions(cursor = '0') {
    setBusy(true);
    try { setHistory(await api(`/candidates/${candidateId}/revisions?cursor=${cursor}`)); }
    catch (e) { setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  async function inspect(id: string) {
    setBusy(true);
    try { setInspected(await api(`/candidates/${candidateId}/revisions/${id}`)); }
    catch (e) { setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  return <section className="human-review" aria-label="Revue humaine">
    <h4>Vérifier et publier les propositions</h4>
    <p>Examinez chaque proposition : validez, corrigez, écartez ou conservez une inconnue. La valeur source et ses preuves restent consultables.</p>
    <button className="button" disabled={busy} onClick={() => void load()}>{state ? 'Recharger la revue et remplacer ma saisie' : 'Ouvrir la revue humaine'}</button>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {state && <>
      <p>Version {state.version} · {state.latest?.status === 'PUBLISHED' ? 'Dernière révision publiée' : 'Revue en cours'} · {items.filter(i => i.decision === 'PENDING').length} proposition(s) à examiner.</p>
      <p className="fine">Cette revue porte sur les propositions du document. L’identité, les coordonnées et la disponibilité déclarée de la fiche se modifient dans le formulaire précédent. Une mention de certification ne prouve pas son obtention.</p>
      <form onSubmit={e => { e.preventDefault(); void save(true); }}>
        <fieldset disabled={busy}><legend>Décisions et corrections</legend>
          {state.source.proposal.claims.map((claim, i) => {
            const item = items[i]; const prefix = `review-${candidateId}-${i}`;
            return <details className="review-item" key={i}>
              <summary>{labels[claim.field]} {claim.item || ''} · {decisions[item.decision]} · {item.value || 'Non renseigné'}</summary>
              <p>Valeur source : {claim.raw ?? 'Inconnue'}</p>
              {claim.status === 'AMBIGUOUS' && <p>Ambiguïté signalée : choisissez une interprétation et justifiez-la.</p>}
              {claim.evidence.map((proof, n) => <blockquote key={n}><p>{proof.quote}</p><p className="fine">{proof.source} · caractères {proof.start}–{proof.end}</p></blockquote>)}
              <label htmlFor={`${prefix}-decision`}>Décision pour la proposition {i + 1}</label>
              <select id={`${prefix}-decision`} value={item.decision} onChange={e => decision(i, e.target.value as Item['decision'])}>
                {Object.entries(decisions).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
              </select>
              {(item.decision === 'VALIDATED' || item.decision === 'PENDING') && <>
                <label htmlFor={`${prefix}-value`}>Valeur retenue pour la proposition {i + 1}</label>
                <textarea id={`${prefix}-value`} rows={2} maxLength={claim.field === 'SKILL' ? 200 : 2048} value={item.value ?? ''} onChange={e => change(i, { value: e.target.value || null })}/>
                {claim.field === 'DECLARED_LEVEL' && <p>Niveau déclaré : 1, 1.5, 2, 2.5 ou 3. Ce n’est pas une durée.</p>}
                {['EXPERIENCE', 'EDUCATION'].includes(claim.field) && <>
                  <label htmlFor={`${prefix}-precision`}>Précision de la période {i + 1}</label>
                  <select id={`${prefix}-precision`} value={item.period?.precision ?? 'UNKNOWN'} onChange={e => period(i, { precision: e.target.value as Period['precision'], start: null, end: null })}>
                    <option value="UNKNOWN">Période inconnue</option><option value="DAY">Jour (AAAA-MM-JJ)</option><option value="MONTH">Mois (AAAA-MM)</option><option value="YEAR">Année (AAAA)</option>
                  </select>
                  {item.period && item.period.precision !== 'UNKNOWN' && <>
                    <label htmlFor={`${prefix}-start`}>Début de période {i + 1}</label><input id={`${prefix}-start`} maxLength={10} placeholder={item.period.precision === 'DAY' ? '2025-01-01' : item.period.precision === 'MONTH' ? '2025-01' : '2025'} value={item.period.start ?? ''} onChange={e => period(i, { start: e.target.value || null })}/>
                    <label htmlFor={`${prefix}-end`}>Fin de période {i + 1}</label><input id={`${prefix}-end`} maxLength={10} value={item.period.end ?? ''} onChange={e => period(i, { end: e.target.value || null })}/>
                  </>}
                  <label htmlFor={`${prefix}-kind`}>Type de période {i + 1}</label><select id={`${prefix}-kind`} value={item.period?.kind ?? 'UNKNOWN'} onChange={e => period(i, { kind: e.target.value })}>
                    <option value="UNKNOWN">Type inconnu</option>{claim.field === 'EXPERIENCE' && <><option value="EMPLOYMENT">Emploi</option><option value="INTERNSHIP">Stage</option><option value="FREELANCE">Freelance</option></>}<option value="ACADEMIC">Projet académique / études</option>
                  </select><p className="fine">Aucune durée ni ancienneté technique n’est calculée.</p>
                </>}
              </>}
              <label htmlFor={`${prefix}-reason`}>Justification de la proposition {i + 1}</label>
              <input id={`${prefix}-reason`} maxLength={500} value={item.reason ?? ''} onChange={e => change(i, { reason: e.target.value || null })}/>
              <p className="fine">Justification obligatoire pour chaque décision ou correction modifiée. N’ajoutez que des informations professionnelles nécessaires.</p>
            </details>;
          })}
          <label htmlFor={`review-${candidateId}-justification`}>Justification de cette révision</label>
          <input id={`review-${candidateId}-justification`} maxLength={500} value={justification} onChange={e => setJustification(e.target.value)}/>
          <div className="review-actions"><button className="button" type="button" disabled={conflict || !justification.trim()} onClick={() => void save(false)}>Enregistrer le brouillon</button>
            <button className="button primary" type="submit" disabled={conflict || !justification.trim() || items.some(i => i.decision === 'PENDING')}>Valider et publier la révision</button></div>
        </fieldset>
      </form>
      <p className="fine">Seuls les libellés de compétences validées, leurs niveaux déclarés et les périodes d’expérience renseignées alimentent l’index structuré. Les noms, coordonnées, langues et textes narratifs en sont exclus. La recherche et les embeddings seront livrés séparément.</p>
      <p className="fine">Révision source : {state.source.id} · Publication active : {state.publishedRevisionId ?? 'Aucune'}</p>
      <button className="button" disabled={busy} onClick={() => void revisions()}>Historique des révisions</button>
      {history && <><ul className="proposal-list">{history.items.map(r => <li key={r.id}><button className="text-button" disabled={busy} onClick={() => void inspect(r.id)}>Version {r.version} · {r.status === 'PUBLISHED' ? 'Publiée' : 'Brouillon'} · {new Date(r.createdAt).toLocaleString('fr-FR')}</button></li>)}</ul>
        {history.nextCursor && <button className="button" disabled={busy} onClick={() => void revisions(history.nextCursor!)}>Révisions précédentes</button>}</>}
      {inspected && <section aria-label="Détail de la révision"><h5>Révision {inspected.version}</h5><p>{inspected.justification}</p><p className="fine">Auteur : {inspected.actorId} · {new Date(inspected.createdAt).toLocaleString('fr-FR')}</p>
        <ul className="proposal-list">{inspected.changes.map(c => <li key={c.sourceIndex}><p>Proposition {c.sourceIndex + 1} : {decisions[c.before.decision]} → {decisions[c.after.decision]}</p>
          <p>Avant : {c.before.value ?? 'Inconnue'} {c.before.period && `· ${c.before.period.start ?? '?'} – ${c.before.period.end ?? '?'} (${c.before.period.precision}, ${c.before.period.kind})`}</p>
          <p>Après : {c.after.value ?? 'Inconnue'} {c.after.period && `· ${c.after.period.start ?? '?'} – ${c.after.period.end ?? '?'} (${c.after.period.precision}, ${c.after.period.kind})`}</p><p>{c.after.reason}</p></li>)}</ul>
        {!inspected.changes.length && <p>Aucune valeur modifiée dans cette révision ; publication ou sauvegarde des décisions existantes.</p>}
      </section>}
    </>}
  </section>;
}
