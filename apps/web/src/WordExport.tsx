import { useState } from 'react';
import { api, ApiError, message, type Session } from './api';
type Options = { canExport: boolean; publishedRevisionId: string | null; templateVersion: string };
type Receipt = { id: string; revisionId: string; templateVersion: string; sha256: string; createdAt: string };
export function WordExport({ candidateId, session, failed }: { candidateId: string; session: Session; failed: (e: unknown) => void }) {
  const [options, setOptions] = useState<Options | null>(null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  async function load() {
    setBusy(true); setError(''); setReceipt(null); setNotice('');
    try { setOptions(await api(`/candidates/${candidateId}/export-options`)); }
    catch (e) { setOptions(null); setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  async function generate() {
    if (!options?.publishedRevisionId) return;
    setBusy(true); setError(''); setNotice('');
    try { setReceipt(await api(`/candidates/${candidateId}/exports`, { method: 'POST', body: JSON.stringify({ revisionId: options.publishedRevisionId }) }, session.csrfToken)); }
    catch (e) { setReceipt(null); setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  async function download() {
    if (!receipt) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch(`/api/v1/exports/${receipt.id}/download`, { credentials: 'same-origin' });
      if (!response.ok) { const detail = await response.json().catch(() => ({})); throw new ApiError(response.status, detail.code || 'REQUEST_FAILED'); }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a'); link.href = url; link.download = `cv-${receipt.id}.docx`; document.body.appendChild(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 20000); setNotice('Fichier transmis au navigateur. Vérifiez vos téléchargements.');
    } catch (e) { if (e instanceof ApiError && e.status === 403) { setReceipt(null); setOptions(o => o ? { ...o, canExport: false } : null); } setError(message(e)); failed(e); } finally { setBusy(false); }
  }
  return <section aria-label="Export Word"><h4>Exporter le CV Word</h4>
    <p>L’export reprend les propositions de la révision publiée sélectionnée. Les champs de la fiche non validés dans cette revue et les coordonnées ne sont pas inclus.</p>
    <button className="button" disabled={busy} onClick={() => void load()}>Préparer un export Word</button>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {options && <>
      {!options.canExport && <p>Permission d’export requise : demandez son attribution pour ce vivier à votre administrateur.</p>}
      {!options.publishedRevisionId && <p>Aucune révision publiée. Terminez la revue humaine avant de générer le CV.</p>}
      <p className="fine">Modèle : {options.templateVersion}<br/>Révision sélectionnée : {options.publishedRevisionId ?? 'Aucune'}</p>
      <button className="button primary" disabled={busy || !options.canExport || !options.publishedRevisionId} onClick={() => void generate()}>Générer le CV Word</button>
    </>}
    {receipt && <><p role="status">Export Word prêt. Les rubriques absentes sont indiquées comme non renseignées.</p>
      <button className="button" disabled={busy} onClick={() => void download()}>Télécharger le CV Word</button>
      <details><summary>Traçabilité de l’export</summary><p className="fine">Révision : {receipt.revisionId}<br/>Modèle : {receipt.templateVersion}<br/>Empreinte : {receipt.sha256}</p></details></>}
  </section>;
}
