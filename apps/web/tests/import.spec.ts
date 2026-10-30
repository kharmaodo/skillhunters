import { test, expect } from '@playwright/test';

for (const width of [1280, 320]) {
  test(`real login and quarantined import at ${width}px`, async ({ page }) => {
    test.setTimeout(90000);
    const filename = `synthetic-${width}-${Date.now()}.md`;
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.getByRole('link', { name: 'Se connecter avec le compte professionnel' }).click();
    await page.locator('#username').fill('alice');
    await page.locator('#password').fill(process.env.DEMO_PASSWORD!);
    await page.locator('#kc-login').click();
    await expect(page.getByRole('heading', { name: 'Mes viviers', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await page.getByLabel('Provenance du document').fill('Recette navigateur synthétique');
    await page.getByLabel('Document à déposer').setInputFiles({ name: filename, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java 2,5\nExpérience : Projet synthétique 2024/25\n`) });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    const copyName = `copy-${filename}`;
    await page.getByLabel('Provenance du document').fill('Copie synthétique pour contrôle des doublons');
    await page.getByLabel('Document à déposer').setInputFiles({ name: copyName, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java 2,5\nExpérience : Projet synthétique 2024/25\n`) });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    const copy = page.locator('.import-list > li').filter({ has: page.getByText(copyName, { exact: true }) });
    await copy.getByRole('button', { name: 'Vérifier les fichiers identiques' }).click();
    await expect(copy.getByText(filename, { exact: true })).toBeVisible();
    await expect(copy.getByText('Signal documentaire uniquement.', { exact: false })).toBeVisible();
    await expect(copy.locator('ul > li')).toHaveCount(1);
    await copy.getByRole('button', { name: 'Consulter l’analyse', exact: true }).click();
    await expect(async () => {
      await copy.getByRole('button', { name: 'Actualiser l’analyse', exact: true }).click();
      await expect(copy.getByText('Texte extrait · non validé', { exact: true })).toBeVisible();
    }).toPass({ timeout: 90000, intervals: [2000] });
    await copy.getByText('Voir le texte brut et ses sources', { exact: true }).click();
    await expect(copy.locator('.extracted-text')).toContainText('Compétence : Java');
    await copy.getByRole('button', { name: 'Préparer les propositions', exact: true }).click();
    const proposals = copy.locator('.extraction-proposals');
    await expect(proposals.getByRole('heading', { name: 'Propositions structurées · à vérifier' })).toBeVisible();
    await expect(proposals.getByText('Niveau déclaré (1)', { exact: true }).locator('..')).toContainText('2.5');
    await expect(proposals.getByText('Disponibilité', { exact: true }).locator('..')).toContainText('Inconnue');
    await proposals.getByText('Preuves de la proposition 2', { exact: true }).click();
    await expect(proposals.locator('details[open] blockquote')).toContainText('Compétence : Java 2,5');
    const firstRevision = await proposals.getByText('Traçabilité des propositions').locator('..').textContent();
    await proposals.getByRole('button', { name: 'Actualiser les propositions', exact: true }).click();
    await expect(proposals.getByRole('button', { name: 'Actualiser les propositions', exact: true })).toBeEnabled();
    expect(await proposals.getByText('Traçabilité des propositions').locator('..').textContent()).toBe(firstRevision);
    await proposals.getByRole('button', { name: 'Créer ou ouvrir la fiche candidat' }).click();
    const candidate = proposals.getByRole('region', { name: 'Fiche candidat' });
    await expect(candidate.getByLabel('Disponibilité', { exact: true })).toHaveValue('UNKNOWN');
    await expect(candidate.getByText('Coordonnées masquées', { exact: false })).toBeVisible();
    await candidate.getByLabel('Titre professionnel (inconnu si vide)').fill(`Développeur synthétique ${width}`);
    await candidate.getByLabel('Provenance / motif de la modification').fill('Entretien synthétique');
    await candidate.getByRole('button', { name: 'Enregistrer la fiche', exact: true }).click();
    await expect(candidate.getByText('Fiche enregistrée.', { exact: true })).toBeVisible();
    await candidate.getByRole('button', { name: 'Historique des modifications' }).click();
    await expect(candidate.getByText('HUMAN: Entretien synthétique', { exact: true })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await page.getByRole('button', { name: 'Actualiser les fiches' }).click();
    await page.getByRole('button', { name: `Nom inconnu · Développeur synthétique ${width}`, exact: true }).last().click();
    await expect(page.getByLabel('Titre professionnel (inconnu si vide)')).toHaveValue(`Développeur synthétique ${width}`);
    await expect(page.getByLabel('Disponibilité', { exact: true })).toHaveValue('UNKNOWN');
    const review = page.getByRole('region', { name: 'Revue humaine', exact: true });
    await review.getByRole('button', { name: 'Ouvrir la revue humaine' }).click();
    await expect(review.locator('.review-item')).toHaveCount(9);
    for (const i of [0, 1, 2]) {
      await review.locator('.review-item').nth(i).locator('summary').click();
      await review.getByLabel(`Décision pour la proposition ${i + 1}`, { exact: true }).selectOption('VALIDATED');
      await review.getByLabel(`Justification de la proposition ${i + 1}`, { exact: true }).fill('Vérification du document synthétique');
    }
    await review.getByLabel('Précision de la période 3', { exact: true }).selectOption('MONTH');
    await review.getByLabel('Début de période 3', { exact: true }).fill('2024-01');
    await review.getByLabel('Fin de période 3', { exact: true }).fill('2025-06');
    await review.getByLabel('Type de période 3', { exact: true }).selectOption('ACADEMIC');
    await review.getByLabel('Justification de cette révision', { exact: true }).fill('Date ambiguë précisée avec sa source');
    await review.getByRole('button', { name: 'Enregistrer le brouillon', exact: true }).click();
    await expect(review.getByText('Brouillon enregistré.', { exact: false })).toBeVisible();
    await review.getByRole('button', { name: 'Recharger la revue et remplacer ma saisie' }).click();
    await expect(review.getByLabel('Début de période 3', { exact: true })).toHaveValue('2024-01');
    await review.getByLabel('Justification de cette révision', { exact: true }).fill('Publication après relecture humaine');
    await review.getByRole('button', { name: 'Valider et publier la révision', exact: true }).click();
    await expect(review.getByText('Révision publiée.', { exact: false })).toBeVisible();
    await expect(review.getByText('Valeur source : Projet synthétique 2024/25', { exact: true })).toBeVisible();
    await review.getByRole('button', { name: 'Historique des révisions' }).click();
    await review.getByRole('button', { name: /Version 3 · Brouillon/ }).click();
    await expect(review.getByRole('region', { name: 'Détail de la révision' })).toContainText('2024-01');
    const periods = page.locator('.skill-periods');
    await periods.getByRole('button', { name: 'Charger les périodes et remplacer ma saisie' }).click();
    await expect(periods.getByText('Durée inconnue', { exact: true }).first()).toBeVisible();
    await periods.getByLabel('Compétence publiée', { exact: true }).selectOption('0');
    await periods.getByLabel('Mission publiée', { exact: true }).selectOption('2');
    await periods.getByLabel('Extrait exact de la preuve de mission', { exact: true }).fill('Projet synthétique 2024/25');
    await expect(periods.getByRole('button', { name: 'Ajouter le rattachement' })).toBeDisabled();
    await periods.getByRole('checkbox').check();
    await periods.getByRole('button', { name: 'Ajouter le rattachement' }).click();
    await periods.getByLabel('Justification des rattachements', { exact: true }).fill('Exposition académique vérifiée');
    await periods.getByRole('button', { name: 'Enregistrer les rattachements' }).click();
    await expect(periods.getByRole('status')).toContainText('Rattachements enregistrés');
    await periods.getByRole('button', { name: 'Historique des rattachements' }).click();
    await expect(periods.locator('summary').filter({ hasText: 'Version' })).toHaveCount(1);
    const search = page.getByRole('region', { name: 'Recherche structurée', exact: true });
    await search.getByLabel('Compétence 1', { exact: true }).fill('Java');
    await search.getByRole('button', { name: 'Ajouter une compétence', exact: true }).click();
    await search.getByLabel('Compétence 2', { exact: true }).fill('Kafka');
    await search.getByLabel('Exigence 2', { exact: true }).selectOption('DESIRED');
    await search.getByRole('checkbox', { name: /^Mode strict/ }).check();
    await search.getByRole('button', { name: 'Rechercher dans ce vivier', exact: true }).click();
    const searchHit = search.locator('article').filter({ has: page.getByRole('heading', { name: `Nom inconnu · Développeur synthétique ${width}`, exact: true }) });
    await expect(searchHit).toHaveCount(1);
    await expect(searchHit.getByText('Java · Obligatoire : Documenté', { exact: true })).toBeVisible();
    await expect(searchHit.getByText('Kafka · Souhaitée : Non documenté', { exact: true })).toBeVisible();
    await searchHit.getByText('Preuves du critère 1', { exact: true }).click();
    await expect(searchHit.locator('blockquote').first()).toContainText('Java');
    await search.getByLabel('Exposition minimale documentée 1 (mois, facultatif)', { exact: true }).fill('12');
    await search.getByRole('button', { name: 'Rechercher dans ce vivier', exact: true }).click();
    await expect(searchHit).toHaveCount(0);
    await search.getByRole('checkbox', { name: /^Mode strict/ }).uncheck();
    await search.getByRole('button', { name: 'Rechercher dans ce vivier', exact: true }).click();
    await expect(searchHit.getByText('Java · Obligatoire : Inconnu', { exact: true })).toBeVisible();
    const word = page.getByRole('region', { name: 'Export Word', exact: true });
    await word.getByRole('button', { name: 'Préparer un export Word' }).click();
    await expect(word.getByRole('button', { name: 'Générer le CV Word' })).toBeDisabled();
    const session = await (await page.request.get('/api/v1/session')).json();
    const adminContext = await page.context().browser()!.newContext();
    const adminPage = await adminContext.newPage();
    await adminPage.goto('http://localhost:8080/');
    await adminPage.getByRole('link', { name: 'Se connecter avec le compte professionnel' }).click();
    await adminPage.locator('#username').fill('admin-demo');
    await adminPage.locator('#password').fill(process.env.DEMO_PASSWORD!);
    await adminPage.locator('#kc-login').click();
    await adminPage.getByRole('button', { name: /Habilitations/ }).click();
    await adminPage.getByLabel('Membre de l’équipe', { exact: true }).selectOption(session.userId);
    await adminPage.getByLabel('Vivier', { exact: true }).selectOption(session.poolIds[0]);
    await expect(adminPage.getByRole('checkbox', { name: 'Autoriser les exports dans ce vivier' })).toBeEnabled();
    await adminPage.getByRole('checkbox', { name: 'Autoriser les exports dans ce vivier' }).check();
    await adminPage.getByRole('button', { name: 'Enregistrer la permission d’export' }).click();
    await expect(adminPage.getByText('Permission d’export enregistrée.', { exact: false })).toBeVisible();
    await word.getByRole('button', { name: 'Préparer un export Word' }).click();
    await word.getByRole('button', { name: 'Générer le CV Word' }).click();
    await expect(word.getByText('Export Word prêt.', { exact: false })).toBeVisible();
    const downloadPromise = page.waitForEvent('download');
    await word.getByRole('button', { name: 'Télécharger le CV Word' }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/^cv-[a-f0-9-]+\.docx$/);
    await download.saveAs(`test-results/export-${width}.docx`);
    expect(await download.failure()).toBeNull();
    await adminPage.getByRole('checkbox', { name: 'Autoriser les exports dans ce vivier' }).uncheck();
    await adminPage.getByRole('button', { name: 'Enregistrer la permission d’export' }).click();
    await expect(adminPage.getByText('Permission d’export enregistrée.', { exact: false })).toBeVisible();
    await word.getByRole('button', { name: 'Télécharger le CV Word' }).click();
    await expect(word.getByRole('alert')).toContainText('permission d’export');
    // SH-15 uses the real versioned dictionary and confirmation endpoints.
    const adminSession = await (await adminPage.request.get('/api/v1/session')).json();
    for (const name of ['Java', 'Spring Boot', 'Kafka']) {
      const response = await adminPage.request.get('/api/v1/taxonomy');
      const dictionary = await response.json();
      if (!dictionary.skills.some((skill: {name:string}) => skill.name === name)) {
        const created = await adminPage.request.post('/api/v1/taxonomy/skills', { headers: {'X-CSRF-TOKEN': adminSession.csrfToken, 'If-Match': response.headers()['etag']}, data: {name, category:'Synthetic', aliases:[], reason:'Recette SH-15 synthétique'} });
        expect(created.ok()).toBeTruthy();
      }
    }
    let retrievals = 0;
    page.on('request', request => { if (request.url().endsWith('/search/structured') || request.url().endsWith('/confirm')) retrievals++; });
    await search.getByLabel('Décrivez les compétences recherchées', { exact: true }).fill('cinq ans Java et Spring Boot ; Kafka souhaité récent');
    await search.getByRole('button', { name: 'Proposer les critères', exact: true }).click();
    await expect(search.getByRole('heading', { name: 'Proposition à relire avant recherche' })).toBeVisible();
    expect(retrievals).toBe(0);
    await expect(search.getByText(/Portée à préciser pour Java et Spring Boot/)).toBeVisible();
    await expect(search.getByLabel('Exposition minimale documentée 1 (mois, facultatif)', { exact: true })).toHaveValue('');
    const confirmSearch = search.getByRole('button', { name: 'Confirmer les critères et rechercher', exact: true });
    const approval = search.getByRole('checkbox', { name: /^J’ai relu les critères/ });
    await expect(confirmSearch).toBeDisabled();
    await approval.check();
    await search.getByLabel('Exigence 2', { exact: true }).selectOption('DESIRED');
    await expect(approval).not.toBeChecked();
    await expect(confirmSearch).toBeDisabled();
    await search.getByLabel('Exposition minimale documentée 1 (mois, facultatif)', { exact: true }).fill('12');
    await approval.check();
    await confirmSearch.click();
    await expect(searchHit.getByText('Java · Obligatoire : Inconnu', { exact: true })).toBeVisible();
    expect(retrievals).toBe(1);
    await expect(search.getByText(/Interprétation .* confirmée après relecture/)).toBeVisible();
    await search.getByRole('checkbox', {name:'Élargir aux compétences proches (recherche hybride locale)'}).check();
    await search.getByRole('button', {name:'Rechercher dans ce vivier',exact:true}).click();
    await expect(search.getByText(/Recherche hybride · modèle minilm/)).toBeVisible();
    await expect(searchHit.getByText('Java · Obligatoire : Inconnu', {exact:true})).toBeVisible();
    await searchHit.getByText('Pourquoi ce profil est retrouvé', {exact:true}).click();
    await expect(searchHit.getByText(/Preuve structurée exacte/)).toBeVisible();
    const scoring = search.getByRole('region', { name: 'Classement documenté', exact: true });
    const frozenResponse = page.waitForResponse(r => r.url().endsWith('/search/scored') && r.request().method() === 'POST');
    await scoring.getByRole('button', { name: 'Calculer et figer le classement', exact: true }).click();
    const frozen = await (await frozenResponse).json();
    await expect(scoring.getByRole('status')).toContainText('Classement figé');
    const ranked = scoring.locator('article').filter({ hasText: `Développeur synthétique ${width}` });
    await expect(ranked).toHaveCount(1);
    await expect(ranked).toContainText('Score documenté');
    await expect(ranked).toContainText('Borne haute');
    await expect(ranked).toContainText('Obligations à vérifier');
    await ranked.getByText(/^Détail des contributions et preuves du rang/).click();
    await expect(ranked.locator('blockquote').first()).toContainText('Java');
    await ranked.getByRole('button', { name: /^Vérifier le recalcul du rang/ }).click();
    await expect(scoring.getByRole('status').filter({ hasText: 'Recalcul vérifié' })).toBeVisible();
    if (width === 320) await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: `test-results/scoring-${width}.png`, fullPage: true });

    // SH-19: a pool lead validates a new version; old snapshots retain their weights.
    await adminPage.getByLabel('Rôle dans ce vivier', { exact: true }).selectOption('RECRUITMENT_LEAD');
    await adminPage.getByRole('button', { name: 'Enregistrer l’habilitation', exact: true }).click();
    await expect(adminPage.getByText('Habilitation enregistrée.', { exact: false })).toBeVisible();
    await search.getByRole('button', { name: 'Rechercher dans ce vivier', exact: true }).click();
    const config = scoring.getByRole('region', { name: 'Configuration du scoring', exact: true });
    await config.getByText('Valider de nouveaux poids pour le poste', { exact: true }).click();
    await config.getByLabel('Nom du poste ou de la configuration', { exact: true }).fill(`Poste synthétique ${width}`);
    await config.getByLabel('Poids : Compétences obligatoires', { exact: true }).fill('0');
    await config.getByLabel('Poids : Compétences souhaitées', { exact: true }).fill('100');
    await config.getByLabel('Poids : Exposition justifiée', { exact: true }).fill('0');
    await config.getByLabel('Poids : Récence', { exact: true }).fill('0');
    await config.getByLabel('Motif de modification des poids', { exact: true }).fill('Priorités du poste synthétique validées');
    await config.getByRole('button', { name: 'Valider une nouvelle version des poids', exact: true }).click();
    await expect(config.getByRole('status')).toContainText('validée');
    await scoring.getByRole('button', { name: 'Calculer et figer le classement', exact: true }).click();
    await expect(scoring.getByRole('status').filter({ hasText: 'Classement figé' })).toBeVisible();
    await expect(ranked.getByText('Score documenté : 0 / 100', { exact: true })).toBeVisible();
    const prior = frozen.items.find((i: {hit:{professionalTitle:string}}) => i.hit.professionalTitle === `Développeur synthétique ${width}`);
    const replay = await page.request.post(`/api/v1/search/scored/${frozen.metadata.searchId}/candidates/${prior.hit.candidateId}/recalculate`, { headers: {'X-CSRF-TOKEN': session.csrfToken} });
    expect(replay.ok()).toBeTruthy();expect((await replay.json()).score).toEqual(prior.score);
    await ranked.getByText('Contester ou décider pour ce profil', { exact: true }).click();
    const decisions = ranked.getByRole('region', { name: 'Revue du classement', exact: true });
    await decisions.getByRole('button', { name: 'Charger l’historique des décisions', exact: true }).click();
    await decisions.getByLabel('Motif factuel de la décision', { exact: true }).fill('Preuve professionnelle synthétique contestée');
    await decisions.getByRole('button', { name: 'Enregistrer la décision', exact: true }).click();
    await expect(decisions.getByRole('status')).toContainText('Décision enregistrée');
    await decisions.getByLabel('Action sur le classement', { exact: true }).selectOption('SELECT');
    await decisions.getByLabel('Motif factuel de la décision', { exact: true }).fill('Sélection humaine pour vérification malgré le score nul');
    await decisions.getByRole('button', { name: 'Enregistrer la décision', exact: true }).click();
    await expect(decisions.getByText('Sélection humaine pour vérification malgré le score nul', { exact: true })).toBeVisible();
    await expect(ranked.getByText('Score documenté : 0 / 100', { exact: true })).toBeVisible();
    await decisions.getByRole('button', { name: 'Charger l’historique des décisions', exact: true }).click();
    await expect(decisions.locator('ol > li')).toHaveCount(2);
    await decisions.screenshot({ path: `test-results/score-review-${width}.png` });
    await config.getByRole('button', { name: 'Historique des poids', exact: true }).click();
    await expect(config.getByText('Avant :', { exact: false }).first()).toBeVisible();
    await page.screenshot({ path: `test-results/scoring-configuration-${width}.png`, fullPage: true });
    await adminPage.getByLabel('Rôle dans ce vivier', { exact: true }).selectOption('RECRUITER');
    await adminPage.getByRole('button', { name: 'Enregistrer l’habilitation', exact: true }).click();
    await expect(adminPage.getByRole('button', { name: 'Enregistrer l’habilitation', exact: true })).toBeEnabled();
    await adminContext.close();
    // SH-21: personal organization is explicit and unaffected by searches.
    // The import-scoped fiche was unmounted by page.reload(); use the reopened fiche.
    const currentCandidate = page.getByRole('region', { name: 'Fiche candidat', exact: true });
    await expect(currentCandidate).toHaveCount(1);
    await currentCandidate.getByText('Organiser ce candidat', { exact: true }).click();
    const follow = currentCandidate.getByRole('region', { name: 'Suivi personnel du candidat', exact: true });
    const loadedFollow = page.waitForResponse(r => r.url().endsWith('/tracking') && r.request().method() === 'GET');
    await follow.getByRole('button', { name: 'Charger ou remplacer ma saisie de suivi', exact: true }).click();
    const initialFollow = await (await loadedFollow).json();
    expect(initialFollow.version).toBe(0);
    await follow.getByLabel('Statut de suivi', { exact: true }).selectOption('SHORTLISTED');
    await follow.getByLabel('Listes nommées (séparées par des virgules)', { exact: true }).fill(`Poste synthétique ${width}`);
    await follow.getByLabel('Tags professionnels (séparés par des virgules)', { exact: true }).fill('Java, Backend');
    await follow.getByLabel('Note factuelle', { exact: true }).fill('Preuve Java publiée à revoir en entretien technique.');
    await follow.getByLabel('Motif du changement de suivi', { exact: true }).fill('Décision humaine après lecture des preuves');
    await follow.getByRole('checkbox').check();
    await follow.getByRole('button', { name: 'Enregistrer mon suivi', exact: true }).click();
    await expect(follow.getByRole('status')).toContainText('Suivi personnel enregistré');
    await follow.getByRole('button', { name: 'Historique de mon suivi', exact: true }).click();
    await expect(follow.getByText('Version 1', { exact: false })).toBeVisible();
    await search.getByRole('button', { name: 'Rechercher dans ce vivier', exact: true }).click();
    const persisted = await (await page.request.get(`/api/v1/candidates/${initialFollow.candidateId}/tracking`)).json();
    expect(persisted.version).toBe(1);expect(persisted.values.status).toBe('SHORTLISTED');
    const lists = page.getByRole('region', { name: 'Mes listes de candidats', exact: true });
    await lists.getByLabel('Nom exact de la liste', { exact: true }).fill(`Poste synthétique ${width}`);
    await lists.getByLabel('Tag exact', { exact: true }).fill('JAVA');
    await lists.getByRole('button', { name: 'Afficher mon suivi', exact: true }).click();
    await expect(lists.locator('article')).toHaveCount(1);
    await expect(lists.locator('article')).toContainText('Présélectionné');
    await expect(lists.locator('article')).toContainText('Preuve Java publiée');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await follow.screenshot({ path: `test-results/candidate-tracking-${width}.png` });
    await lists.screenshot({ path: `test-results/candidate-lists-${width}.png` });

    if (width === 320) {
      await page.evaluate(() => { document.documentElement.style.fontSize = '32px'; });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy();
    }
    // Only the synthetic application view is captured, never the login or credential form.
    await page.screenshot({ path: `test-results/import-${width}.png`, fullPage: true });
  });
}

for (const width of [1280, 320]) {
  test(`batch partial success survives interruption and reload at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.getByRole('link', { name: 'Se connecter avec le compte professionnel' }).click();
    await page.locator('#username').fill('alice');
    await page.locator('#password').fill(process.env.DEMO_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await page.getByRole('button', { name: 'Importer un lot', exact: true }).click();
    await page.getByLabel('Provenance des documents').fill('Lot synthétique de recette navigateur');
    const suffix = `${width}-${Date.now()}`;
    const files = [
      { name: `accepted-${suffix}.md`, mimeType: 'text/markdown', buffer: Buffer.from('# Synthetic profile') },
      { name: `invalid-${suffix}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('This is not a PDF') },
      { name: `resume-${suffix}.md`, mimeType: 'text/markdown', buffer: Buffer.from('# Resume synthetic profile') }
    ];
    // Let the first two independent uploads finish, then interrupt the third before it reaches the server.
    let uploads = 0;
    await page.route('**/api/v1/import-batches/*/items/*/content', async route => {
      uploads++;
      if (uploads === 3) await route.abort('failed'); else await route.continue();
    });
    await page.getByLabel('Documents du lot', { exact: true }).setInputFiles(files);
    await page.getByRole('button', { name: 'Envoyer le lot', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: '2 / 3 fichiers terminés' })).toBeVisible();
    await expect(page.getByText('Format refusé', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Nouveau lot', exact: true })).toBeEnabled();
    await page.unroute('**/api/v1/import-batches/*/items/*/content');
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await page.getByRole('button', { name: 'Importer un lot', exact: true }).click();
    await page.getByRole('button', { name: 'Ouvrir le lot', exact: true }).first().click();
    await page.getByLabel('Documents du lot', { exact: true }).setInputFiles(files);
    let resumed = 0;
    page.on('request', req => { if (req.method() === 'PUT' && req.url().includes('/items/')) resumed++; });
    await page.getByRole('button', { name: 'Reprendre les fichiers sélectionnés', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: '3 / 3 fichiers terminés' })).toBeVisible();
    expect(resumed).toBe(1);
    await expect(page.getByText('En quarantaine', { exact: true })).toHaveCount(2);
    if (width === 320) {
      await page.evaluate(() => { document.documentElement.style.fontSize = '32px'; });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy();
    }
    await page.screenshot({ path: `test-results/batch-${width}.png`, fullPage: true });
  });
}

for (const width of [1280, 320]) {
  test(`scanned PDF OCR remains unvalidated at ${width}px`, async ({ page }) => {
    test.setTimeout(150000);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.getByRole('link', { name: 'Se connecter avec le compte professionnel' }).click();
    await page.locator('#username').fill('alice');
    await page.locator('#password').fill(process.env.DEMO_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await page.getByLabel('Provenance du document').fill('PDF image synthétique de recette OCR');
    await page.getByLabel('Document à déposer').setInputFiles('../../services/document-worker/tests/scanned.pdf');
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    const receipt = page.locator('.import-list > li').first();
    await receipt.getByRole('button', { name: 'Consulter l’analyse', exact: true }).click();
    await expect(async () => {
      await receipt.getByRole('button', { name: 'Actualiser l’analyse', exact: true }).click();
      await expect(receipt.getByText('Texte extrait · non validé', { exact: true })).toBeVisible();
    }).toPass({ timeout: 110000, intervals: [2000] });
    await expect(receipt.getByRole('heading', { name: 'Pages traitées par OCR' })).toBeVisible();
    await expect(receipt.getByText('Page 1', { exact: true })).toBeVisible();
    await receipt.getByText('Voir le texte brut et ses sources', { exact: true }).click();
    await expect(receipt.locator('.extracted-text')).toContainText('Java');
    await expect(receipt.getByText('ocr:page:1', { exact: true })).toBeVisible();
    await receipt.getByText('Traçabilité OCR de la page 1', { exact: true }).click();
    if (width === 320) {
      await page.evaluate(() => { document.documentElement.style.fontSize = '32px'; });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy();
    }
    await page.screenshot({ path: `test-results/ocr-${width}.png`, fullPage: true });
  });
}
