import { useState } from 'react';
import { api, message } from './api';
type Event={sequence:number;id:string;actorId:string|null;action:string;objectId:string|null;poolId:string|null;purpose:string|null;occurredAt:string};
type Page={items:Event[];nextCursor:number|null};
export function AuditLog(){
 const [page,setPage]=useState<Page|null>(null),[pool,setPool]=useState(''),[action,setAction]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function load(before?:number){setBusy(true);setError('');setPage(null);try{const params=new URLSearchParams();if(pool)params.set('poolId',pool);if(action)params.set('action',action);if(before)params.set('before',String(before));setPage(await api<Page>('/audit-events?'+params));}catch(e){setError(message(e));}finally{setBusy(false);}}
 return <section aria-label="Journal de conformité"><h1>Journal de conformité</h1><p>Événements techniques minimaux. Chaque consultation de ce journal est elle-même tracée.</p>
 <label>Identifiant du vivier (facultatif)<input value={pool} disabled={busy} onChange={e=>{setPool(e.target.value);setPage(null);}}/></label>
 <label>Action exacte (facultative)<input value={action} disabled={busy} placeholder="CANDIDATE_READ" onChange={e=>{setAction(e.target.value);setPage(null);}}/></label>
 <button className="button" disabled={busy} onClick={()=>void load()}>Consulter le journal</button>{error&&<p role="alert">{error}</p>}
 {page&&<><p role="status">{page.items.length} événement(s) sur cette page.</p><ol className="proposal-list">{page.items.map(event=><li key={event.id} style={{overflowWrap:'anywhere'}}><strong>{event.action}</strong><p>{new Date(event.occurredAt).toLocaleString()} · Acteur : {event.actorId||'Non authentifié'}</p><p>Objet : {event.objectId||'Sans objet'} · Vivier : {event.poolId||'Global'}</p><p>Finalité déclarée : {event.purpose||'Non disponible'}</p></li>)}</ol>{page.nextCursor!==null&&<button className="button" disabled={busy} onClick={()=>void load(page.nextCursor!)}>Événements précédents</button>}</>}
 </section>;
}
