import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, message, type Pool, type Session } from './api';

type Policy = { id: string; label: string; purpose: string; basisCode: string; retentionDays: number };
type Receipt = { id: string; createdAt: string; expiresAt: string; items: { fileName: string; state: string; byteSize: number }[] };
type Page = { items: Receipt[]; nextCursor: string | null };

export function Imports({ pool, session, failed }: { pool: Pool; session: Session; failed: (error: unknown) => void }) {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [policyId, setPolicyId] = useState('');
  const [source, setSource] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [rows, setRows] = useState<Receipt[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [paging, setPaging] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const key = useRef(crypto.randomUUID());
  const input = useRef<HTMLInputElement>(null);
  const policy = policies.find(p => p.id === policyId);

  useEffect(() => {
    let active = true;
    Promise.all([api<Policy[]>(`/pools/${pool.id}/import-policies`), api<Page>(`/pools/${pool.id}/imports`)])
      .then(([available, history]) => {
        if (!active) return;
        setPolicies(available); setPolicyId(available[0]?.id || ''); setRows(history.items); setCursor(history.nextCursor);
      }).catch(e => { if (active) { setError(message(e)); failed(e); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [pool.id]);

  function changed() { key.current = crypto.randomUUID(); setNotice(''); setError(''); }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!file || !policy || busy) return;
    if (file.size === 0 || file.size > 15 * 1024 * 1024) { setError('Choisissez un fichier non vide de 15 Mio maximum.'); return; }
    setBusy(true); setError(''); setNotice('');
    const body = new FormData();
    body.append('poolId', pool.id);
    body.append('basis', new Blob([JSON.stringify({ source: source.trim(), purpose: policy.purpose, basisCode: policy.basisCode, retentionPolicyId: policy.id })], { type: 'application/json' }));
    body.append('files', file);
    try {
      const receipt = await api<Receipt>('/imports', { method: 'POST', body, headers: { 'Idempotency-Key': key.current } }, session.csrfToken);
      setRows(previous => [receipt, ...previous.filter(item => item.id !== receipt.id)]);
      setFile(null); if (input.current) input.current.value = '';
      key.current = crypto.randomUUID();
      setNotice(`Dépôt reçu en quarantaine. Référence : ${receipt.id}. L’analyse antivirus n’est pas encore disponible.`);
    } catch (e) { setError(message(e)); failed(e); }
    finally { setBusy(false); }
  }
  async function loadMore() {
    setPaging(true);
    try {
      const next = await api<Page>(`/pools/${pool.id}/imports${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`);
      setRows(previous => cursor ? [...previous, ...next.items.filter(item => !previous.some(p => p.id === item.id))] : next.items);
      setCursor(next.nextCursor);
    } catch (e) { setError(message(e)); failed(e); }
    finally { setPaging(false); }
  }

  return <div className="import-section">
    <h3>Déposer un CV</h3>
    <p className="muted">Un fichier à la fois, de 15 Mio maximum. PDF, DOCX, DOC ou Markdown. Le document reste privé et bloqué en quarantaine jusqu’aux contrôles antivirus et documentaires.</p>
    {loading ? <p role="status">Chargement des possibilités d’import…</p> : !policies.length ? <p className="alert">Aucune politique d’import n’est configurée. Contactez votre administrateur.</p> :
      <form onSubmit={e => void submit(e)} className="import-form">
        <label htmlFor="import-policy">Cadre du dépôt</label>
        <select id="import-policy" disabled={busy} value={policyId} onChange={e => { setPolicyId(e.target.value); changed(); }}>
          {policies.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
        </select>
        {policy && <p className="fine">Finalité : {policy.purpose}. Échéance prévue : {policy.retentionDays} jours. Le dépôt ne vaut pas consentement du candidat.</p>}
        <label htmlFor="import-source">Provenance du document</label>
        <input id="import-source" required maxLength={200} value={source} disabled={busy} placeholder="Ex. : candidature reçue pour le poste backend" onChange={e => { setSource(e.target.value); changed(); }}/>
        <label htmlFor="import-file">Document à déposer</label>
        <input id="import-file" ref={input} type="file" accept=".pdf,.docx,.doc,.md" required disabled={busy} onChange={e => { setFile(e.target.files?.[0] || null); changed(); }}/>
        <button className="button primary" disabled={busy || !file || !source.trim()} type="submit">{busy ? 'Envoi et mise en quarantaine…' : 'Déposer en quarantaine'}</button>
      </form>}
    {error && <p className="alert" role="alert">{error}</p>}
    {notice && <p className="success" role="status">{notice}</p>}
    <div className="row"><h3>Dépôts du vivier</h3><button className="text-button" disabled={paging || busy} onClick={() => { setCursor(null); void api<Page>(`/pools/${pool.id}/imports`).then(p => { setRows(p.items); setCursor(p.nextCursor); }).catch(failed); }}>Actualiser</button></div>
    {!loading && !rows.length && <p className="muted">Aucun document déposé.</p>}
    <ul className="import-list">{rows.map(receipt => <li key={receipt.id}>
      <strong>{receipt.items[0].fileName}</strong>
      <span className="badge">{receipt.items[0].state === 'QUARANTINED' ? 'En quarantaine · analyse en attente' : 'Réception incomplète · à reprendre'}</span>
      <span className="fine">{new Date(receipt.createdAt).toLocaleString('fr-FR')} · {Math.ceil(receipt.items[0].byteSize / 1024)} Kio</span>
      <span className="fine">Référence : {receipt.id}</span>
    </li>)}</ul>
    {cursor && <button className="button" disabled={paging} onClick={() => void loadMore()}>{paging ? 'Chargement…' : 'Voir les dépôts précédents'}</button>}
  </div>;
}
