import {useState} from 'react';
import {api,message,ApiError,type Session} from './api';
type Status={sourceId:string;expiresAt:string|null;holdVersion:number;holdUntil:string|null;holdReason:string|null;erasureState:string;completedAt:string|null;resultCode:string|null;categories:string[]};
export function Retention({id,session,onErased}:{id:string;session:Session;onErased:()=>void}){
 const [state,setState]=useState<Status|null>(null),[until,setUntil]=useState(''),[reason,setReason]=useState(''),[confirmed,setConfirmed]=useState(false),[eraseConfirmed,setEraseConfirmed]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const base=`/imports/${id}/retention`;
 async function run(task:()=>Promise<Status>){setBusy(true);setError('');try{const s=await task();setState(s);if(s.erasureState!=='LIVE')onErased();setConfirmed(false);setEraseConfirmed(false);}catch(e){setState(null);setError(message(e));if(e instanceof ApiError&&e.code==='ERASURE_LEDGER_UNAVAILABLE')onErased();}finally{setBusy(false);}}
 function hold(release:boolean){if(!state)return;void run(()=>api<Status>(base+'/hold',{method:'PUT',headers:{'If-Match':`"${state.holdVersion}"`},body:JSON.stringify({until:release?null:new Date(until).toISOString(),reason,confirmed})},session.csrfToken));}
 return <section aria-label="Conservation et effacement"><h4>Conservation et effacement</h4><p>Accès conformité requis. Un gel préserve les données sans prolonger leur accès courant.</p>
 <button className="button" disabled={busy} onClick={()=>void run(()=>api<Status>(base))}>Consulter la conservation</button>
 {error&&<p role="alert">{error}</p>}
 {state&&<><p role="status">{state.erasureState==='COMPLETE'?'Suppression terminée.':state.erasureState==='PENDING'?'Suppression en attente ou en reprise.':'Source conservée.'}</p>
 {state.resultCode==='ERASURE_RETRY_REQUIRED'&&<p>Stockage ou journal indisponible : la purge sera retentée, les accès restent bloqués.</p>}
 {state.completedAt&&<p>Terminé le {new Date(state.completedAt).toLocaleString()}.</p>}
 {state.erasureState==='LIVE'&&<><p>Échéance initiale : {state.expiresAt?new Date(state.expiresAt).toLocaleString():'—'}</p><p>Gel : {state.holdUntil?new Date(state.holdUntil).toLocaleString():'Aucun'} · {state.holdReason}</p>
 <label>Fin du gel (au plus 365 jours)<input type="datetime-local" value={until} disabled={busy} onChange={e=>{setUntil(e.target.value);setConfirmed(false);}}/></label>
 <label>Motif du gel ou de sa levée<input maxLength={1000} value={reason} disabled={busy} onChange={e=>{setReason(e.target.value);setConfirmed(false);}}/></label>
 <p className="fine">Décrivez la nécessité et la référence de décision, sans recopier de données du CV.</p>
 <label><input type="checkbox" checked={confirmed} disabled={busy} onChange={e=>setConfirmed(e.target.checked)}/>J’approuve cette exception motivée et son échéance, ou sa levée.</label>
 <button className="button" disabled={busy||!confirmed||!reason.trim()||!until} onClick={()=>hold(false)}>Approuver le gel borné</button>
 <button className="button" disabled={busy||!confirmed||!reason.trim()||!state.holdVersion} onClick={()=>hold(true)}>Lever le gel</button>
 <p>L’effacement retire cet original, ses révisions et tous ses dérivés actifs. Les autres sources liées gardent leurs propres échéances. Un gel actif doit être levé préalablement.</p>
 <label><input type="checkbox" checked={eraseConfirmed} disabled={busy} onChange={e=>setEraseConfirmed(e.target.checked)}/>Je confirme l’effacement définitif de cette source et de ses dérivés.</label>
 <button className="button" disabled={busy||!eraseConfirmed} onClick={()=>void run(()=>api<Status>(base+'/erase',{method:'POST',body:JSON.stringify({confirmed:true})},session.csrfToken))}>Demander l’effacement définitif</button></>}
 </>}
 </section>;
}
type Proof={sourceId:string;kind:string;state:string;requestedAt:string;completedAt:string|null;resultCode:string|null};
export function ErasureProofs({poolId}:{poolId:string}){
 const [page,setPage]=useState<{items:Proof[];nextCursor:string|null}|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 async function load(cursor?:string){setBusy(true);setError('');setPage(null);try{setPage(await api(`/pools/${poolId}/erasures`+(cursor?'?cursor='+cursor:'')));}catch(e){setError(message(e));}finally{setBusy(false);}}
 return <section aria-label="Suivi des effacements"><h3>Suivi des effacements</h3><p>Preuves minimales, sans nom, contact ni contenu de CV. Accès conformité requis.</p><button className="button" disabled={busy} onClick={()=>void load()}>Actualiser les effacements</button>{error&&<p role="alert">{error}</p>}{page&&<><ul>{page.items.map(p=><li key={p.sourceId}>{p.state==='COMPLETE'?'Terminé':'En attente / reprise'} · {p.kind} · {p.sourceId}<p>{new Date(p.requestedAt).toLocaleString()} {p.completedAt&&' → '+new Date(p.completedAt).toLocaleString()}</p>{p.resultCode==='ERASURE_RETRY_REQUIRED'&&<p>Reprise automatique attendue.</p>}</li>)}</ul>{page.nextCursor&&<button className="button" disabled={busy} onClick={()=>void load(page.nextCursor!)}>Effacements suivants</button>}</>}</section>;
}
