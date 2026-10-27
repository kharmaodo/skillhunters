import { ScoreReview } from './ScoreReview';
import { ScoringConfiguration } from './ScoringConfiguration';
import { useState } from 'react';
import { api, message, type Session } from './api';
type Criteria = { skill:string;importance:'REQUIRED'|'DESIRED';minimumMonths:number|null;maximumRecencyMonths:number|null };
type Query = {poolId:string;criteria:Criteria[];strict:boolean;experienceKinds:string[];referenceDate:string|null;dictionaryVersion:number|null;cursor:string|null;limit:number};
type Component = {criterionIndex:number;dimension:string;baseWeight:number;normalizedWeight:number;documentedValue:number;upperValue:number;contribution:number;upperContribution:number;certainty:string;sourceIndices:number[]};
type Score = {documented:number;upper:number;evidenceCoverage:number;quality:number;obligationsToVerify:boolean;components:Component[]};
type Item = {rank:number;fingerprint:string;score:Score;hit:{candidateId:string;displayName:string|null;professionalTitle:string|null;publicationId:string;sourceRevisionId:string;candidateVersion:number;periodLinksVersion:number|null;criteria:{criterion:{label:string};evidence:{original:string|null;validatedValue:string;citations:{quote:string;source:string}[]}[];periodLinks:{evidence:string;period:{start:string|null;end:string|null;kind:string}}[]}[]}};
type Page = {metadata:{searchId:string;createdAt:string;expiresAt:string;scanned:number;scoringRule:string;weightsVersion:string;halfLifeMonths:number;hybrid:boolean;model:string|null;retrievalStrategy:string;trace:{referenceDate:string;dictionaryVersion:number;strict:boolean}};items:Item[];nextCursor:number|null};
const dimensions:Record<string,string>={REQUIRED:'Compétence obligatoire',DESIRED:'Compétence souhaitée',EXPERIENCE:'Exposition justifiée',RECENCY:'Récence'};
const certainty:Record<string,string>={KNOWN:'Documentée',PARTIAL:'Partiellement connue',UNKNOWN:'Inconnue / non documentée'};
const number=(v:number)=>v.toLocaleString('fr-FR',{maximumFractionDigits:6});
export function ScoredResults({query,hybrid,minimumSimilarity,session,failed,open}:{query:Query;hybrid:boolean;minimumSimilarity:number;session:Session;failed:(e:unknown)=>void;open:(id:string)=>void}) {
 const [configurationVersion,setConfigurationVersion]=useState(0);
 const [page,setPage]=useState<Page|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState('');const [notice,setNotice]=useState('');
 async function create(){setBusy(true);setError('');setNotice('');setPage(null);try{setPage(await api<Page>('/search/scored',{method:'POST',body:JSON.stringify({query:{...query,cursor:null},hybrid,minimumSimilarity,configurationVersion})},session.csrfToken));}catch(e){setError(message(e));failed(e);}finally{setBusy(false);}}
 async function next(){if(!page||page.nextCursor===null)return;setBusy(true);setNotice('');setError('');try{setPage(await api<Page>(`/search/scored/${page.metadata.searchId}?cursor=${page.nextCursor}&limit=${query.limit}`));}catch(e){setPage(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 async function replay(item:Item){if(!page)return;setBusy(true);setNotice('');setError('');try{const r=await api<Item>(`/search/scored/${page.metadata.searchId}/candidates/${item.hit.candidateId}/recalculate`,{method:'POST'},session.csrfToken);setNotice(`Recalcul vérifié pour le rang ${r.rank} : ${number(r.score.documented)} / 100. Contributions et empreinte identiques au snapshot.`);}catch(e){setPage(null);setError(message(e));failed(e);}finally{setBusy(false);}}
 return <section aria-label="Classement documenté" className="human-review"><h4>Classer par correspondance documentée</h4>
 <p>Poids initiaux : obligatoires 35, souhaitées 15, exposition 20, récence 10 ; une configuration validée peut les remplacer pour le prochain calcul. Seuls les groupes demandés sont actifs ; leurs poids sont renormalisés pour toute la recherche. La récence utilise une demi-vie de 36 mois. Responsabilité, certification et secteur ne sont pas évalués dans cette version.</p>
 <p>Ce classement conserve tous les profils récupérés, y compris ceux à vérifier. Il ne représente pas une probabilité d’embauche. La similarité et la qualité documentaire ne modifient pas le score.</p>
 <ScoringConfiguration poolId={query.poolId} session={session} busy={busy} selected={configurationVersion} onSelect={v=>{setConfigurationVersion(v);setNotice('Poids sélectionnés pour le prochain calcul. Le classement affiché conserve sa version précédente.');}}/>
 <button className="button" disabled={busy} onClick={()=>void create()}>Calculer et figer le classement</button>
 <p className="fine">Parcours complet limité à 1 000 publications examinées : au-delà, la demande est refusée sans classement partiel. Snapshot privé valable au maximum 24 h, soumis aux droits et à la conservation des sources.</p>
 {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 {page&&<><p role="status">Classement figé · {page.metadata.scanned} publications examinées · {page.items.length} profil(s) sur cette page.</p>
 <p className="fine">Référence {page.metadata.trace.referenceDate} · dictionnaire v{page.metadata.trace.dictionaryVersion} · règle {page.metadata.scoringRule} · poids {page.metadata.weightsVersion}. Calcul du {new Date(page.metadata.createdAt).toLocaleString()} ; expiration {new Date(page.metadata.expiresAt).toLocaleString()}. Les mises à jour ultérieures des fiches ne modifient pas ce snapshot.</p>
 <p>Ordre : score documenté décroissant, puis identifiant en cas d’égalité. Les profils supprimés, expirés ou devenus inaccessibles sont retirés ; des rangs peuvent manquer. {page.metadata.trace.strict?'Le mode strict confirmé est appliqué.':'Le mode exploratoire conserve les obligations à vérifier.'}</p>
 {page.items.length===0&&<p>Aucun profil encore accessible dans ce classement.</p>}
 {page.items.map(item=><article className="card" key={item.hit.candidateId}><h4>Rang {item.rank} · {item.hit.displayName||'Nom inconnu'} · {item.hit.professionalTitle||'Titre inconnu'}</h4>
 <p><strong>Score documenté : {number(item.score.documented)} / 100</strong> · Borne haute : {number(item.score.upper)} / 100</p>
 <p>{item.score.obligationsToVerify?'Obligations à vérifier':'Obligations documentées'} · Couverture des composantes connues : {number(item.score.evidenceCoverage)} %</p>
 <p>Qualité documentaire Q : {number(item.score.quality)} % — indicateur distinct, jamais utilisé pour classer. {item.score.quality<100?'Relecture recommandée des preuves ou périodes incomplètes.':''}</p>
 <details><summary>Détail des contributions et preuves du rang {item.rank}</summary><ul>{item.score.components.map((c,i)=><li key={i}><strong>{item.hit.criteria[c.criterionIndex].criterion.label} · {dimensions[c.dimension]}</strong><p>{certainty[c.certainty]} · poids {number(c.normalizedWeight*100)} % · valeur documentée {number(c.documentedValue)} · contribution {number(c.contribution)} à {number(c.upperContribution)} points</p></li>)}</ul>
 {item.hit.criteria.map((c,i)=><div key={i}><h5>Preuves : {c.criterion.label}</h5>{!c.evidence.length&&<p>Aucune preuve publiée correspondante : l’aptitude reste à vérifier.</p>}{c.evidence.map((e,j)=><div key={j}><p>Valeur validée : {e.validatedValue} · original : {e.original||'inconnu'}</p>{e.citations.map((proof,k)=><blockquote key={k}>{proof.quote}<small> · {proof.source}</small></blockquote>)}</div>)}{c.periodLinks.map((link,j)=><p key={j}>{link.period.start||'?'} – {link.period.end||'?'} · {link.period.kind} · {link.evidence}</p>)}</div>)}
 <p className="fine">Publication {item.hit.publicationId} · source {item.hit.sourceRevisionId} · fiche v{item.hit.candidateVersion} · rattachements v{item.hit.periodLinksVersion??'aucun'} · empreinte {item.fingerprint}</p></details>
 <button className="button" disabled={busy} onClick={()=>void replay(item)}>Vérifier le recalcul du rang {item.rank}</button> <button className="button" disabled={busy} onClick={()=>open(item.hit.candidateId)}>Ouvrir la fiche actuelle du rang {item.rank}</button>
 <ScoreReview searchId={page.metadata.searchId} candidateId={item.hit.candidateId} labels={item.hit.criteria.map(c=>c.criterion.label)} originalScore={item.score.documented} createdAt={page.metadata.createdAt} session={session} open={()=>open(item.hit.candidateId)}/>
 </article>)}
 {page.nextCursor!==null&&<button className="button" disabled={busy} onClick={()=>void next()}>Page suivante du classement</button>}
 </>}
 </section>;
}
