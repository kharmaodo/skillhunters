import {useState} from 'react';
import {api,ApiError,message,type Session} from './api';
type State={candidateId:string;version:number;policyId:string;source:string;purpose:string;basisCode:string;justification:string|null;noticeVersion:string|null;noticeEvidence:string|null;notifiedAt:string|null;opposed:boolean};
type Policy={id:string;label:string;purpose:string;basisCode:string};
type History={items:State[];nextCursor:number|null};
export function Governance({candidateId,poolId,contactsVisible,session,failed}:{candidateId:string;poolId:string;contactsVisible:boolean;session:Session;failed:(e:unknown)=>void}){
 const [state,setState]=useState<State|null>(null),[policies,setPolicies]=useState<Policy[]>([]),[history,setHistory]=useState<History|null>(null),[link,setLink]=useState<{url:string;expiresAt:string}|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[confirm,setConfirm]=useState(false);
 const base=`/candidates/${candidateId}`,compliance=session.roles.includes('COMPLIANCE');
 async function run(task:()=>Promise<void>){setBusy(true);setError('');setNotice('');setLink(null);try{await task();}catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 async function load(){await run(async()=>{const [s,p]=await Promise.all([api<State>(base+'/governance'),api<Policy[]>(`/pools/${poolId}/import-policies`)]);setState(s);setPolicies(p);setHistory(null);});}
 async function save(){if(!state)return;await run(async()=>{setState(await api<State>(base+'/governance',{method:'PUT',headers:{'If-Match':`"${state.version}"`},body:JSON.stringify({policyId:state.policyId,source:state.source,justification:state.justification,noticeVersion:state.noticeVersion,noticeEvidence:state.noticeEvidence,notifiedAt:state.notifiedAt})},session.csrfToken));setHistory(null);setNotice('Dossier de traitement enregistré.');});}
 async function oppose(){if(!state||!confirm)return;await run(async()=>{setState(await api<State>(base+'/governance/opposition',{method:'POST',headers:{'If-Match':`"${state.version}"`}},session.csrfToken));setHistory(null);setConfirm(false);setNotice('Opposition enregistrée : le contact est bloqué, y compris depuis les anciens brouillons.');});}
 async function versions(before?:number){await run(async()=>setHistory(await api<History>(base+'/governance/history'+(before?'?before='+before:''))));}
 if(!compliance&&!contactsVisible)return null;
 return <section aria-label="Traitement et opposition"><h3>Traitement et opposition</h3><p>Une base déclarée ne vaut pas consentement. La justification et la preuve de notice doivent être vérifiées par la conformité.</p>
 {compliance&&<button className="button" disabled={busy} onClick={()=>void load()}>Charger le dossier de traitement</button>}
 {state&&compliance&&<fieldset disabled={busy}><legend>Version {state.version} · {state.opposed?'Opposition au contact enregistrée':'Aucune opposition enregistrée'}</legend>
 <label>Provenance<input maxLength={200} value={state.source} onChange={e=>setState({...state,source:e.target.value})}/></label>
 <label>Politique et base déclarée<select value={state.policyId} onChange={e=>setState({...state,policyId:e.target.value})}>{policies.map(p=><option key={p.id} value={p.id}>{p.label} · {p.basisCode} · {p.purpose}</option>)}</select></label>
 <label>Justification du traitement<textarea maxLength={1000} value={state.justification||''} onChange={e=>setState({...state,justification:e.target.value})}/></label>
 <label>Version de notice<input maxLength={80} value={state.noticeVersion||''} onChange={e=>setState({...state,noticeVersion:e.target.value})}/></label>
 <label>Référence de preuve de notice<input maxLength={500} value={state.noticeEvidence||''} onChange={e=>setState({...state,noticeEvidence:e.target.value})}/></label>
 <label>Date de notification (UTC)<input type="datetime-local" value={state.notifiedAt?new Date(state.notifiedAt).toISOString().slice(0,16):''} onChange={e=>setState({...state,notifiedAt:e.target.value?e.target.value+':00Z':null})}/></label>
 <button className="button" onClick={()=>void save()}>Enregistrer le traitement</button><button className="button" onClick={()=>void versions()}>Historique du traitement</button>
 {!state.opposed&&<><label><input type="checkbox" checked={confirm} onChange={e=>setConfirm(e.target.checked)}/>Je confirme avoir reçu une opposition au contact.</label><button className="button" disabled={!confirm} onClick={()=>void oppose()}>Enregistrer l’opposition</button></>}
 </fieldset>}
 {history&&<><ol>{history.items.map(s=><li key={s.version} style={{overflowWrap:'anywhere'}}>Version {s.version} · {s.basisCode} · {s.purpose}<p>{s.source} · {s.justification}</p><p>Notice : {s.noticeVersion} · Preuve : {s.noticeEvidence} · {s.notifiedAt}</p><p>{s.opposed?'Contact bloqué':'Aucune opposition enregistrée'}</p></li>)}</ol>{history.nextCursor!==null&&<button className="button" disabled={busy} onClick={()=>void versions(history.nextCursor!)}>Versions précédentes</button>}</>}
 {contactsVisible&&<><button className="button" disabled={busy} onClick={()=>void run(async()=>{const e=await api<{allowed:boolean;reason:string}>(base+'/contact-eligibility');setNotice(e.allowed?'Dossier de traitement complet. Aucun message n’a été envoyé.':message(new ApiError(409,e.reason)));})}>Vérifier la possibilité de contact</button>
 <button className="button" disabled={busy} onClick={()=>void run(async()=>setLink(await api(base+'/opposition-links',{method:'POST'},session.csrfToken)))}>Créer un lien d’opposition</button></>}
 {link&&<div><p>À copier dans le message. Valable jusqu’au {new Date(link.expiresAt).toLocaleString()}.</p><textarea aria-label="Lien d’opposition" readOnly value={link.url}/></div>}
 {notice&&<p role="status">{notice}</p>}{error&&<p role="alert">{error}</p>}
 </section>;
}
export function PublicOpposition(){
 const [token]=useState(()=>new URLSearchParams(window.location.hash.slice(1)).get('opposition')||'');
 const [confirmed,setConfirmed]=useState(false),[busy,setBusy]=useState(false),[done,setDone]=useState(false),[error,setError]=useState('');
 async function submit(){setBusy(true);setError('');try{await api('/public/contact-opposition',{method:'POST',body:JSON.stringify({token})});setDone(true);window.history.replaceState(null,'','/');}catch{setError('Ce lien est indisponible ou expiré. Contactez l’organisme qui vous a adressé le message.');}finally{setBusy(false);}}
 return <main className="login"><h1>Opposition au contact</h1>{done?<p role="status">Votre opposition au contact a été enregistrée.</p>:<><p>Confirmez que vous ne souhaitez plus être contacté au sujet de cette candidature. Aucun dossier candidat n’est affiché sur cette page.</p><label><input type="checkbox" checked={confirmed} disabled={busy} onChange={e=>setConfirmed(e.target.checked)}/>Je confirme mon opposition au contact.</label><button className="button" disabled={busy||!confirmed} onClick={()=>void submit()}>Confirmer mon opposition</button>{error&&<p role="alert">{error}</p>}</>}</main>;
}
