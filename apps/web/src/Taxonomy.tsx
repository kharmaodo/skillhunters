import { useEffect, useState } from 'react';
import { api, type Session } from './api';
type Skill = { id: string; name: string; category: string; aliases: string[] };
type Dictionary = { version: number; skills: Skill[] };
type Event = { version: number; actorId: string | null; action: string; reason: string; createdAt: string };
export function Taxonomy({ session, failed }: { session: Session; failed: (e: unknown) => void }) {
  const [data, setData] = useState<Dictionary | null>(null);
  const [id, setId] = useState(''); const [name, setName] = useState(''); const [category, setCategory] = useState('');
  const [aliases, setAliases] = useState(''); const [reason, setReason] = useState(''); const [target, setTarget] = useState('');
  const [confirmed, setConfirmed] = useState(false); const [busy, setBusy] = useState(false); const [notice, setNotice] = useState('');
  const [events, setEvents] = useState<Event[]>([]); const [cursor, setCursor] = useState(0);
  async function load() { setBusy(true); try { setData(await api<Dictionary>('/taxonomy')); select(); setNotice('Dictionnaire actualisé.'); } catch(e) { failed(e); } finally { setBusy(false); } }
  useEffect(() => { void load(); }, []);
  function select(s?: Skill) { setId(s?.id ?? ''); setName(s?.name ?? ''); setCategory(s?.category ?? ''); setAliases(s?.aliases.join('\n') ?? ''); setReason(''); setTarget(''); setConfirmed(false); setNotice(''); }
  async function save(merge: boolean) {
    if (!data) return; setBusy(true); setNotice('');
    try {
      const path=merge?`/taxonomy/skills/${id}/merge`:id?`/taxonomy/skills/${id}`:'/taxonomy/skills';
      const body=merge?{ targetId: target, confirmed, reason }:{ name, category, aliases: aliases.split('\n').map(a=>a.trim()).filter(Boolean), reason };
      setData(await api<Dictionary>(path,{ method: merge || !id?'POST':'PUT', headers: { 'If-Match': `"${data.version}"` }, body: JSON.stringify(body) },session.csrfToken));
      select(); setEvents([]); setNotice(merge?'Fusion enregistrée. Les publications antérieures sont conservées.':'Compétence enregistrée.');
    } catch(e) { failed(e); } finally { setBusy(false); }
  }
  async function history(offset: number) { setBusy(true); try { setEvents(await api<Event[]>(`/taxonomy/history?cursor=${offset}`)); setCursor(offset); } catch(e) { failed(e); } finally { setBusy(false); } }
  return <section className="taxonomy"><p className="eyebrow">Administration</p><h1>Compétences et synonymes</h1><p className="muted">Une correspondance exacte unifie les appellations. Java, JavaScript et Spring Boot restent distincts, sauf fusion explicitement décidée par un administrateur.</p>
    <button className="button" disabled={busy} onClick={()=>void load()}>Actualiser le dictionnaire</button>
    {notice && <p role="status">{notice}</p>}
    {data && <><p>Version {data.version} · {data.skills.length} compétences (maximum 1 000)</p><div className="columns"><section className="card"><h2>Dictionnaire</h2><button className="button" disabled={busy} onClick={()=>select()}>Nouvelle compétence</button>
      <ul className="proposal-list">{data.skills.map(s=><li key={s.id}><button className="text-button" disabled={busy} onClick={()=>select(s)}>{s.name}</button><p>{s.category} · Alias : {s.aliases.join(', ') || 'aucun'}</p></li>)}</ul>{!data.skills.length && <p>Aucune compétence définie.</p>}</section>
      <section className="card"><h2>{id?'Modifier la compétence':'Créer une compétence'}</h2><form onSubmit={e=>{e.preventDefault();void save(false);}}><fieldset disabled={busy}><legend>Appellations validées</legend>
        <label htmlFor="skill-name">Nom canonique</label><input id="skill-name" required maxLength={200} value={name} onChange={e=>setName(e.target.value)}/>
        <label htmlFor="skill-category">Catégorie</label><input id="skill-category" required maxLength={100} value={category} onChange={e=>setCategory(e.target.value)}/>
        <label htmlFor="skill-aliases">Alias (un par ligne, 50 maximum)</label><textarea id="skill-aliases" rows={4} value={aliases} onChange={e=>setAliases(e.target.value)}/>
        <p className="fine">La casse et les espaces sont ignorés. Un alias ne peut appartenir qu’à une compétence. Ne recopiez pas le nom canonique parmi ses alias.</p>
        <label htmlFor="skill-reason">Justification</label><input id="skill-reason" required maxLength={500} value={reason} onChange={e=>{setReason(e.target.value);setConfirmed(false);}}/>
        <button className="button primary" type="submit">Enregistrer la compétence</button></fieldset></form>
      {id && <fieldset disabled={busy}><legend>Fusionner {data.skills.find(s=>s.id===id)?.name}</legend><p>La source disparaîtra du dictionnaire courant. Ses appellations seront transférées vers la cible. Les anciennes versions resteront consultables.</p>
        <label htmlFor="skill-target">Compétence cible</label><select id="skill-target" value={target} onChange={e=>{setTarget(e.target.value);setConfirmed(false);}}><option value="">Choisir la cible</option>{data.skills.filter(s=>s.id!==id).map(s=><option value={s.id} key={s.id}>{s.name}</option>)}</select>
        <label><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/> Je confirme la fusion vers {data.skills.find(s=>s.id===target)?.name ?? 'la cible à choisir'}.</label>
        <button className="button" disabled={!target || !confirmed || !reason.trim()} onClick={()=>void save(true)}>Confirmer la fusion</button></fieldset>}</section></div>
      <section className="card"><h2>Historique du dictionnaire</h2><button className="button" disabled={busy} onClick={()=>void history(0)}>Charger l’historique</button>
        <ul>{events.map(e=><li key={e.version}>Version {e.version} · {e.action} · {e.reason} · {e.createdAt} · auteur {e.actorId ?? 'initialisation'}</li>)}</ul>
        {cursor>0 && <button className="button" disabled={busy} onClick={()=>void history(cursor-25)}>Plus récentes</button>}{events.length===25 && <button className="button" disabled={busy} onClick={()=>void history(cursor+25)}>Plus anciennes</button>}</section></>}
  </section>;
}
