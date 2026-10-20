import { useState } from 'react';
import { api, message, type Session } from './api';
type Period = { start: string | null; end: string | null; precision: string; kind: string };
type Link = { skillIndex: number; experienceIndex: number; period: Period; evidence: string; confirmed: boolean };
type Result = { minimumMonths: number | null; maximumMonths: number | null; recencyMinimumMonths: number | null; recencyMaximumMonths: number | null; incomplete: boolean; certainUnion: {start:string;endExclusive:string}[]; possibleUnion: {start:string;endExclusive:string}[] };
type Snapshot = { version:number; revisionId:string; links:Link[]; actorId:string; reason:string; createdAt:string };
type Report = { version:number; revisionId:string|null; referenceDate:string; rule:string; links:Link[]; globalByKind:Record<string,Result>; globalProfessional:Result; skills:{key:string;label:string;sourceIndices:number[];byKind:Record<string,Result>;professional:Result}[]; lastEdit:Snapshot|null };
type Claim = { field:string; evidence:{quote:string;source:string}[] };
type Item = {sourceIndex:number;value:string|null;decision:string;period:Period|null};
const kinds:Record<string,string>={EMPLOYMENT:'Emploi',INTERNSHIP:'Stage',FREELANCE:'Freelance',ACADEMIC:'Projet académique',UNKNOWN:'Type inconnu'};
const unknown:Period={start:null,end:null,precision:'UNKNOWN',kind:'UNKNOWN'};
function summary(r:Result) {return r.minimumMonths===null?'Durée inconnue':`${r.minimumMonths} à ${r.maximumMonths} mois calendaires complets${r.incomplete?' · couverture incomplète':''}`;}
function Metrics({value}:{value:Result}) {return <><p>{summary(value)}</p><p>Récence documentée : {value.recencyMinimumMonths===null?'inconnue':`${value.recencyMinimumMonths} à ${value.recencyMaximumMonths} mois depuis la dernière période`}</p><details><summary>Intervalles de calcul</summary><p>Bornes certaines (fin exclue) : {value.certainUnion.map(s=>`${s.start} → ${s.endExclusive}`).join(' ; ')||'aucune'}</p><p>Bornes possibles (fin exclue) : {value.possibleUnion.map(s=>`${s.start} → ${s.endExclusive}`).join(' ; ')||'aucune'}</p></details></>;}
export function SkillPeriods({candidateId,session,failed,versionChanged}:{candidateId:string;session:Session;failed:(e:unknown)=>void;versionChanged:(v:number)=>void}) {
 const [report,setReport]=useState<Report|null>(null);const [items,setItems]=useState<Item[]>([]);const [claims,setClaims]=useState<Claim[]>([]);
 const [links,setLinks]=useState<Link[]>([]);const [skill,setSkill]=useState('');const [experience,setExperience]=useState('');const [period,setPeriod]=useState<Period>(unknown);
 const [evidence,setEvidence]=useState('');const [confirmed,setConfirmed]=useState(false);const [reason,setReason]=useState('');const [reference,setReference]=useState('');
 const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [notice,setNotice]=useState('');const [history,setHistory]=useState<Snapshot[]>([]);const [cursor,setCursor]=useState(0);
 async function load() {setBusy(true);setError('');setNotice('');try {
   const r=await api<Report>(`/candidates/${candidateId}/skill-periods${reference?`?referenceDate=${reference}`:''}`);
   const state=await api<{source:{proposal:{claims:Claim[]}}}>(`/candidates/${candidateId}/review`);
   const revision=r.revisionId?await api<{items:Item[]}>(`/candidates/${candidateId}/revisions/${r.revisionId}`):null;
   setReport(r);setLinks(r.links);setItems(revision?.items??[]);setClaims(state.source.proposal.claims);setHistory([]);setSkill('');setExperience('');setPeriod(unknown);setEvidence('');setConfirmed(false);setReason('');versionChanged(r.version);
 }catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 function chooseExperience(index:string) {setExperience(index);setPeriod(items.find(i=>String(i.sourceIndex)===index)?.period??unknown);setEvidence('');setConfirmed(false);}
 function add() {setLinks([...links,{skillIndex:Number(skill),experienceIndex:Number(experience),period,evidence,confirmed}]);setConfirmed(false);setEvidence('');setNotice('Rattachement ajouté à la saisie. Enregistrez pour le conserver.');}
 async function save() {if(!report)return;setBusy(true);setError('');try {
   const r=await api<Report>(`/candidates/${candidateId}/skill-periods`,{method:'PUT',headers:{'If-Match':`"${report.version}"`},body:JSON.stringify({revisionId:report.revisionId,links,reason})},session.csrfToken);
   setReport(r);setLinks(r.links);setReference(r.referenceDate);setReason('');setHistory([]);versionChanged(r.version);setNotice('Rattachements enregistrés. Calcul actualisé à la date du jour.');
 }catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 async function audit(offset:number) {setBusy(true);try{setHistory(await api(`/candidates/${candidateId}/skill-periods/history?cursor=${offset}`));setCursor(offset);}catch(e){failed(e);}finally{setBusy(false);}}
 const selectable=items.filter(i=>i.decision==='VALIDATED');
 return <section className="human-review skill-periods"><h4>Périodes et récence des compétences</h4><p>Les mois décrivent une exposition documentée, pas un temps plein ni une pratique continue. Les stages, emplois, missions freelance et projets académiques restent distincts. Les notes 1–3 ne sont jamais converties en années.</p>
 <label>Date de référence du calcul<input type="date" value={reference} onChange={e=>setReference(e.target.value)}/></label><button className="button" disabled={busy} onClick={()=>void load()}>Charger les périodes et remplacer ma saisie</button>
 {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 {report&&<><p>Révision publiée : {report.revisionId??'aucune'} · règle {report.rule} · référence {report.referenceDate}</p>
 <h5>Expérience professionnelle globale (emploi, stage et freelance)</h5><Metrics value={report.globalProfessional}/>
 {Object.entries(report.globalByKind).map(([kind,value])=><details key={kind}><summary>{kinds[kind]}</summary><Metrics value={value}/></details>)}
 <h5>Par compétence publiée</h5>{report.skills.map(s=><article key={s.key}><h5>{s.label}</h5><Metrics value={s.professional}/>{Object.entries(s.byKind).map(([kind,value])=><details key={kind}><summary>{kinds[kind]}</summary><Metrics value={value}/></details>)}</article>)}
 {report.revisionId&&<fieldset disabled={busy}><legend>Rattachements humains aux missions</legend><p>Ne rattachez que la période justifiée de la compétence. Les dates proposées viennent de la mission et doivent être vérifiées. Une nouvelle publication exige une nouvelle vérification des rattachements.</p>
 <label htmlFor={`skill-period-${candidateId}-skill`}>Compétence publiée</label><select id={`skill-period-${candidateId}-skill`} value={skill} onChange={e=>{setSkill(e.target.value);setConfirmed(false);}}><option value="">Choisir</option>{selectable.filter(i=>claims[i.sourceIndex]?.field==='SKILL').map(i=><option key={i.sourceIndex} value={i.sourceIndex}>{i.value}</option>)}</select>
 <label htmlFor={`skill-period-${candidateId}-experience`}>Mission publiée</label><select id={`skill-period-${candidateId}-experience`} value={experience} onChange={e=>chooseExperience(e.target.value)}><option value="">Choisir</option>{selectable.filter(i=>claims[i.sourceIndex]?.field==='EXPERIENCE').map(i=><option key={i.sourceIndex} value={i.sourceIndex}>{i.value}</option>)}</select>
 {experience!==''&&<><p>Type : {kinds[period.kind]} · précision {period.precision}</p>{period.precision!=='UNKNOWN'&&<><label>Début justifié<input maxLength={10} value={period.start??''} onChange={e=>{setPeriod({...period,start:e.target.value});setConfirmed(false);}}/></label><label>Fin justifiée<input maxLength={10} value={period.end??''} onChange={e=>{setPeriod({...period,end:e.target.value});setConfirmed(false);}}/></label></>}
 {claims[Number(experience)]?.evidence.map((e,i)=><blockquote key={i}>{e.quote}<small> · {e.source}</small></blockquote>)}
 <label>Extrait exact de la preuve de mission<textarea maxLength={500} value={evidence} onChange={e=>{setEvidence(e.target.value);setConfirmed(false);}}/></label></>}
 <label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> Je confirme avoir vérifié le lien entre cette compétence, cette mission et la période retenue.</label>
 <button className="button" disabled={skill===''||experience===''||!evidence.trim()||!confirmed||links.length>=200} onClick={add}>Ajouter le rattachement</button>
 <ul>{links.map((l,i)=><li key={i}>{items.find(it=>it.sourceIndex===l.skillIndex)?.value} · mission {l.experienceIndex+1} · {l.period.start??'?'} – {l.period.end??'?'} · {kinds[l.period.kind]}<p>{l.evidence}</p><button className="text-button" onClick={()=>setLinks(links.filter((_,n)=>n!==i))}>Retirer le rattachement {i+1}</button></li>)}</ul>
 <label>Justification des rattachements<input maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label><button className="button primary" disabled={!reason.trim()} onClick={()=>void save()}>Enregistrer les rattachements</button></fieldset>}
 <button className="button" disabled={busy} onClick={()=>void audit(0)}>Historique des rattachements</button>{history.map(s=><details key={s.version}><summary>Version {s.version} · {s.createdAt}</summary><p>{s.reason} · auteur {s.actorId} · publication {s.revisionId}</p><ul>{s.links.map((l,i)=><li key={i}>Compétence {l.skillIndex+1} / mission {l.experienceIndex+1} : {l.period.start??'?'} – {l.period.end??'?'} ({l.period.precision}, {kinds[l.period.kind]}) · {l.evidence}</li>)}</ul></details>)}
 {cursor>0&&<button className="button" disabled={busy} onClick={()=>void audit(cursor-25)}>Historique plus récent</button>}{history.length===25&&<button className="button" disabled={busy} onClick={()=>void audit(cursor+25)}>Historique plus ancien</button>}
 </>}
 </section>;
}
