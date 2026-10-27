"use strict";
(() => {
  const $ = id => document.getElementById(id);
  let entries = [], editing;
  async function request(body) {
    const response = await fetch('/local/presentation/library', body ? {
      method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
    } : {});
    const data = await response.json();
    if (!response.ok) throw Error(data.message || 'Saved recordings unavailable');
    return data;
  }
  function text(tag, value) { const el=document.createElement(tag); el.textContent=value; return el; }
  function render() {
    const query=$('library-search').value.toLocaleLowerCase(), sort=$('library-sort').value;
    const filtered=entries.filter(m=>[m.name,m.description,m.source_mode,m.display_date].some(v=>String(v||'').toLocaleLowerCase().includes(query)));
    filtered.sort((a,b)=>String(a[sort]||'').localeCompare(String(b[sort]||'')) * (['saved_at','started_at'].includes(sort)?-1:1));
    $('library-rows').replaceChildren();
    for (const m of filtered) {
      const row=text('section',''); row.className='library-row';
      row.append(text('h2',m.name),text('p',`Actual Start: ${m.started_at} · Saved at: ${m.saved_at}`),text('p',`Duration: ${Math.max(0,Math.round((Date.parse(m.cutoff_at)-Date.parse(m.started_at))/1000))} seconds · Source: ${m.source_mode}`),text('p',`Total parties during recording: ${m.total_parties}`),text('p',`Parties included in saved recording by operator: ${m.included.join(', ')} (${m.included.length})`));
      if(m.display_date) row.append(text('p',`Descriptive date: ${m.display_date}`));
      if(m.description) row.append(text('p',m.description));
      row.append(text('small','Unverified presentation evidence remains marked. Rendering opportunities do not prove OS scanout.'));
      const edit=text('button','Edit metadata'); edit.onclick=()=>{editing=m.id;$('library-name').value=m.name;$('library-date').value=m.display_date||'';$('library-description').value=m.description||'';$('library-edit-status').textContent='';$('library-edit').showModal();};
      const del=text('button','Delete');del.onclick=async()=>{if(!confirm(`Delete “${m.name}” from the library? Operational GNSS and SOS remain retained. Sealed evidence storage is retained.`))return;del.disabled=true;try{await request({id:m.id,action:'delete',confirmed:true});await load();$('library-status').textContent='Recording removed from library.';}catch(e){$('library-status').textContent=e.message;}finally{del.disabled=false;}};
      row.append(edit,del);$('library-rows').append(row);
    }
    $('library-status').textContent=filtered.length?`${filtered.length} saved recordings`:(entries.length?'No matching recordings.':'No saved recordings yet. Use Save in the Recording strip.');
  }
  async function load(){ $('library-status').textContent='Loading saved recordings…';try{entries=await request();render();}catch(e){$('library-rows').replaceChildren();$('library-status').textContent=e.message;} }
  $('open-library').onclick=()=>{$('saved-library').showModal();load();};
  $('library-search').oninput=render;$('library-sort').onchange=render;
  $('library-edit-form').onsubmit=async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;try{await request({id:editing,action:'edit',name:$('library-name').value,display_date:$('library-date').value,description:$('library-description').value});$('library-edit').close();await load();$('library-status').textContent='Metadata saved.';}catch(e){$('library-edit-status').textContent=e.message;}finally{button.disabled=false;}};
})();
