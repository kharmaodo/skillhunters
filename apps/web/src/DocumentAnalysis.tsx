import { useState } from 'react';
import { api, message, type Session } from './api';

type Analysis = { state: string; attempts: number; code: string | null; parserVersion: string | null; avVersion: string | null;
  result: { blocks: { source: string; text: string }[]; ocrPages: number[] } | null };
const labels: Record<string, string> = { PENDING: 'Analyse en attente', RUNNING: 'Analyse en cours', RETRY: 'Service indisponible · reprise prévue',
  EXTRACTED: 'Texte extrait · non validé', OCR_REQUIRED: 'OCR requis', REJECTED: 'Document bloqué', FAILED: 'Analyse interrompue', CANCELLED: 'Analyse annulée' };
const reasons: Record<string, string> = { ANTIVIRUS_UNAVAILABLE: 'Antivirus indisponible ou signatures trop anciennes. Aucune extraction autorisée.',
  ANTIVIRUS_BLOCKED: 'Le contrôle antivirus a bloqué ce document.', EXTERNAL_REFERENCE: 'Le document contient une référence externe. Déposez une version sans liens externes.',
  ACTIVE_CONTENT: 'Le document contient du contenu actif non autorisé.', ENCRYPTED_DOCUMENT: 'Déposez une version non chiffrée.',
  LEGACY_FIELDS_UNSUPPORTED: 'Ce document Word ancien contient des champs non pris en charge. Déposez une version DOCX ou PDF sans champs actifs.',
  SOURCE_UNAVAILABLE: 'Le document ou l’habilitation à l’origine du traitement n’est plus disponible.' };
export function DocumentAnalysis({ id, session, failed }: { id: string; session: Session; failed: (error: unknown) => void }) {
  const [result, setResult] = useState<Analysis | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function load(retry = false) {
    setBusy(true); setError('');
    try { setResult(await api<Analysis>(`/imports/${id}/analysis${retry ? '/retry' : ''}`, retry ? { method: 'POST' } : {}, session.csrfToken)); }
    catch (e) { setResult(null); setError(message(e)); failed(e); }
    finally { setBusy(false); }
  }
  return <div>
    <button className="text-button" disabled={busy} onClick={() => void load()}>{busy ? 'Chargement…' : result ? 'Actualiser l’analyse' : 'Consulter l’analyse'}</button>
    {error && <p role="alert">{error}</p>}
    {result && <div aria-live="polite">
      <p><strong>{labels[result.state] || result.state}</strong> · {result.attempts} tentative(s)</p>
      {result.code && <p>{reasons[result.code] || 'Le traitement n’a pas abouti. Le document reste bloqué.'}</p>}
      {result.avVersion && <p className="fine">Antivirus : {result.avVersion}</p>}
      {result.parserVersion && <p className="fine">Extraction : {result.parserVersion}</p>}
      {['RETRY', 'FAILED', 'CANCELLED'].includes(result.state) && <button className="button" disabled={busy} onClick={() => void load(true)}>Relancer l’analyse</button>}
      {!!result.result?.ocrPages.length && <p>Pages sans texte à traiter par OCR : {result.result.ocrPages.join(', ')}. L’OCR sera disponible dans SH-08.</p>}
      {result.result && <details><summary>Voir le texte brut et ses sources</summary>
        <p className="muted">Texte non validé : aucune compétence ni durée n’est encore déduite. La revue structurée sera livrée séparément.</p>
        {result.result.blocks.map((block, index) => <div key={index}><p className="fine">{block.source}</p><p className="extracted-text">{block.text}</p></div>)}
        {!result.result.blocks.length && <p>Aucun texte exploitable extrait.</p>}
      </details>}
    </div>}
  </div>;
}
