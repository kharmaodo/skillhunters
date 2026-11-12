import {useState} from 'react';
import {api,message,type Session} from './api';
type Draft={version:number;recipient:string;subject:string;body:string;missingFields:string[]};
type Receipt={id:string;draftVersion:number;contentHash:string;sender:string;recipient:string;replyTo:string;subject:string;body:string;messageId:string;status:string;approvedAt:string;expiresAt:string;resultCode:string|null};
const statuses:Record<string,string>={APPROVED:'Approuvé — en attente de votre demande d’envoi',INVALIDATED:'Approbation invalidée — enregistrer et relire une nouvelle version',SENDING:'Tentative en cours — ne pas renvoyer',ACCEPTED:'Accepté par SMTP — livraison et lecture non confirmées',UNCERTAIN:'Résultat incertain — vérifier auprès de la messagerie avant toute nouvelle sollicitation',FAILED:'Échec avant transmission — aucun renvoi automatique'};
export function ContactDelivery({candidateId,draft,session,onBusy}:{candidateId:string;draft:Draft;session:Session;onBusy:(value:boolean)=>void}){
 const [confirmed,setConfirmed]=useState(false),[receipt,setReceipt]=useState<Receipt|null>(null),[busy,setBusy]=useState(false),[attempted,setAttempted]=useState(false),[error,setError]=useState('');
 const base=`/candidates/${candidateId}/contact-deliveries`;
 async function perform(task:()=>Promise<Receipt>){setBusy(true);onBusy(true);setError('');try{setReceipt(await task());}catch(e){setError(message(e));}finally{setBusy(false);onBusy(false);}}
 return <section aria-label="Validation et envoi du contact"><h5>Relire et approuver la version {draft.version}</h5><p>Destinataire exact : <strong>{draft.recipient}</strong></p>
 <p>La validation porte sur l’objet et le texte affichés ci-dessus. Tout changement nécessite un nouvel enregistrement et une nouvelle relecture.</p>
 <label><input type="checkbox" disabled={busy||!!receipt} checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/>J’ai relu le destinataire, l’objet et le texte exacts, vérifié les affirmations et le lien d’opposition.</label>
 {!receipt&&<button className="button" disabled={busy||!confirmed||draft.missingFields.length>0} onClick={()=>void perform(()=>api<Receipt>(base,{method:'POST',body:JSON.stringify({draftVersion:draft.version,recipient:draft.recipient,subject:draft.subject,body:draft.body,confirmed:true})},session.csrfToken))}>Approuver ce message exact</button>}
 {receipt&&<><p role="status">{statuses[receipt.status]||receipt.status}</p><p>Expéditeur : {receipt.sender} · Réponse à : {receipt.replyTo}</p><p>Validation valable jusqu’au {new Date(receipt.expiresAt).toLocaleString()}.</p>
 {receipt.status==='APPROVED'&&!attempted&&<button className="button primary" disabled={busy} onClick={()=>{setAttempted(true);void perform(()=>api<Receipt>(base+'/'+receipt.id+'/send',{method:'POST'},session.csrfToken));}}>Envoyer une seule fois le message approuvé</button>}
 <button className="button" disabled={busy} onClick={()=>void perform(()=>api<Receipt>(base+'/'+receipt.id))}>Vérifier le résultat sans renvoyer</button>
 <p className="fine" style={{overflowWrap:'anywhere'}}>Référence : {receipt.id} · Empreinte approuvée : {receipt.contentHash}</p></>}
 {error&&<p role="alert">{error}</p>}
 </section>;
}
