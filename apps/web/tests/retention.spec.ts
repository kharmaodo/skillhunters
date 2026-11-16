import {test,expect} from '@playwright/test';
for(const width of [1280,320]) {
 test(`retention confirmations and source removal at ${width}px`,async({page})=>{
  test.setTimeout(90000);
  await page.setViewportSize({width,height:900});await page.goto('/');
  await page.getByRole('link',{name:'Se connecter avec le compte professionnel'}).click();
  await page.locator('#username').fill('alice');await page.locator('#password').fill(process.env.DEMO_PASSWORD!);await page.locator('#kc-login').click();
  await page.getByRole('button',{name:'Ouvrir le vivier'}).click();
  const filename=`retention-synthetic-${width}-${Date.now()}.md`;
  await page.getByLabel('Provenance du document').fill('Recette conservation synthétique');
  await page.getByLabel('Document à déposer').setInputFiles({name:filename,mimeType:'text/markdown',buffer:Buffer.from('Synthetic retention fixture')});
  await page.getByRole('button',{name:'Déposer en quarantaine',exact:true}).click();
  await expect(page.getByText(filename,{exact:true})).toBeVisible();
  if(width===320)await page.evaluate(()=>document.documentElement.style.fontSize='200%');
  // UI contract fixture only; real authorization, erasure and replay are tested against PostgreSQL/S3.
  let state={sourceId:'00000000-0000-0000-0000-000000000001',expiresAt:'2027-01-01T00:00:00Z',holdVersion:0,holdUntil:null as string|null,holdReason:null as string|null,erasureState:'LIVE',completedAt:null,resultCode:null,categories:[]};
  await page.route('**/api/v1/imports/*/retention**',async route=>{
   const req=route.request();
   if(req.method()==='PUT'){
    expect(req.headers()['if-match']).toBe(`"${state.holdVersion}"`);
    expect(req.postDataJSON().confirmed).toBe(true);
    state={...state,holdVersion:state.holdVersion+1,holdUntil:req.postDataJSON().until,holdReason:req.postDataJSON().reason};
   }
   if(req.method()==='POST'){expect(req.postDataJSON()).toEqual({confirmed:true});state={...state,erasureState:'PENDING'};}
   await route.fulfill({status:req.method()==='POST'?202:200,contentType:'application/json',body:JSON.stringify(state)});
  });
  const row=page.locator('.import-list > li').first();
  await row.getByRole('button',{name:'Consulter la conservation'}).click();
  await expect(row.getByRole('button',{name:'Approuver le gel borné'})).toBeDisabled();
  await row.getByLabel('Fin du gel').fill('2026-12-01T12:00');
  await row.getByLabel('Motif du gel ou de sa levée').fill('Décision de recette synthétique');
  await row.getByRole('checkbox',{name:'J’approuve cette exception',exact:false}).check();
  await row.getByRole('button',{name:'Approuver le gel borné'}).click();
  await expect(row.getByRole('checkbox',{name:'J’approuve cette exception',exact:false})).not.toBeChecked();
  await row.getByRole('checkbox',{name:'J’approuve cette exception',exact:false}).check();
  await row.getByRole('button',{name:'Lever le gel'}).click();
  const erase=row.getByRole('button',{name:'Demander l’effacement définitif'});await expect(erase).toBeDisabled();
  await row.getByRole('checkbox',{name:'Je confirme l’effacement définitif',exact:false}).check();await erase.click();
  await expect(row.getByRole('status')).toContainText('Suppression en attente');
  await expect(row.getByText(filename,{exact:true})).toHaveCount(0);
  await expect(row.getByRole('button',{name:'Consulter l’analyse',exact:true})).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
 });
}
