import { useState } from 'react';
import { api, message, type Session } from './api';
export const trackingStatuses:Record<string,string>={NEW:'Nouveau',TO_REVIEW:'À étudier',SHORTLISTED:'Présélectionné',CONTACTED:'Contacté',INTERVIEW:'Entretien',CLOSED:'Clôturé'};
type Values={status:string;note:string;tags:string[];lists:string[]};
type State={candidateId:string;version:number;values:Values};
type Event={version:number;actorId:string;occurredAt:string;reason:string;before:Values;after:Values};
type Page<T>={items:T[];nextCursor:string|null};
type Row={candidateId:string;displayName:string|null;professionalTitle:string|null;tracking:State};
function Summary({values}:{values:Values}){return <><p>Statut : {trackingStatuses[values.status]}</p><p>Listes : {values.lists.join(', ')||'aucune'} · Tags : {values.tags.join(', ')||'aucun'}</p><p style={{whiteSpace:'pre-wrap'}}>Note : {values.note||'aucune'}</p></>;}
export function TrackingEditor({candidateId,session,failed}:{candidateId:string;session:Session;failed:(e:unknown)=>void}){
 const [state,setState]=useState<State|null>(null);const [status,setStatus]=useState('NEW');const [note,setNote]=useState('');const [tags,setTags]=useState('');const [lists,setLists]=useState('');const [reason,setReason]=useState('');const [professional,setProfessional]=useState(false);
 const [history,setHistory]=useState<Page<Event>|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [notice,setNotice]=useState('');const prefix=`tracking-${candidateId}`;
 const base=`/candidates/${candidateId}/tracking`;
 function apply(s:State){setState(s);setStatus(s.values.status);setNote(s.values.note);setTags(s.values.tags.join(', '));setLists(s.values.lists.join(', '));setReason('');setProfessional(false);}
 async function load(){setBusy(true);setError('');setNotice('');try{apply(await api<State>(base));setHistory(null);}catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 async function audit(cursor='0'){setBusy(true);setError('');try{setHistory(await api<Page<Event>>(`${base}/history?cursor=${cursor}`));}catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 const labels=(s:string)=>s.split(',').map(v=>v.trim()).filter(Boolean);
 async function save(){if(!state)return;setBusy(true);setError('');setNotice('');try{apply(await api<State>(base,{method:'PUT',headers:{'If-Match':`"${state.version}"`},body:JSON.stringify({status,note,tags:labels(tags),lists:labels(lists),reason,professionalOnly:professional})},session.csrfToken));setHistory(null);setNotice('Suivi personnel enregistré. Aucun score ni contact modifié.');}catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 return <details><summary>Organiser ce candidat</summary><section className="candidate-tracking" aria-label="Suivi personnel du candidat"><h4>Mon suivi</h4><p>Privé à votre compte, accessible uniquement tant que vous avez accès au vivier et que le document est conservé. Aucun statut ne change automatiquement à la suite d’une recherche ou d’un score.</p>
 <button className="button" disabled={busy} onClick={()=>void load()}>Charger ou remplacer ma saisie de suivi</button>{error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 {state&&<><p>Version du suivi : {state.version}</p><form onSubmit={e=>{e.preventDefault();void save();}}><fieldset disabled={busy}><legend>Choix du recruteur</legend>
 <label htmlFor={`${prefix}-status`}>Statut de suivi</label><select id={`${prefix}-status`} value={status} onChange={e=>{setStatus(e.target.value);setProfessional(false);}}>{Object.entries(trackingStatuses).map(([v,l])=><option key={v} value={v}>{l}</option>)}</select>
 <p className="fine">« Contacté » consigne un contact effectué ; ce choix n’envoie aucun message. Les changements de statut sont réversibles et motivés.</p>
 <label htmlFor={`${prefix}-lists`}>Listes nommées (séparées par des virgules)</label><input id={`${prefix}-lists`} value={lists} maxLength={810} onChange={e=>{setLists(e.target.value);setProfessional(false);}}/>
 <label htmlFor={`${prefix}-tags`}>Tags professionnels (séparés par des virgules)</label><input id={`${prefix}-tags`} value={tags} maxLength={810} onChange={e=>{setTags(e.target.value);setProfessional(false);}}/>
 <p className="fine">10 listes et 10 tags maximum, 80 caractères chacun. Les noms sont normalisés en minuscules. Retirez un nom pour sortir le candidat de cette liste ; réutilisez-le sur une autre fiche pour regrouper les profils.</p>
 <label htmlFor={`${prefix}-note`}>Note factuelle</label><textarea id={`${prefix}-note`} value={note} maxLength={2000} onChange={e=>{setNote(e.target.value);setProfessional(false);}}/>
 <label htmlFor={`${prefix}-reason`}>Motif du changement de suivi</label><input id={`${prefix}-reason`} value={reason} required maxLength={500} onChange={e=>{setReason(e.target.value);setProfessional(false);}}/>
 <p>Ne renseignez ni âge, genre, origine, santé, religion, opinions, situation familiale, ni coordonnées. Décrivez uniquement des faits professionnels vérifiables, sans appréciation de personnalité.</p>
 <label><input type="checkbox" checked={professional} onChange={e=>setProfessional(e.target.checked)}/>Je confirme que le suivi contient uniquement des faits professionnels, sans attribut protégé.</label>
 <button className="button" disabled={!professional}>Enregistrer mon suivi</button></fieldset></form>
 <button className="button" disabled={busy} onClick={()=>void audit()}>Historique de mon suivi</button>
 {history&&<><ol>{history.items.map(e=><li key={e.version}><p>Version {e.version} · {new Date(e.occurredAt).toLocaleString()} · {e.reason}</p><p className="fine">Auteur : {e.actorId}</p><details><summary>Avant le changement {e.version}</summary><Summary values={e.before}/></details><details><summary>Après le changement {e.version}</summary><Summary values={e.after}/></details></li>)}</ol>{history.nextCursor&&<button className="button" disabled={busy} onClick={()=>void audit(history.nextCursor!)}>Suivi précédent</button>}</>}</>}
 </section></details>;
}
export function TrackingLists({poolId,failed,open}:{poolId:string;failed:(e:unknown)=>void;open:(id:string)=>void}){
 const [status,setStatus]=useState('');const [list,setList]=useState('');const [tag,setTag]=useState('');const [page,setPage]=useState<Page<Row>|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const prefix=`tracking-list-${poolId}`;
 async function load(cursor='0'){setBusy(true);setError('');try{const q=new URLSearchParams({cursor});if(status)q.set('status',status);if(list.trim())q.set('list',list.trim());if(tag.trim())q.set('tag',tag.trim());setPage(await api<Page<Row>>(`/pools/${poolId}/tracking?${q}`));}catch(e){setPage(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 return <section className="candidate-tracking" aria-label="Mes listes de candidats"><h3>Mes listes et statuts</h3><p>Filtrez votre suivi dans ce vivier. Une fiche sans suivi enregistré est « Nouveau ». Les listes sont créées en les nommant dans le suivi d’une fiche.</p>
 <form onSubmit={e=>{e.preventDefault();void load();}}><fieldset disabled={busy}><legend>Filtrer mon suivi</legend><label htmlFor={`${prefix}-status`}>Filtre par statut</label><select id={`${prefix}-status`} value={status} onChange={e=>{setStatus(e.target.value);setPage(null);}}><option value="">Tous</option>{Object.entries(trackingStatuses).map(([v,l])=><option key={v} value={v}>{l}</option>)}</select>
 <label htmlFor={`${prefix}-list`}>Nom exact de la liste</label><input id={`${prefix}-list`} maxLength={80} value={list} onChange={e=>{setList(e.target.value);setPage(null);}}/>
 <label htmlFor={`${prefix}-tag`}>Tag exact</label><input id={`${prefix}-tag`} maxLength={80} value={tag} onChange={e=>{setTag(e.target.value);setPage(null);}}/>
 <button className="button">Afficher mon suivi</button></fieldset></form>{error&&<p role="alert">{error}</p>}
 {page&&<><p role="status">{page.items.length} candidat(s) sur cette page.</p>{page.items.map(r=><article className="card" key={r.candidateId}><h4>{r.displayName||'Nom inconnu'} · {r.professionalTitle||'Titre inconnu'}</h4><Summary values={r.tracking.values}/><button className="button" disabled={busy} onClick={()=>open(r.candidateId)}>Ouvrir cette fiche suivie</button></article>)}{page.nextCursor&&<button className="button" disabled={busy} onClick={()=>void load(page.nextCursor!)}>Suivi suivant</button>}</>}
 </section>;
}
