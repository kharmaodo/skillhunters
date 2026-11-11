import {test,expect} from '@playwright/test';
for(const width of [1280,320]) {
 test(`public opposition requires confirmation and exposes no CV at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});
  await page.goto('/#opposition='+'A'.repeat(43));
  if(width===320)await page.evaluate(()=>document.documentElement.style.fontSize='200%');
  const button=page.getByRole('button',{name:'Confirmer mon opposition',exact:true});
  await expect(button).toBeDisabled();
  await expect(page.getByRole('link',{name:'Se connecter avec le compte professionnel'})).toHaveCount(0);
  await page.getByRole('checkbox').check();await button.click();
  await expect(page.getByRole('alert')).toContainText('indisponible ou expiré');
  // Exercise the successful UI state with a stub; real redemption is covered by PostgreSQL API tests.
  let requests=0;
  await page.route('**/api/v1/public/contact-opposition',async route=>{requests++;expect(route.request().postDataJSON()).toEqual({token:'A'.repeat(43)});await route.fulfill({status:204});});
  await button.click();await expect(page.getByRole('status')).toContainText('a été enregistrée');
  expect(requests).toBe(1);expect(new URL(page.url()).hash).toBe('');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
 });
}
