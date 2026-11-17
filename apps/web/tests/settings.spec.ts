import {test,expect} from '@playwright/test';
for(const width of [1280,320])test(`admin publishes and restores import settings at ${width}px`,async({page})=>{
 await page.setViewportSize({width,height:900});await page.goto('/');await page.getByRole('link',{name:'Se connecter avec le compte professionnel'}).click();await page.locator('#username').fill('admin-demo');await page.locator('#password').fill(process.env.DEMO_PASSWORD!);await page.locator('#kc-login').click();await expect(page.getByRole('heading',{name:'Mes viviers'})).toBeVisible();
 if(width===320){await page.evaluate(()=>document.documentElement.style.fontSize='200%');await page.getByRole('button',{name:'Ouvrir le menu'}).click();}
 await page.getByRole('button',{name:'Paramètres et modèle',exact:true}).click();await page.getByRole('button',{name:'Charger les paramètres'}).click();
 const field=page.getByLabel('Taille maximale par fichier (octets)',{exact:true});const previous=await field.inputValue();
 await field.fill('1048576');await page.getByLabel('Motif de la modification',{exact:true}).fill('Recette synthétique SH28');await page.getByRole('button',{name:'Publier les paramètres'}).click();await expect(page.getByRole('status')).toContainText('Nouvelle version publiée');
 await page.getByRole('button',{name:'Historique des paramètres'}).click();await expect(page.getByText('Recette synthétique SH28',{exact:false}).first()).toBeVisible();
 const download=page.waitForEvent('download');await page.getByRole('link',{name:'Télécharger le modèle propre'}).click();expect((await download).suggestedFilename()).toBe('skillhunters-cv-template-v1.docx');
 await field.fill(previous);await page.getByLabel('Motif de la modification',{exact:true}).fill('Restauration des limites après recette');await page.getByRole('button',{name:'Publier les paramètres'}).click();await expect(page.getByRole('status')).toContainText('Nouvelle version publiée');await page.getByRole('button',{name:'Recharger les paramètres'}).click();await expect(field).toHaveValue(previous);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
});
