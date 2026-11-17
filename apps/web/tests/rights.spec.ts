import {test,expect,type Page} from '@playwright/test';
const id='33333333-0000-0000-0000-000000000001';
const candidate={id,poolId:'20000000-0000-0000-0000-000000000001',importId:id,revisionId:id,version:1,activeVersion:true,contactsVisible:true,contact:{email:'synthetic@example.com',phone:null},expiresAt:'2027-12-31T00:00:00Z',profile:{displayName:'Personne synthétique SH33',professionalTitle:'Recette',availability:{status:'UNKNOWN',availableFrom:null,declaredOn:null,declaration:null}}};
async function fixture(page:Page){
 await page.route('**/api/v1/candidates?**',r=>r.fulfill({json:{items:[candidate],nextCursor:null}}));
 await page.route('**/api/v1/candidates/'+id,r=>r.fulfill({json:candidate}));
 const item={sourceIndex:0,value:'Projet synthétique',decision:'VALIDATED',precision:'TEXT',period:{start:'2024-01',end:'2024-12',precision:'MONTH',kind:'ACADEMIC'},reason:'Revue synthétique'};
 const revision={id,candidateId:id,version:1,status:'PUBLISHED',items:[item],changes:[],actorId:id,createdAt:'2026-11-01T00:00:00Z',justification:'Revue synthétique'};
 await page.route('**/api/v1/candidates/'+id+'/review',r=>r.fulfill({json:{version:1,latest:revision,publishedRevisionId:id,source:{id,proposal:{claims:[{field:'EXPERIENCE',item:1,raw:'Projet synthétique',normalized:'Projet synthétique',status:'PROPOSED',warnings:[],evidence:[{source:'document',start:0,end:18,quote:'Projet synthétique'}]}]}}}}));
 let current:any=null;let corrected=false;let prepared=false;
 await page.route('**/api/v1/candidates/'+id+'/rights-requests**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;
  if(req.method()==='GET'){await route.fulfill({json:{items:current?[current]:[],nextCursor:null}});return;}
  const body=req.postData()?req.postDataJSON():{};
  if(path.endsWith('/rights-requests')){current={...body,id:'33333333-0000-0000-0000-000000000002',candidateId:id,version:1,state:'RECEIVED',verifiedAt:null,overdue:false};}
  else {
   expect(req.headers()['if-match']).toBe(`"${current.version}"`);
   current={...current,version:current.version+1};
   if(path.endsWith('/verification')){expect(body.confirmed).toBe(true);current={...current,state:'VERIFIED',verifiedAt:'2026-11-17T09:00:00Z'};}
   if(path.endsWith('/rectification')){expect(body.candidateVersion).toBe(1);expect(body.confirmed).toBe(true);expect(body.review.items[0].period.end).toBe('2024-06');corrected=true;current={...current,state:'IN_PROGRESS'};await route.fulfill({json:{request:current,revision:{...revision,version:2,items:body.review.items}}});return;}
   if(path.endsWith('/access-package')){expect(current.verifiedAt).not.toBeNull();prepared=true;current={...current,state:'IN_PROGRESS'};await route.fulfill({json:{request:current,candidate,source:{synthetic:true},published:revision,scope:['Recette synthétique']}});return;}
   if(path.endsWith('/closure')){expect(body.confirmed).toBe(true);expect(corrected||prepared).toBe(true);current={...current,state:'CLOSED',outcome:body.outcome,responseReference:body.responseReference};}
  }
  await route.fulfill({status:path.endsWith('/rights-requests')?201:200,json:current});
 });
 await page.goto('/');await page.getByRole('link',{name:'Se connecter avec le compte professionnel'}).click();await page.locator('#username').fill('alice');await page.locator('#password').fill(process.env.DEMO_PASSWORD!);await page.locator('#kc-login').click();
 await page.getByRole('button',{name:'Ouvrir le vivier'}).click();await page.getByRole('button',{name:'Actualiser les fiches'}).click();await page.getByRole('button',{name:'Personne synthétique SH33 · Recette'}).click();
 return page.getByRole('region',{name:'Demandes et droits du candidat',exact:true});
}
async function intake(region:ReturnType<Page['getByRole']>,kind:string){
 await region.getByRole('button',{name:'Consulter les demandes'}).click();await region.getByText('Enregistrer une demande reçue par e-mail',{exact:true}).click();
 await region.getByLabel('Type de demande',{exact:true}).selectOption(kind);await region.getByLabel('Échéance applicable',{exact:true}).fill('2026-12-01T12:00');await region.getByLabel('Référence de la politique applicable').fill('POLICY-SYNTHETIC-1');await region.getByLabel('Référence interne de l’e-mail reçu').fill('EMAIL-SYNTHETIC-1');await region.getByLabel('Objet résumé de la demande').fill('Demande synthétique de rectification');
 await region.getByRole('button',{name:'Enregistrer la demande',exact:true}).click();
 await expect(region.getByRole('button',{name:'Clôturer avec la réponse consignée'})).toHaveCount(0);
 await region.getByText('Vérification humaine de l’identité',{exact:true}).click();await region.getByLabel('Référence du contrôle effectué').fill('CHECK-SYNTHETIC-1');
 const check=region.getByRole('button',{name:'Consigner la vérification'});await expect(check).toBeDisabled();await region.getByRole('checkbox',{name:'J’atteste avoir vérifié',exact:false}).check();await check.click();await expect(region.getByRole('heading',{name:/Identité vérifiée/})).toBeVisible();
}
for(const width of [1280,320]){
 test(`verified candidate rectification and closure at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});const region=await fixture(page);if(width===320)await page.evaluate(()=>document.documentElement.style.fontSize='200%');
  await intake(region,'RECTIFICATION');await region.getByText('Rectifier et republier les données contestées',{exact:true}).click();
  const review=region.getByRole('region',{name:'Revue humaine',exact:true});await review.getByRole('button',{name:'Ouvrir la revue humaine'}).click();await review.locator('summary').filter({hasText:'Expérience'}).click();
  await review.getByLabel('Fin de période 1',{exact:true}).fill('2024-06');await review.getByLabel('Justification de la proposition 1',{exact:true}).fill('Période rectifiée après vérification');await review.getByLabel('Justification de cette révision',{exact:true}).fill('Réponse à la demande synthétique');
  await review.getByRole('button',{name:'Valider et publier la révision'}).click();await expect(review.getByRole('status')).toContainText('index professionnel a été mis à jour');
  await region.getByLabel('Motif de la décision et du traitement').fill('Correction publiée et réponse transmise');await region.getByLabel('Référence de l’examen ou de la réponse e-mail').fill('REPLY-SYNTHETIC-1');
  const close=region.getByRole('button',{name:'Clôturer avec la réponse consignée'});await expect(close).toBeDisabled();await region.getByRole('checkbox',{name:'Je confirme l’examen humain',exact:false}).check();await close.click();await expect(region.getByRole('heading',{name:/Clôturée/})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);
 });
}
test('verified access package downloads only on explicit preparation',async({page})=>{
 const region=await fixture(page);await intake(region,'ACCESS');const download=page.waitForEvent('download');await region.getByRole('button',{name:'Préparer le dossier vérifié'}).click();expect((await download).suggestedFilename()).toMatch(/^dossier-.*\.json$/);await expect(region.getByRole('status')).toContainText('avant transmission manuelle');
});
