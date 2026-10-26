import { useEffect, useState } from 'react';
import { api, message, type Session } from './api';
type Weights={required:number;desired:number;experience:number;recency:number};
type Revision={poolId:string;version:number;name:string;weights:Weights;before:Weights|null;normalized:Record<string,number>;validatorId:string|null;validatedAt:string|null;reason:string};
type State={latest:Revision;canConfigure:boolean};
const labels:Record<keyof Weights,string>={required:'Compétences obligatoires',desired:'Compétences souhaitées',experience:'Exposition justifiée',recency:'Récence'};
const defaults:Weights={required:35,desired:15,experience:20,recency:10};
export function ScoringConfiguration({poolId,session,busy,selected,onSelect}:{poolId:string;session:Session;busy:boolean;selected:number;onSelect:(v:number)=>void}) {
 const [state,setState]=useState<State|null>(null);const [weights,setWeights]=useState<Weights>(defaults);const [name,setName]=useState('');const [reason,setReason]=useState('');
 const [history,setHistory]=useState<{items:Revision[];nextCursor:string|null}|null>(null);const [view,setView]=useState<Revision|null>(null);
 const [pending,setPending]=useState(false);const [error,setError]=useState('');const [notice,setNotice]=useState('');
 const base=`/pools/${poolId}/scoring-configurations`;const prefix=`weights-${poolId}`;
 async function load(){setPending(true);setError('');try{const s=await api<State>(base);setState(s);setWeights(s.latest.weights);setName(s.latest.name);setReason('');}catch(e){setError(message(e));}finally{setPending(false);}}
 useEffect(()=>{void load();},[poolId]);
 async function choose(version:number){setPending(true);setError('');try{const r=await api<Revision>(`${base}/${version}`);setView(r);onSelect(version);}catch(e){setError(message(e));}finally{setPending(false);}}
 async function events(cursor='0'){setPending(true);setError('');try{setHistory(await api(`${base}/history?cursor=${cursor}`));}catch(e){setError(message(e));}finally{setPending(false);}}
 async function save(){if(!state)return;setPending(true);setError('');setNotice('');try{const r=await api<Revision>(base,{method:'POST',headers:{'If-Match':`"${state.latest.version}"`},body:JSON.stringify({name,weights,reason})},session.csrfToken);setState({...state,latest:r});setReason('');setView(r);setHistory(null);onSelect(r.version);setNotice(`Version ${r.version} validée. Elle sera utilisée lors du prochain calcul ; les snapshots existants restent inchangés.`);}catch(e){setError(message(e));}finally{setPending(false);}}
 const total=Object.values(weights).reduce((a,b)=>a+b,0);
 return <section aria-label="Configuration du scoring"><h5>Poids du classement · version sélectionnée {selected}</h5>
 <p>La permission de configuration est réservée au responsable recrutement de ce vivier. Tous ses membres peuvent choisir une version validée. La qualité documentaire reste séparée.</p>
 {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 <fieldset disabled={busy||pending}><legend>Choisir une version pour le prochain calcul</legend>
 <button type="button" className="button" onClick={()=>void choose(0)}>Utiliser les poids initiaux</button>
 {state&&state.latest.version>0&&<button type="button" className="button" onClick={()=>void choose(state.latest.version)}>Utiliser la dernière version ({state.latest.version})</button>}
 <button type="button" className="button" onClick={()=>void events()}>Historique des poids</button>
 {history&&<><ul>{history.items.map(r=><li key={r.version}>v{r.version} · {r.name} · validée par {r.validatorId}<p>{r.reason}</p><p>Avant : {r.before?Object.values(r.before).join(' / '):'initial'} · Après : {Object.values(r.weights).join(' / ')} (obligatoires / souhaitées / exposition / récence)</p><button type="button" className="text-button" onClick={()=>void choose(r.version)}>Choisir la version {r.version}</button></li>)}</ul>{history.nextCursor&&<button type="button" className="button" onClick={()=>void events(history.nextCursor!)}>Historique suivant</button>}</>}
 {view&&<p>Version choisie : {view.name} · {Object.entries(view.weights).map(([k,v])=>`${labels[k as keyof Weights]} ${v}`).join(' ; ')}. Normalisation sur les groupes actifs lors du calcul.</p>}
 </fieldset>
 {state?.canConfigure&&<details><summary>Valider de nouveaux poids pour le poste</summary><p>Dernière version : {state.latest.version}. Les modifications créent une nouvelle version ; elles ne remplacent aucune version antérieure.</p>
 <form onSubmit={e=>{e.preventDefault();void save();}}><fieldset disabled={busy||pending}><legend>Nouvelle configuration</legend>
 <label htmlFor={`${prefix}-name`}>Nom du poste ou de la configuration</label><input id={`${prefix}-name`} required maxLength={100} value={name} onChange={e=>setName(e.target.value)}/>
 {(Object.keys(labels) as (keyof Weights)[]).map(k=><div key={k}><label htmlFor={`${prefix}-${k}`}>Poids : {labels[k]}</label><input id={`${prefix}-${k}`} type="number" min={0} max={10000} step={1} required value={weights[k]} onChange={e=>setWeights({...weights,[k]:Number(e.target.value)})}/><p className="fine">Part globale proposée : {total>0?(100*weights[k]/total).toFixed(2):'0'} %</p></div>)}
 <p>Entiers de 0 à 10 000, au moins un poids positif. Les groupes non demandés sont retirés et les autres renormalisés pour tous les profils. Aucun calcul n’est accepté si tous les groupes demandés ont un poids nul.</p>
 <label htmlFor={`${prefix}-reason`}>Motif de modification des poids</label><textarea id={`${prefix}-reason`} required maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/>
 <button className="button" disabled={total<=0}>Valider une nouvelle version des poids</button>
 <button type="button" className="text-button" onClick={()=>void load()}>Recharger et remplacer ma saisie</button>
 </fieldset></form></details>}
 </section>;
}
