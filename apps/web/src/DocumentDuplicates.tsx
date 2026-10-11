import { useState } from 'react';
import { api, message } from './api';

type Match = { id: string; createdAt: string; items: { fileName: string }[] };
type Page = { items: Match[]; nextCursor: string | null };

export function DocumentDuplicates({ id, failed }: { id: string; failed: (error: unknown) => void }) {
  const [result, setResult] = useState<Page | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function load(more = false) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      const cursor = more ? result?.nextCursor : null;
      const page = await api<Page>(`/imports/${id}/duplicates${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`);
      setResult(previous => ({ ...page, items: more && previous ? [...previous.items, ...page.items.filter(item => !previous.items.some(old => old.id === item.id))] : page.items }));
    } catch (e) { setResult(null); setError(message(e)); failed(e); }
    finally { setBusy(false); }
  }
  return <div>
    <button className="text-button" disabled={busy} onClick={() => void load()}>{busy ? 'Vérification…' : 'Vérifier les fichiers identiques'}</button>
    {error && <p role="alert">{error}</p>}
    {result && <div aria-live="polite">
      <p>{result.items.length ? 'Fichiers de même empreinte et taille dans ce vivier :' : 'Aucun autre fichier identique conservé dans ce vivier.'}</p>
      {!!result.items.length && <>
        <p className="fine">Signal documentaire uniquement. Aucune fusion de candidats ; les dépôts restent distincts et en quarantaine.</p>
        <ul>{result.items.map(item => <li key={item.id}>
          <strong>{item.items[0].fileName}</strong>
          <span className="fine">{new Date(item.createdAt).toLocaleString('fr-FR')} · Référence : {item.id}</span>
        </li>)}</ul>
      </>}
      {result.nextCursor && <button className="text-button" disabled={busy} onClick={() => void load(true)}>Voir les correspondances suivantes</button>}
    </div>}
  </div>;
}
