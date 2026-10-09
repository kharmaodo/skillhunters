import { useEffect, useRef, useState } from 'react';
import { api, ApiError, message, type Pool, type Session } from './api';

type Policy = { id: string; label: string; purpose: string; basisCode: string; retentionDays: number };
type Item = { id: string; fileName: string; byteSize: number; sha256: string; state: string; errorCode: string | null; attempts: number; retryAfter: string | null };
type Batch = { id: string; poolId: string; canUpload: boolean; completedFiles: number; totalFiles: number; items: Item[] };
type Summary = { id: string; createdAt: string; completedFiles: number; totalFiles: number };
type Page = { items: Summary[]; nextCursor: string | null };
type Selected = { file: File; fileName: string; byteSize: number; sha256: string };
const terminal = (item: Item) => ['QUARANTINED', 'REJECTED_FORMAT', 'FAILED'].includes(item.state);
const labels: Record<string, string> = { WAITING_UPLOAD: 'À envoyer', UPLOADING: 'Réception en cours', QUARANTINED: 'En quarantaine', REJECTED_FORMAT: 'Format refusé', RETRYABLE_FAILURE: 'À reprendre', FAILED: 'Échec définitif' };
const reasons: Record<string, string> = {
  EMPTY_FILE: 'Fichier vide', FILE_TOO_LARGE: 'Fichier supérieur à 15 Mio', UNSUPPORTED_FORMAT: 'Extension non acceptée',
  SIGNATURE_MISMATCH: 'Le contenu ne correspond pas au format attendu', MIME_MISMATCH: 'Type déclaré incohérent',
  INVALID_TEXT: 'Texte UTF-8 invalide', UNSAFE_ARCHIVE: 'Archive non autorisée', ARCHIVE_LIMIT: 'Limite de décompression dépassée',
  QUARANTINE_UNAVAILABLE: 'Stockage temporairement indisponible', RECEPTION_UNAVAILABLE: 'Réception indisponible',
  UPLOAD_INTERRUPTED: 'Transfert interrompu', ATTEMPTS_EXHAUSTED: 'Limite de cinq tentatives atteinte'
};

export function BatchImports({ pool, session, failed, back }: { pool: Pool; session: Session; failed: (e: unknown) => void; back: () => void }) {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [policyId, setPolicyId] = useState('');
  const [source, setSource] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [batch, setBatch] = useState<Batch | null>(null);
  const [history, setHistory] = useState<Summary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const key = useRef(crypto.randomUUID());
  const picker = useRef<HTMLInputElement>(null);
  const alive = useRef(true);
  const stop = useRef(false);
  const policy = policies.find(p => p.id === policyId);

  useEffect(() => {
    alive.current = true;
    Promise.all([api<Policy[]>(`/pools/${pool.id}/import-policies`), api<Page>(`/pools/${pool.id}/import-batches`)])
      .then(([p, h]) => { if (alive.current) { setPolicies(p); setPolicyId(p[0]?.id || ''); setHistory(h.items); setCursor(h.nextCursor); } })
      .catch(e => { if (alive.current) { setError(message(e)); failed(e); } })
      .finally(() => { if (alive.current) setLoading(false); });
    return () => { alive.current = false; stop.current = true; };
  }, [pool.id]);

  function reset() { setBatch(null); setFiles([]); setError(''); setNotice(''); key.current = crypto.randomUUID(); if (picker.current) picker.current.value = ''; }
  async function open(id: string) {
    setBusy(true); setError(''); setNotice(''); setFiles([]); if (picker.current) picker.current.value = '';
    try { setBatch(await api<Batch>(`/import-batches/${id}`)); }
    catch (e) { failed(e); setError(message(e)); }
    finally { setBusy(false); }
  }
  async function refreshHistory(more = false) {
    const next = await api<Page>(`/pools/${pool.id}/import-batches${more && cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`);
    if (alive.current) { setHistory(previous => more ? [...previous, ...next.items.filter(i => !previous.some(p => p.id === i.id))] : next.items); setCursor(next.nextCursor); }
  }
  async function fingerprints(): Promise<Selected[]> {
    const selected: Selected[] = [];
    for (const file of files) {
      if (stop.current) break;
      // Oversize files are rejected from the manifest without allocating their content.
      const hash = file.size > 15 * 1024 * 1024 ? '0'.repeat(64) : Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()))).map(b => b.toString(16).padStart(2, '0')).join('');
      selected.push({ file, fileName: file.name, byteSize: file.size, sha256: hash });
    }
    return selected;
  }
  function mergeProgress(next: Batch) {
    if (!alive.current) return;
    setBatch(previous => {
      if (!previous || previous.id !== next.id) return next;
      const items = next.items.map(item => {
        const old = previous.items.find(i => i.id === item.id)!;
        return terminal(old) || old.attempts > item.attempts ? old : item;
      });
      return { ...next, items, completedFiles: items.filter(terminal).length };
    });
  }
  async function send() {
    if (busy || !files.length || (!batch && (!policy || !source.trim()))) return;
    if (files.length > 100 || files.reduce((sum, file) => sum + file.size, 0) > 300 * 1024 * 1024) { setError('Choisissez de 1 à 100 fichiers, pour un total de 300 Mio maximum.'); return; }
    stop.current = false; setBusy(true); setError(''); setNotice('Calcul des empreintes…');
    try {
      const selected = await fingerprints();
      if (stop.current || !alive.current) return;
      let current = batch;
      if (!current) {
        current = await api<Batch>('/import-batches', { method: 'POST', headers: { 'Idempotency-Key': key.current }, body: JSON.stringify({
          poolId: pool.id, basis: { source: source.trim(), purpose: policy!.purpose, basisCode: policy!.basisCode, retentionPolicyId: policy!.id },
          files: selected.map(({ file: _file, ...spec }) => spec)
        }) }, session.csrfToken);
        if (!alive.current) return;
        setBatch(current);
      }
      const id = current.id;
      const pending = current.items.filter(item => !terminal(item));
      const queue = pending.flatMap(item => {
        const match = selected.find(file => file.fileName === item.fileName && file.byteSize === item.byteSize && file.sha256 === item.sha256);
        return match ? [{ item, file: match.file }] : [];
      });
      const missing = pending.length - queue.length;
      setNotice(queue.length ? 'Envoi des fichiers. Vous pouvez suspendre après les transferts en cours.' : 'Aucun fichier à envoyer dans cette sélection.');
      async function lane() {
        while (queue.length && !stop.current) {
          const next = queue.shift()!;
          const form = new FormData(); form.append('file', next.file);
          try { mergeProgress(await api<Batch>(`/import-batches/${id}/items/${next.item.id}/content`, { method: 'PUT', body: form }, session.csrfToken)); }
          catch (e) {
            stop.current = true;
            if (alive.current) { setError(message(e)); if (e instanceof ApiError && [401, 403, 404].includes(e.status)) failed(e); }
          }
        }
      }
      await Promise.all([lane(), lane()]);
      if (!alive.current) return;
      setBatch(await api<Batch>(`/import-batches/${id}`)); await refreshHistory();
      setNotice(stop.current ? 'Envoi suspendu. Les résultats reçus sont conservés ; vous pouvez reprendre ce lot.' : missing ? `${missing} fichier(s) encore attendu(s). Resélectionnez les originaux correspondants pour reprendre.` : 'Envoi terminé. Consultez le résultat de chaque fichier ci-dessous ; les fichiers reçus restent en quarantaine.');
    } catch (e) { if (alive.current) { setError(message(e)); failed(e); } }
    finally { if (alive.current) setBusy(false); }
  }

  return <section className="import-section">
    <div className="row"><h3>Importer un lot</h3><button className="text-button" disabled={busy} onClick={back}>Retour au dépôt individuel</button></div>
    <p className="muted">De 1 à 100 fichiers, 300 Mio par lot et 15 Mio par fichier. Chaque fichier garde son résultat, même si un autre est refusé. Aucun fichier ne sort de quarantaine.</p>
    {loading ? <p role="status">Chargement…</p> : <>
      {!batch && <div className="import-form">
        <label htmlFor="batch-policy">Cadre du lot</label><select id="batch-policy" value={policyId} disabled={busy} onChange={e => { setPolicyId(e.target.value); key.current = crypto.randomUUID(); }}>{policies.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}</select>
        {!policies.length && <p className="alert">Aucune politique d’import configurée.</p>}
        <label htmlFor="batch-source">Provenance des documents</label><input id="batch-source" value={source} maxLength={200} disabled={busy} onChange={e => { setSource(e.target.value); key.current = crypto.randomUUID(); }}/>
      </div>}
      {batch && <><p className="fine">Lot {batch.id}</p><p role="status">{batch.completedFiles} / {batch.totalFiles} fichiers terminés (reçus ou refusés).</p><progress max={batch.totalFiles} value={batch.completedFiles} aria-label="Progression du lot"/>
        {!batch.canUpload && <p className="alert">La reprise est réservée à l’auteur du lot, habilité à ce vivier, avant son échéance.</p>}
        <p className="fine">Pour reprendre, resélectionnez les fichiers locaux d’origine. Leurs empreintes seront vérifiées ; les fichiers déjà reçus ne seront pas renvoyés.</p></>}
      {(!batch || (batch.canUpload && batch.completedFiles < batch.totalFiles)) && <div className="import-form">
        <label htmlFor="batch-files">Documents du lot</label><input ref={picker} id="batch-files" type="file" multiple accept=".md,.pdf,.docx,.doc" disabled={busy} onChange={e => { setFiles(Array.from(e.target.files || [])); if (!batch) key.current = crypto.randomUUID(); setError(''); }}/>
        <p className="fine">{files.length} fichier(s) sélectionné(s).</p>
        <button className="button primary" disabled={busy || !files.length || (!batch && (!policy || !source.trim()))} onClick={() => void send()}>{batch ? 'Reprendre les fichiers sélectionnés' : 'Envoyer le lot'}</button>
      </div>}
      {busy && <button className="button" onClick={() => { stop.current = true; setNotice('Suspension demandée : les transferts en cours vont se terminer.'); }}>Suspendre l’envoi</button>}
      {batch && !busy && <button className="text-button" onClick={reset}>Nouveau lot</button>}
      {error && <p className="alert" role="alert">{error}</p>}{notice && <p className="success" role="status">{notice}</p>}
      {batch && <ul className="import-list">{batch.items.map(item => <li key={item.id}><strong>{item.fileName}</strong><span className="badge">{labels[item.state]}</span><span className="fine">Tentatives : {item.attempts} / 5{item.errorCode ? ` · ${reasons[item.errorCode] || item.errorCode}` : ''}</span>{item.retryAfter && item.state === 'UPLOADING' && <span className="fine">Si l’envoi a été interrompu, reprise possible après {new Date(item.retryAfter).toLocaleTimeString('fr-FR')}.</span>}</li>)}</ul>}
      <div className="row"><h3>Lots du vivier</h3><button className="text-button" disabled={busy} onClick={() => void refreshHistory().catch(failed)}>Actualiser les lots</button></div>
      {!history.length && <p className="muted">Aucun lot enregistré.</p>}
      <ul className="import-list">{history.map(item => <li key={item.id}><strong>{new Date(item.createdAt).toLocaleString('fr-FR')}</strong><span>{item.completedFiles} / {item.totalFiles} fichiers terminés</span><button className="button" disabled={busy} onClick={() => void open(item.id)}>Ouvrir le lot</button></li>)}</ul>
      {cursor && <button className="button" disabled={busy} onClick={() => void refreshHistory(true).catch(failed)}>Lots précédents</button>}
    </>}
  </section>;
}
