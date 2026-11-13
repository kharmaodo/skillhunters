import {useState} from 'react';
import {api,message} from './api';
type Summary={id:string;actorId:string;draftVersion:number;recipient:string;subject:string;status:string;approvedAt:string;attemptedAt:string|null;completedAt:string|null;retainedUntil:string;resultCode:string|null};
type Page={prepared:{draftVersion:number;subject:string;createdAt:string}|null;items:Summary[];nextCursor:string|null};
type Detail={summary:Summary;sender:string;replyTo:string;body:string;messageId:string;events:{sequence:number;status:string;code:string|null;occurredAt:string}[]};
const labels:Record<string,string>={APPROVED:'Validé',INVALIDATED:'Validation invalidée',SENDING:'Envoi en cours',ACCEPTED:'Accepté par SMTP — livraison et lecture non confirmées',UNCERTAIN:'Résultat incertain',FAILED:'Échec'};
const date=(value:string|null)=>value?new Date(value).toLocaleString():'—';
export function ContactHistory({candidateId}:{candidateId:string}){
 const [page,setPage]=useState<Page|null>(null),[detail,setDetail]=useState<Detail|null>(null),[status,setStatus]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const base=`/candidates/${candidateId}/contact-history`;
 async function load(filter:string,cursor?:string){setBusy(true);setError('');setDetail(null);setPage(null);try{const params=new URLSearchParams();if(filter)params.set('status',filter);if(cursor)params.set('cursor',cursor);setPage(await api<Page>(base+'?'+params));}catch(e){setError(message(e));}finally{setBusy(false);}}
 async function inspect(id:string){setBusy(true);setError('');setDetail(null);try{setDetail(await api<Detail>(base+'/'+id));}catch(e){setPage(null);setError(message(e));}finally{setBusy(false);}}
 return <section aria-label="Historique des contacts"><h3>Historique des contacts</h3>
 <p>Messages partagés avec les personnes habilitées du vivier. Vos brouillons non approuvés restent privés. Aucun suivi de lecture ni renvoi depuis cet historique.</p>
 <label>Statut du contact <select value={status} disabled={busy} onChange={e=>{setStatus(e.target.value);void load(e.target.value);}}><option value="">Tous les statuts</option>{Object.entries(labels).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
 <button className="button" disabled={busy} onClick={()=>void load(status)}>Actualiser l’historique des contacts</button>
 {error&&<p role="alert">{error}</p>}
 {page&&<><div role="status">{page.prepared&&<p>Votre brouillon préparé — version {page.prepared.draftVersion} : {page.prepared.subject} ({date(page.prepared.createdAt)})</p>}{!page.items.length&&<p>Aucun message validé conservé pour ce filtre.</p>}</div>
 <ul>{page.items.map(item=><li key={item.id}><strong>{labels[item.status]||item.status}</strong> · {date(item.approvedAt)}<p>{item.subject} — {item.recipient}</p><p className="fine">Auteur : {item.actorId} · Version {item.draftVersion} · Conservation au plus tard jusqu’au {date(item.retainedUntil)}</p><button className="button" disabled={busy} onClick={()=>void inspect(item.id)}>Consulter le message</button></li>)}</ul>
 {page.nextCursor&&<button className="button" disabled={busy} onClick={()=>void load(status,page.nextCursor!)}>Contacts plus anciens</button>}</>}
 {detail&&<article aria-label="Détail du contact"><h4>{detail.summary.subject}</h4><p>{labels[detail.summary.status]||detail.summary.status}</p><p>De : {detail.sender} · À : {detail.summary.recipient} · Réponse à : {detail.replyTo}</p>
 <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{detail.body}</pre><h5>Transitions enregistrées</h5><ol>{detail.events.map(event=><li key={event.sequence}>{date(event.occurredAt)} — {labels[event.status]||event.status}{event.code&&` (${event.code})`}</li>)}</ol><p className="fine">Référence du message : {detail.messageId}</p><button className="button" onClick={()=>setDetail(null)}>Fermer le détail</button></article>}
 </section>;
}
