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
    await page.getByLabel('Document à déposer').setInputFiles({ name: filename, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java\n`) });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    const copyName = `copy-${filename}`;
    await page.getByLabel('Provenance du document').fill('Copie synthétique pour contrôle des doublons');
    await page.getByLabel('Document à déposer').setInputFiles({ name: copyName, mimeType: 'text/markdown', buffer: Buffer.from(`# Profil synthétique ${filename}\nCompétence : Java\n`) });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    const copy = page.locator('.import-list > li').filter({ has: page.getByText(copyName, { exact: true }) });
    await copy.getByRole('button', { name: 'Vérifier les fichiers identiques' }).click();
    await expect(copy.getByText(filename, { exact: true })).toBeVisible();
    await expect(copy.getByText('Signal documentaire uniquement.', { exact: false })).toBeVisible();
    await expect(copy.locator('ul > li')).toHaveCount(1);
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
