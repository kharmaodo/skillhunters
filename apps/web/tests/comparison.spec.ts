import { test, expect } from '@playwright/test';
for (const width of [1280,320]) {
 test(`compare three profiles with shared criteria at ${width}px`,async({page})=>{
  test.setTimeout(180000);await page.setViewportSize({width,height:900});await page.goto('/');
  await page.getByRole('link',{name:'Se connecter avec le compte professionnel'}).click();
  await page.locator('#username').fill('alice');await page.locator('#password').fill(process.env.DEMO_PASSWORD!);await page.locator('#kc-login').click();
  await page.getByRole('button',{name:'Ouvrir le vivier'}).click();
  const session=await(await page.request.get('/api/v1/session')).json();const headers={'X-CSRF-TOKEN':session.csrfToken};
  const titles:string[]=[];const ids:string[]=[];
  for(let n=0;n<3;n++){
   const title=`Comparaison synthétique ${width} profil ${n}`;titles.push(title);
   await page.getByLabel('Provenance du document').fill('Recette SH-22 synthétique');
   await page.getByLabel('Document à déposer').setInputFiles({name:`compare-${width}-${n}-${Date.now()}.md`,mimeType:'text/markdown',buffer:Buffer.from(`Compétence : ${n===1?'Python':'Java'} 2,5\nExpérience : Projet synthétique 2024/25\n${title}`)});
   const received=page.waitForResponse(r=>r.url().endsWith('/api/v1/imports')&&r.request().method()==='POST');
   await page.getByRole('button',{name:'Déposer en quarantaine',exact:true}).click();const receipt=await(await received).json();
   await expect.poll(async()=>(await(await page.request.get(`/api/v1/imports/${receipt.id}/analysis`)).json()).state,{timeout:90000,intervals:[1000]}).toBe('EXTRACTED');
   const extracted=await page.request.post(`/api/v1/imports/${receipt.id}/extraction`,{headers});expect(extracted.ok()).toBeTruthy();const revision=await extracted.json();
   const created=await page.request.post(`/api/v1/imports/${receipt.id}/candidate`,{headers,data:{revisionId:revision.id}});expect(created.ok()).toBeTruthy();const candidate=await created.json();ids.push(candidate.id);
   const updated=await page.request.patch(`/api/v1/candidates/${candidate.id}`,{headers:{...headers,'If-Match':`"${candidate.version}"`},data:{...candidate.profile,professionalTitle:title,reason:'Titre de recette synthétique'}});expect(updated.ok()).toBeTruthy();
   const items=revision.proposal.claims.map((c:{status:string;normalized:string|null;field:string},sourceIndex:number)=>{const unknown=c.status==='UNKNOWN';return {sourceIndex,value:unknown?null:c.normalized,decision:unknown?'UNKNOWN':'VALIDATED',precision:unknown?'UNKNOWN':c.field==='DECLARED_LEVEL'?'DECLARED_LEVEL':'TEXT',period:c.field==='EXPERIENCE'&&!unknown?{start:'2024-01',end:'2025-06',precision:'MONTH',kind:'ACADEMIC'}:null,reason:unknown?null:'Vérification synthétique'};});
   const published=await page.request.post(`/api/v1/candidates/${candidate.id}/validate`,{headers:{...headers,'If-Match':updated.headers()['etag']},data:{items,justification:'Publication synthétique relue'}});expect(published.ok()).toBeTruthy();
  }
  const search=page.getByRole('region',{name:'Recherche structurée',exact:true});await search.getByLabel('Compétence 1',{exact:true}).fill('Java');await search.getByRole('button',{name:'Rechercher dans ce vivier',exact:true}).click();
  const scoring=search.getByRole('region',{name:'Classement documenté',exact:true});await scoring.getByRole('button',{name:'Calculer et figer le classement',exact:true}).click();
  const comparison=scoring.getByRole('region',{name:'Comparaison de profils',exact:true});const compare=comparison.getByRole('button',{name:'Comparer les profils choisis',exact:true});await expect(compare).toBeDisabled();
  await comparison.getByRole('checkbox',{name:`Nom inconnu · ${titles[0]}`,exact:true}).check();await expect(compare).toBeDisabled();
  for(const title of titles.slice(1))await comparison.getByRole('checkbox',{name:`Nom inconnu · ${title}`,exact:true}).check();await compare.click();
  const result=comparison.getByRole('region',{name:'Résultat de la comparaison',exact:true});await expect(result.getByRole('status')).toContainText('3 profils comparés');
  const criterion=result.getByRole('region',{name:'Critère 1 : Java',exact:true});await expect(criterion.locator('.comparison-cell')).toHaveCount(3);await expect(criterion.getByText('Exposition calculée : Inconnue',{exact:true})).toHaveCount(3);await expect(criterion.locator('.comparison-cell').nth(1)).toContainText('Non documenté');
  await criterion.locator('.comparison-cell').first().locator('summary').click();await expect(criterion.locator('blockquote').first()).toContainText('Java');
  if(width===320)await page.evaluate(()=>{document.documentElement.style.fontSize='200%';});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true);await result.screenshot({path:`test-results/profile-comparison-${width}.png`});
  await comparison.getByRole('button',{name:`Retirer Nom inconnu · ${titles[2]}`,exact:true}).click();await expect(result).toHaveCount(0);await compare.click();await expect(result.getByRole('status')).toContainText('2 profils comparés');
  for(const id of ids){const state=await(await page.request.get(`/api/v1/candidates/${id}/tracking`)).json();expect(state.version).toBe(0);}
  await comparison.getByRole('button',{name:'Vider la comparaison',exact:true}).click();await expect(result).toHaveCount(0);await expect(compare).toBeDisabled();
 });
}
