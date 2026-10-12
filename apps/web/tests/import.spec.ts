import { test, expect } from '@playwright/test';

for (const width of [1280, 320]) {
  test(`real login and quarantined import at ${width}px`, async ({ page }) => {
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
    await page.getByLabel('Document à déposer').setInputFiles({ name: filename, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java 2,5\n`) });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    const copyName = `copy-${filename}`;
    await page.getByLabel('Provenance du document').fill('Copie synthétique pour contrôle des doublons');
    await page.getByLabel('Document à déposer').setInputFiles({ name: copyName, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java 2,5\n`) });
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
