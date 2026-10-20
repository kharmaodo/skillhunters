export type Pool = { id: string; name: string };
export type Membership = { userId: string; poolId: string; role: string };
export type Session = { userId: string; displayName: string; roles: string[]; poolIds: string[]; memberships: Membership[]; csrfToken: string };
export type Account = { id: string; displayName: string; enabled: boolean };
export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}
export async function api<T>(path: string, options: RequestInit = {}, csrfToken?: string): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
      ...(csrfToken ? { 'X-CSRF-Token': csrfToken } : {}), ...options.headers }
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ code: 'REQUEST_FAILED' }));
    throw new ApiError(response.status, error.code || 'REQUEST_FAILED');
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}
export function message(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Votre session a expiré. Reconnectez-vous pour continuer.';
    if (error.code === 'EXPORT_PERMISSION_REQUIRED') return 'La permission d’export est absente ou a été retirée dans ce vivier.';
    if (error.code === 'REVISION_NOT_PUBLISHED') return 'Publiez cette révision avant de générer le CV.';
    if (error.code === 'EXPORT_MEMBERSHIP_REQUIRED') return 'Attribuez d’abord un rôle dans ce vivier, puis autorisez l’export.';
    if (error.status === 403) return 'Cette action n’est pas autorisée. Vos habilitations ont peut-être changé.';
    if (error.status === 404) return 'Cet élément n’est pas disponible dans votre périmètre.';
    if (error.status === 413) return 'Le fichier dépasse la limite de 15 Mio.';
    if (error.status === 415) return 'Ce document est vide, son format est invalide ou son archive dépasse les limites de sécurité. Utilisez un PDF, DOCX, DOC ou Markdown valide.';
    if (error.code === 'IMPORT_NOT_COMPARABLE') return 'Ce dépôt est expiré ou sa réception est incomplète. La comparaison est indisponible.';
    if (error.code === 'FILE_FINGERPRINT_MISMATCH') return 'Le fichier ne correspond pas au document d’origine. Resélectionnez le même nom et le même contenu.';
    if (error.code === 'IMPORT_IN_PROGRESS') return 'Une tentative est encore active. Actualisez le lot puis reprenez après l’heure indiquée.';
    if (error.code === 'BATCH_RESUME_REQUIRED') return 'Ce document appartient à un lot. Ouvrez le lot pour reprendre son transfert.';
    if (error.code === 'INVALID_SKILL_PERIOD') return 'Vérifiez la confirmation, la preuve et les dates : la période doit rester dans celle de la mission, avec la même précision et le même type.';
    if (error.code === 'PERIOD_EVIDENCE_REQUIRED') return 'Recopiez un extrait exact de la preuve de mission affichée.';
    if (error.code === 'PUBLICATION_CHANGED') return 'La publication a changé. Rechargez les périodes et vérifiez les nouveaux rattachements.';
    if (error.code === 'INVALID_REVIEW') return 'Vérifiez les valeurs, les niveaux 1 à 3, les périodes et la justification de chaque modification.';
    if (error.code === 'REVIEW_INCOMPLETE') return 'Examinez toutes les propositions avant de publier.';
    if (error.code === 'REVIEW_CONTRADICTION') return 'Résolvez les propositions contradictoires et validez la compétence associée à chaque niveau.';
    if (error.code === 'EXTRACTION_CHANGED') return 'Les propositions ont changé. Actualisez-les avant de créer la fiche.';
    if (error.code === 'INVALID_CANDIDATE') return 'Vérifiez la fiche : une disponibilité déclarée exige deux dates et sa provenance ; la date de déclaration ne peut pas être future.';
    if (error.code === 'CANDIDATE_CHANGED') return 'La fiche a changé. Rechargez sa dernière version avant de reprendre vos corrections.';
    if (error.code === 'VERSION_REQUIRED') return 'Rechargez la fiche pour obtenir sa version actuelle.';
    if (error.code === 'SKILL_ALIAS_COLLISION') return 'Cette appellation est déjà utilisée. Choisissez un autre alias ou examinez une fusion explicite.';
    if (error.code === 'DICTIONARY_CHANGED') return 'Le dictionnaire a changé. Actualisez-le avant de reprendre votre modification.';
    if (error.code === 'DICTIONARY_LIMIT') return 'La limite de compétences ou d’alias est atteinte.';
    if (error.status === 409) return 'La clé de reprise correspond à un autre dépôt ou ce dépôt a expiré. Actualisez puis recommencez.';
    if (error.status === 429) return 'La capacité ou le quota d’import est atteint. Réessayez plus tard ou contactez votre administrateur.';
    if (error.code === 'QUARANTINE_UNAVAILABLE') return 'Le stockage est indisponible. Conservez ce formulaire et réessayez : le dépôt sera repris sans doublon.';
    if (error.status === 400) return 'Vérifiez les informations saisies.';
  }
  return 'Le service est momentanément indisponible. Réessayez dans quelques instants.';
}
