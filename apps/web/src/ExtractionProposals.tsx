import { useState } from 'react';
import { api, message, type Session } from './api';

type Evidence = { blockIndex: number; source: string; start: number; end: number; quote: string };
type Claim = { field: string; item: number; raw: string | null; normalized: string | null;
  status: 'UNKNOWN' | 'PROPOSED' | 'AMBIGUOUS'; precision: string; evidence: Evidence[]; warnings: string[] };
type Revision = { id: string; importId: string; documentSha256: string; analysisSha256: string; createdAt: string;
  proposal: { rulesVersion: string; schemaVersion: string; claims: Claim[]; warnings: string[] } };
const fields: Record<string, string> = { FULL_NAME: 'Nom', TITLE: 'Titre professionnel', AVAILABILITY: 'Disponibilité',
  SKILL: 'Compétence déclarée', DECLARED_LEVEL: 'Niveau déclaré', EXPERIENCE: 'Expérience déclarée',
  EDUCATION: 'Formation déclarée', LANGUAGE: 'Langue déclarée', CERTIFICATION_DECLARATION: 'Certification ou examen mentionné' };
const warnings: Record<string, string> = {
  GROUP_ATTRIBUTION_AMBIGUOUS: 'Groupe de technologies : attribution individuelle à confirmer.',
  LEVEL_OUT_OF_RANGE: 'Note hors de l’échelle 1 à 3 : aucune normalisation retenue.',
  CONFLICTING_DECLARATIONS: 'Plusieurs déclarations se contredisent : vérifier les sources.',
  PERIOD_AND_KIND_UNRESOLVED: 'Dates et type d’expérience à préciser. Aucune durée calculée.',
  ATTAINMENT_UNVERIFIED: 'Cette mention ne prouve pas l’obtention d’une certification.',
  AVAILABILITY_DATE_UNRESOLVED: 'Date et actualité de la disponibilité à confirmer.',
  LONG_LINE_SKIPPED: 'Une ligne trop longue n’a pas été interprétée.',
  CLAIM_LIMIT: 'Limite de propositions atteinte : extraction incomplète.'
};
export function ExtractionProposals({ id, session, failed }: { id: string; session: Session; failed: (error: unknown) => void }) {
  const [revision, setRevision] = useState<Revision | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function prepare() {
    setBusy(true); setError('');
    try { setRevision(await api<Revision>(`/imports/${id}/extraction`, { method: 'POST' }, session.csrfToken)); }
    catch (e) { setRevision(null); setError(message(e)); failed(e); }
    finally { setBusy(false); }
  }
  return <section className="extraction-proposals">
    <button className="button" disabled={busy} onClick={() => void prepare()}>{busy ? 'Préparation…' : revision ? 'Actualiser les propositions' : 'Préparer les propositions'}</button>
    {error && <p role="alert">{error}</p>}
    {revision && <div aria-live="polite">
      <h4>Propositions structurées · à vérifier</h4>
      <p>Lecture des rubriques et libellés reconnus : la couverture peut être incomplète. Aucune proposition n’est validée ni indexée.</p>
      <p>Les notes de 1 à 3 sont des niveaux déclarés, jamais des années d’expérience. Les durées restent inconnues.</p>
      {revision.proposal.warnings.filter(w => w !== 'RULE_BASED_LIMITED_COVERAGE').map(w => <p key={w}>{warnings[w] || w}</p>)}
      <ul className="proposal-list">
        {revision.proposal.claims.map((claim, i) => <li key={i}>
          <p><strong>{fields[claim.field] || claim.field}{claim.item > 0 ? ` (${claim.item})` : ''}</strong> : {claim.status === 'UNKNOWN' ? 'Inconnue / non documentée' : claim.normalized ?? claim.raw}
            {claim.status === 'AMBIGUOUS' && ' · ambiguïté à résoudre'}</p>
          {claim.raw !== null && claim.raw !== claim.normalized && <p>Valeur source : {claim.raw}</p>}
          {claim.warnings.map(w => <p key={w}>{warnings[w] || w}</p>)}
          {!!claim.evidence.length && <details><summary>Preuves de la proposition {i + 1}</summary>
            {claim.evidence.map((e, j) => <div key={j}><p className="fine">{e.source} · caractères {e.start}–{e.end}</p>
              <blockquote className="extracted-text">{e.quote}</blockquote></div>)}
          </details>}
        </li>)}
      </ul>
      <details><summary>Traçabilité des propositions</summary>
        <p className="fine">Révision : {revision.id} · {new Date(revision.createdAt).toLocaleString('fr-FR')}</p>
        <p className="fine">Règles : {revision.proposal.rulesVersion} · schéma : {revision.proposal.schemaVersion} · sans modèle génératif</p>
        <p className="fine">Document SHA-256 : {revision.documentSha256}</p>
        <p className="fine">Analyse SHA-256 : {revision.analysisSha256}</p>
      </details>
    </div>}
  </section>;
}
