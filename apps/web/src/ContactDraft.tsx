import { ContactDelivery } from './ContactDelivery';
import { useState } from 'react';
import { api, message, type Session } from './api';
type Skill={sourceIndex:number;value:string};
type Context={templates:{id:string;version:string;label:string}[];variables:string[];publicationId:string|null;skills:Skill[];recipient:string|null};
type Draft={version:number;publicationId:string;templateId:string;templateVersion:string;variables:Record<string,string>;matchingSkills:Skill[];recipient:string;subject:string;body:string;missingFields:string[];sendBlocked:boolean;createdAt:string};
type History={items:Draft[];nextCursor:number|null;latestVersion:number};
const labels:Record<string,string>={candidateFirstName:'Prénom fiable (facultatif)',candidateLastName:'Nom fiable (facultatif)',recruiterName:'Nom du recruteur',companyName:'Entreprise',jobTitle:'Poste proposé',jobDescription:'Description du poste',workLocation:'Organisation du travail',contractType:'Type de contrat',contactEmail:'E-mail de réponse',unsubscribeOrObjectionLink:'Lien HTTPS d’opposition',matchingSkills:'Compétences publiées sélectionnées',recipient:'E-mail du candidat',subject:'Objet',body:'Message',unresolvedVariables:'Variables non remplacées'};
export function ContactDraft({candidateId,session,failed}:{candidateId:string;session:Session;failed:(e:unknown)=>void}) {
 const [ctx,setCtx]=useState<Context|null>(null),[template,setTemplate]=useState('professional'),[variables,setVariables]=useState<Record<string,string>>({}),[skills,setSkills]=useState<number[]>([]);
 const [version,setVersion]=useState(0),[draft,setDraft]=useState<Draft|null>(null),[history,setHistory]=useState<History|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [dirty,setDirty]=useState(false);
 const route=`/candidates/${candidateId}/contact-draft`;
 function invalidate(){setDirty(true);setDraft(null);setNotice('');}
 async function load(){setDirty(false);setBusy(true);setError('');setDraft(null);setHistory(null);try{
   const context=await api<Context>(route+'/context');const h=await api<History>(route);setCtx(context);setHistory(h);
   const latest=h.items[0];setVersion(h.latestVersion??latest?.version??0);setTemplate(latest?.templateId??'professional');setVariables(latest?.variables??{});setSkills(latest?.matchingSkills.map(s=>s.sourceIndex)??[]);setDraft(latest?.publicationId===context.publicationId?latest:null);if(latest&&latest.publicationId!==context.publicationId){setSkills([]);setNotice("La publication a changé : sélectionnez à nouveau les compétences et régénérez le message.");}
 }catch(e){setCtx(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 async function prepare(save=false){setBusy(true);setError('');setNotice('');try{
   const result=await api<Draft>(route+(save?'':'/preview'),{method:save?'PUT':'POST',headers:save?{'If-Match':`"${version}"`}:{},body:JSON.stringify({publicationId:ctx?.publicationId,templateId:template,variables,skillIndices:skills,subject:save?draft?.subject:null,body:save?draft?.body:null})},session.csrfToken);
   setDraft(result);setDirty(!save);if(save){setVersion(result.version);setHistory(null);setNotice(`Brouillon enregistré · version ${result.version}.`);}
 }catch(e){setDraft(null);setHistory(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 async function older(cursor:number){setBusy(true);try{setHistory(await api<History>(route+`?cursor=${cursor}`));}catch(e){setHistory(null);setDraft(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 return <section className="human-review" aria-label="Brouillon de contact"><h4>Préparer un contact</h4>
 <p>Brouillon personnel, soumis à relecture. Enregistrez une version complète avant de la relire et de l’approuver. Sans prénom fiable, le modèle propose « Bonjour ».</p>
 <button className="button" disabled={busy} onClick={()=>void load()}>Charger le brouillon et remplacer ma saisie</button>
 {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 {ctx&&<fieldset disabled={busy}><legend>Modèle et informations à vérifier</legend>
 <label>Modèle de contact<select value={template} onChange={e=>{setTemplate(e.target.value);invalidate();}}>{ctx.templates.map(t=><option key={t.id} value={t.id}>{t.label} · v{t.version}</option>)}</select></label>
 <p>Destinataire : {ctx.recipient||'E-mail manquant : compléter les coordonnées de la fiche.'}</p>
 {ctx.variables.map(key=><label key={key}>{labels[key]}{key==='jobDescription'?<textarea maxLength={4000} value={variables[key]||''} onChange={e=>{setVariables({...variables,[key]:e.target.value});invalidate();}}/>:<input maxLength={500} value={variables[key]||''} onChange={e=>{setVariables({...variables,[key]:e.target.value});invalidate();}}/>}</label>)}
 <fieldset><legend>Compétences publiées à mentionner</legend>{ctx.skills.map(s=><label key={s.sourceIndex}><input type="checkbox" checked={skills.includes(s.sourceIndex)} onChange={e=>{setSkills(ids=>e.target.checked?[...ids,s.sourceIndex]:ids.filter(id=>id!==s.sourceIndex));invalidate();}}/>{s.value}</label>)}{ctx.skills.length===0&&<p>Aucune compétence publiée. Compléter la revue humaine.</p>}</fieldset>
 <button className="button" disabled={!ctx.publicationId} onClick={()=>void prepare()}>Générer un aperçu du message</button>
 </fieldset>}
 {draft&&<><p>Modèle v{draft.templateVersion} · publication {draft.publicationId}</p>
 {draft.missingFields.length>0&&<div role="alert"><p>Champs à compléter — envoi bloqué :</p><ul>{draft.missingFields.map(f=><li key={f}>{labels[f]||f}</li>)}</ul></div>}
 <label>Objet du brouillon<input maxLength={500} disabled={busy} value={draft.subject} onChange={e=>{setDraft({...draft,subject:e.target.value});setDirty(true);setNotice('');}}/></label>
 <label>Texte du brouillon<textarea maxLength={12000} rows={14} disabled={busy} value={draft.body} onChange={e=>{setDraft({...draft,body:e.target.value});setDirty(true);setNotice('');}}/></label>
 <p>Vérifiez chaque affirmation et le lien d’opposition. Toute édition reste une proposition non approuvée.</p>
 <button className="button" disabled={busy} onClick={()=>void prepare(true)}>Enregistrer le brouillon de contact</button>
 {!dirty&&draft.version>0&&<ContactDelivery key={draft.version} candidateId={candidateId} draft={draft} session={session} onBusy={setBusy}/>}
 </>}
 {history&&<details><summary>Historique des brouillons</summary>{history.items.map(d=><article key={d.version}><h5>Version {d.version} · {new Date(d.createdAt).toLocaleString()}</h5><p>{d.subject}</p><p style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{d.body}</p></article>)}{history.nextCursor!==null&&<button className="button" disabled={busy} onClick={()=>void older(history.nextCursor!)}>Révisions précédentes</button>}</details>}
 </section>;
}
