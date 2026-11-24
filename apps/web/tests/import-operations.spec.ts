import {test,expect} from '@playwright/test';
for(const width of [1280,320])test(`scoped operations metrics and controlled retry UI at ${width}px`,async({page})=>{
 let uiCsrf='';page.on('response',async response=>{if(new URL(response.url()).pathname==='/api/v1/session'&&response.status()===200)uiCsrf=(await response.json()).csrfToken;});
 await page.setViewportSize({width,height:900});await page.goto('/');await page.getByRole('link',{name:'Se connecter avec le compte professionnel'}).click();await page.locator('#username').fill('admin-demo');await page.locator('#password').fill(process.env.DEMO_PASSWORD!);await page.locator('#kc-login').click();await expect(page.getByRole('heading',{name:'Mes viviers',exact:true})).toBeVisible();
 const session=await(await page.request.get('/api/v1/session')).json();const headers={'X-CSRF-TOKEN':session.csrfToken};
 const pools=await(await page.request.get('/api/v1/admin/pools')).json();const pool=pools[0].id;
 const memberships=await(await page.request.get(`/api/v1/admin/users/${session.userId}/memberships`)).json();const previous=memberships.filter((m:{poolId:string})=>m.poolId===pool).map((m:{role:string})=>m.role);
 try {
  expect((await page.request.put('/api/v1/admin/memberships',{headers,data:{userId:session.userId,poolId:pool,roles:[...new Set([...previous,'RECRUITER'])]}})).ok()).toBeTruthy();
  if(width===320){await page.evaluate(()=>document.documentElement.style.fontSize='200%');await page.getByRole('button',{name:'Ouvrir le menu'}).click();}
  await page.getByRole('button',{name:'Suivi des imports',exact:true}).click();const region=page.getByRole('region',{name:'Suivi opérationnel des imports',exact:true});
  await expect(region.getByLabel('Vivier à superviser')).toHaveValue(pool);await region.getByRole('button',{name:'Actualiser le suivi',exact:true}).click();
  await expect(region.getByRole('status').filter({hasText:'Observation du'})).toBeVisible();
  const real=await page.request.get(`/api/v1/admin/pools/${pool}/import-operations`);expect(real.ok()).toBeTruthy();expect(await real.text()).not.toMatch(/fileName|displayName|result_json|object_key/);
  // Deterministic UI fixture only for failure/retry transitions; real authorization and mutation are integration-tested in Java.
  const id='30000000-0000-0000-0000-000000000036';let version=7,attempts=0,accepted=false;
  const base=`/api/v1/admin/pools/${pool}/import-operations`;
  await page.route(`**${base}/jobs?*`,route=>route.fulfill({json:{items:accepted?[]:[{id,version,state:'FAILED',attempts:3,manualRetries:0,leaseRecoveries:1,code:'WORKER_UNAVAILABLE',updatedAt:'2026-11-24T08:00:00Z',leaseUntil:null,retryable:true}],nextCursor:null}}));
  await page.route(`**${base}/jobs/${id}/retry`,async route=>{
   expect(uiCsrf).not.toBe('');expect(route.request().headers()['x-csrf-token']).toBe(uiCsrf);expect(route.request().headers()['if-match']).toBe(`"${version}"`);expect(route.request().postDataJSON()).toEqual({reason:'CONFIGURATION_FIXED'});attempts++;
   if(attempts===1){version++;await route.fulfill({status:412,json:{code:'ANALYSIS_CHANGED'}});}else{accepted=true;await route.fulfill({status:202,json:{id,version:version+1,state:'PENDING'}});}
  });
  await region.getByRole('button',{name:'Actualiser le suivi',exact:true}).click();
  for(let n=0;n<2;n++){
   await region.getByRole('button',{name:`Préparer la relance de ${id}`,exact:true}).click();const confirm=region.getByRole('button',{name:'Confirmer la relance',exact:true});await expect(confirm).toBeDisabled();
   await region.getByLabel('J’ai vérifié la cause et confirme cette relance.',{exact:true}).check();await region.getByLabel('Cause résolue',{exact:true}).selectOption('CONFIGURATION_FIXED');await expect(confirm).toBeDisabled();
   await region.getByLabel('J’ai vérifié la cause et confirme cette relance.',{exact:true}).check();await confirm.click();
   if(n===0){await expect(region.getByRole('alert')).toContainText('Cette tâche a changé');await region.getByRole('button',{name:'Actualiser le suivi',exact:true}).click();}
  }
  await expect(region.getByRole('status').filter({hasText:'Relance enregistrée'})).toBeVisible();expect(attempts).toBe(2);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);await region.screenshot({path:`test-results/import-operations-${width}.png`});
 } finally {expect((await page.request.put('/api/v1/admin/memberships',{headers,data:{userId:session.userId,poolId:pool,roles:previous}})).ok()).toBeTruthy();}
});
