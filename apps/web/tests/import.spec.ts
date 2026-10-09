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
    await page.getByLabel('Document à déposer').setInputFiles({ name: filename, mimeType: 'text/markdown', buffer: Buffer.from('# Profil synthétique\nCompétence : Java\n') });
    await page.getByRole('button', { name: 'Déposer en quarantaine', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Dépôt reçu en quarantaine' })).toBeVisible();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    await page.reload();
    await page.getByRole('button', { name: 'Ouvrir le vivier' }).click();
    await expect(page.getByText(filename, { exact: true })).toBeVisible();
    if (width === 320) {
      await page.evaluate(() => { document.documentElement.style.fontSize = '32px'; });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy();
    }
    // Only the synthetic application view is captured, never the login or credential form.
    await page.screenshot({ path: `test-results/import-${width}.png`, fullPage: true });
  });
}
