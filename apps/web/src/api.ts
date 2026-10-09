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
    if (error.status === 403) return 'Cette action n’est pas autorisée. Vos habilitations ont peut-être changé.';
    if (error.status === 404) return 'Cet élément n’est pas disponible dans votre périmètre.';
    if (error.status === 413) return 'Le fichier dépasse la limite de 15 Mio.';
    if (error.status === 415) return 'Ce document est vide, son format est invalide ou son archive dépasse les limites de sécurité. Utilisez un PDF, DOCX, DOC ou Markdown valide.';
    if (error.status === 409) return 'La clé de reprise correspond à un autre dépôt ou ce dépôt a expiré. Actualisez puis recommencez.';
    if (error.status === 429) return 'La capacité ou le quota d’import est atteint. Réessayez plus tard ou contactez votre administrateur.';
    if (error.code === 'QUARANTINE_UNAVAILABLE') return 'Le stockage est indisponible. Conservez ce formulaire et réessayez : le dépôt sera repris sans doublon.';
    if (error.status === 400) return 'Vérifiez les informations saisies.';
  }
  return 'Le service est momentanément indisponible. Réessayez dans quelques instants.';
}
