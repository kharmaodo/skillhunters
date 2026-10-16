import { test, expect } from '@playwright/test';
for (const width of [1280, 320]) {
  test(`admin taxonomy and confirmed merge at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await page.getByRole('link', { name: 'Se connecter avec le compte professionnel' }).click();
    await page.locator('#username').fill('admin-demo');
    await page.locator('#password').fill(process.env.DEMO_PASSWORD!);
    await page.locator('#kc-login').click();
    await expect(page.getByRole('heading', { name: 'Mes viviers' })).toBeVisible();
    if (width === 320) { await page.evaluate(() => document.documentElement.style.fontSize = '200%'); await page.getByRole('button', { name: 'Ouvrir le menu' }).click(); }
    await page.getByRole('button', { name: 'Compétences', exact: true }).click();
    const suffix=`${width}-${Date.now()}`;
    const name=`PostgreSQL ${suffix}`, alias=`pg-${suffix}`, legacy=`Legacy ${suffix}`;
    async function create(label: string, synonym: string) {
      await page.getByRole('button', { name: 'Nouvelle compétence' }).click();
      await page.getByLabel('Nom canonique', { exact: true }).fill(label);
      await page.getByLabel('Catégorie', { exact: true }).fill('Base de données');
      await page.getByLabel('Alias (un par ligne, 50 maximum)', { exact: true }).fill(synonym);
      await page.getByLabel('Justification', { exact: true }).fill('Recette synthétique');
      await page.getByRole('button', { name: 'Enregistrer la compétence', exact: true }).click();
    }
    await create(name,alias);
    await expect(page.getByRole('button',{name,exact:true})).toBeVisible();
    await create(legacy,alias);
    await expect(page.getByRole('alert')).toContainText('Cette appellation est déjà utilisée');
    await page.getByLabel('Alias (un par ligne, 50 maximum)', { exact: true }).fill(`old-${suffix}`);
    await page.getByRole('button', { name: 'Enregistrer la compétence', exact: true }).click();
    await page.getByRole('button',{name:legacy,exact:true}).click();
    await page.getByLabel('Compétence cible', { exact: true }).selectOption({ label:name });
    await page.getByLabel('Justification', { exact: true }).fill('Synonymes vérifiés');
    await expect(page.getByRole('button',{name:'Confirmer la fusion',exact:true})).toBeDisabled();
    await page.getByRole('checkbox',{name:/Je confirme la fusion/}).check();
    await page.getByRole('button',{name:'Confirmer la fusion',exact:true}).click();
    await expect(page.getByRole('status')).toContainText('Fusion enregistrée');
    await expect(page.getByRole('button',{name:legacy,exact:true})).toHaveCount(0);
    await page.getByRole('button',{name:'Charger l’historique',exact:true}).click();
    await expect(page.getByText(/MERGE · Synonymes vérifiés/).first()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBeTruthy();
    await page.screenshot({path:`test-results/taxonomy-${width}.png`,fullPage:true});
  });
}
