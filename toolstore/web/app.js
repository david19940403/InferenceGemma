'use strict';
const $ = id => document.getElementById(id);
const nameOf = t => t.name || t.nombre;
const descOf = t => t.description || t.descripcion;
let token = '', tools = [], loaded = false, editing = null, deleting = null;
let params = [], schemaBase = {}, jsonMode = false, saving = false, initialForm = '', toastTimer;
try { token = sessionStorage.getItem('toolstore-token') || ''; } catch {}

function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
function icon(name) {
  const n = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', '#i-' + name); n.append(use); n.setAttribute('aria-hidden', 'true'); return n;
}
function button(label, cls, handler, glyph) {
  const n = el('button', undefined, cls); n.type = 'button';
  if (glyph) n.append(icon(glyph));
  if (label) n.append(document.createTextNode(label));
  n.onclick = handler; return n;
}
function iconButton(label, glyph, handler, extra = '') {
  const n = button('', 'icon-button ' + extra, handler, glyph); n.title = label; n.setAttribute('aria-label', label); return n;
}
function toast(message, error = false) {
  clearTimeout(toastTimer); $('toast').classList.toggle('error', error);
  $('toast').querySelector('span').textContent = message; $('toast').hidden = false;
  toastTimer = setTimeout(() => $('toast').hidden = true, 4200);
}
function formError(id, message) { $(id).textContent = message; $(id).hidden = !message; }
async function api(path, options = {}, access = token) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const res = await fetch(path, { ...options, signal: controller.signal, headers: { 'Content-Type': 'application/json', ...(access ? { Authorization: 'Bearer ' + access } : {}) } });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const e = new Error(res.status === 401 ? 'El token no es válido. Revisa el acceso al catálogo.' : body.error || 'No se pudo completar la solicitud.');
      e.status = res.status; throw e;
    }
    return res.status === 204 ? null : res.json();
  } catch (e) {
    if (e.name === 'AbortError') throw Error('El catálogo tardó demasiado en responder. Intenta de nuevo.');
    if (e instanceof TypeError) throw Error('No se pudo conectar con el catálogo. Comprueba que el servicio esté activo.');
    throw e;
  } finally { clearTimeout(timeout); }
}
function showView(view) {
  document.querySelectorAll('.view').forEach(n => n.hidden = n.id !== view + '-view');
  document.querySelectorAll('.nav-item').forEach(n => { const active = n.dataset.view === view; n.classList.toggle('active', active); if (active) n.setAttribute('aria-current', 'page'); else n.removeAttribute('aria-current'); });
  $('breadcrumb-current').textContent = { catalog: 'Acciones', hosts: 'Hosts del catálogo', test: 'Probar selección' }[view];
  window.scrollTo({ top: 0, behavior: 'instant' });
}
function accessState(ok) {
  loaded = ok; $('access-label').textContent = ok ? 'Acceso conectado' : 'Configurar acceso';
  $('access-button').classList.toggle('connected', ok);
  $('export-actions').disabled = !ok || tools.length === 0;
}
function empty(container, heading, description, glyph = 'grid', action) {
  const box = el('div', undefined, 'empty-state'); const mark = el('div', undefined, 'empty-icon'); mark.append(icon(glyph));
  box.append(mark, el('h3', heading), el('p', description)); if (action) box.append(action); container.replaceChildren(box);
}
function render() {
  $('stat-actions').textContent = loaded ? tools.length : '—';
  const hosts = [...new Set(tools.map(t => t.target_host))].sort();
  $('stat-hosts').textContent = loaded ? hosts.length : '—';
  $('stat-params').textContent = loaded ? tools.reduce((sum, t) => sum + Object.keys(t.parameters?.properties || {}).length, 0) : '—';
  $('nav-count').textContent = $('catalog-count').textContent = loaded ? tools.length : '—';
  const selected = $('host-filter').value;
  $('host-filter').replaceChildren(new Option('Todos los hosts', ''), ...hosts.map(h => new Option(h, h)));
  if (hosts.includes(selected)) $('host-filter').value = selected;
  $('known-hosts').replaceChildren(...hosts.map(h => new Option(h, h)));
  renderCatalog(); renderHosts();
}
function normalize(s) { return s.toLocaleLowerCase('es').normalize('NFD').replace(/[\u0300-\u036f]/g, ''); }
function renderCatalog() {
  const list = $('actions-list'); list.replaceChildren();
  if (!loaded) {
    empty(list, 'Conecta tu catálogo', 'Configura el acceso para ver y administrar tus acciones.', 'key', button('Configurar acceso', 'button secondary', openAccess, 'key'));
    $('results-count').textContent = 'Sin conexión al catálogo'; return;
  }
  const q = normalize($('filter').value.trim()); const host = $('host-filter').value;
  const filtered = tools.filter(t => (!host || t.target_host === host) && normalize(nameOf(t) + ' ' + descOf(t) + ' ' + t.target_host).includes(q));
  filtered.sort($('sort').value === 'name' ? (a,b) => nameOf(a).localeCompare(nameOf(b), 'es') : (a,b) => b.id - a.id);
  $('results-count').textContent = `${filtered.length} de ${tools.length} ${tools.length === 1 ? 'acción' : 'acciones'}`;
  if (!filtered.length) {
    if (tools.length) empty(list, 'No encontramos coincidencias', 'Prueba con otro término o elimina los filtros.', 'search', button('Limpiar filtros', 'button secondary', () => { $('filter').value = ''; $('host-filter').value = ''; renderCatalog(); }));
    else empty(list, 'Tu primera acción está por llegar', 'Conecta un script para empezar a administrar tu infraestructura en tus palabras.', 'terminal', button('Crear primera acción', 'button primary', () => openEditor(), 'plus'));
    return;
  }
  for (const tool of filtered) list.append(actionCard(tool));
}
function actionCard(t) {
  const card = el('article', undefined, 'action-card'); card.dataset.id = t.id;
  const top = el('div', undefined, 'action-top'); const mark = el('div', undefined, 'action-glyph'); mark.append(icon('terminal'));
  const heading = el('div', undefined, 'action-heading'); heading.append(el('h3', nameOf(t)), el('span', 'SCRIPT REMOTO / SSH', 'transport-label'));
  const actions = el('div', undefined, 'action-actions');
  actions.append(iconButton('Editar ' + nameOf(t), 'edit', () => openEditor(t)), iconButton('Duplicar ' + nameOf(t), 'copy', () => openEditor(t, true)), iconButton('Eliminar ' + nameOf(t), 'trash', () => askDelete(t), 'danger'));
  top.append(mark, heading, actions);
  const bottom = el('div', undefined, 'action-bottom'); const host = el('span', undefined, 'host-label'); host.append(icon('server'), document.createTextNode(t.target_host));
  const n = Object.keys(t.parameters?.properties || {}).length;
  bottom.append(host, el('span', n ? `${n} ${n === 1 ? 'parámetro' : 'parámetros'}` : 'Sin parámetros', 'param-label'));
  card.append(top, el('p', descOf(t), 'action-description'), el('code', t.command_template, 'command-box'), bottom); return card;
}
function renderHosts() {
  const list = $('hosts-list'); list.replaceChildren();
  if (!loaded) { empty(list, 'Conecta el catálogo', 'Los hosts aparecerán al conectar tu catálogo.', 'server', button('Configurar acceso', 'button secondary', openAccess)); return; }
  const hosts = [...new Set(tools.map(t => t.target_host))].sort();
  if (!hosts.length) { empty(list, 'Aún no hay destinos', 'Los hosts se agregarán automáticamente cuando registres acciones.', 'server', button('Nueva acción', 'button primary', () => openEditor())); return; }
  for (const host of hosts) {
    const count = tools.filter(t => t.target_host === host).length; const card = el('article', undefined, 'host-card'); const mark = el('div', undefined, 'stat-icon violet'); mark.append(icon('server'));
    card.append(mark, el('h3', host), el('p', `${count} ${count === 1 ? 'acción registrada' : 'acciones registradas'} · Transporte SSH`), button('Ver acciones', 'text-button', () => { $('filter').value = ''; $('host-filter').value = host; showView('catalog'); renderCatalog(); }, 'arrow')); list.append(card);
  }
}
async function load(quiet = false) {
  $('refresh').disabled = true;
  try { tools = await api('/api/tools'); accessState(true); $('catalog-message').hidden = true; render(); if (!quiet) toast('Catálogo actualizado'); return true; }
  catch (e) { tools = []; accessState(false); render(); $('catalog-message').textContent = e.message; $('catalog-message').hidden = false; return false; }
  finally { $('refresh').disabled = false; }
}
async function health() {
  try { await api('/healthz'); $('service-dot').classList.add('online'); $('service-status').textContent = 'Catálogo disponible'; }
  catch { $('service-dot').classList.remove('online'); $('service-status').textContent = 'Catálogo no disponible'; }
}
function openAccess() { $('token').value = token; $('token').type = 'password'; $('show-token').textContent = 'Mostrar'; formError('access-error', ''); $('access-dialog').showModal(); }
$('access-button').onclick = openAccess;
$('show-token').onclick = () => { const show = $('token').type === 'password'; $('token').type = show ? 'text' : 'password'; $('show-token').textContent = show ? 'Ocultar' : 'Mostrar'; };
$('access-form').onsubmit = async e => {
  e.preventDefault(); $('connect').disabled = true; formError('access-error', ''); const candidate = $('token').value.trim();
  try {
    const result = await api('/api/tools', {}, candidate); token = candidate; tools = result;
    try { if ($('remember-token').checked) sessionStorage.setItem('toolstore-token', token); else sessionStorage.removeItem('toolstore-token'); } catch {}
    accessState(true); $('catalog-message').hidden = true; render(); $('access-dialog').close(); toast('Conexión al catálogo establecida');
  } catch (err) { formError('access-error', err.message); } finally { $('connect').disabled = false; }
};
$('disconnect').onclick = async () => { try { await api('/auth/logout',{method:'POST'}); } catch (e) { formError('access-error',e.message); return; } token = ''; tools = []; try { sessionStorage.removeItem('toolstore-token'); } catch {} accessState(false); render(); $('access-dialog').close(); toast('Acceso desconectado'); };

function schemaToParams(schema) {
  schemaBase = structuredClone(schema);
  params = Object.entries(schema.properties || {}).map(([name, prop]) => ({ name, description: prop.description || '', enum: Array.isArray(prop.enum) ? prop.enum.join(', ') : '', defaultValue: prop.default ?? '', hasDefault: Object.hasOwn(prop,'default'), required: (schema.required || []).includes(name), extra: { ...prop } }));
}
function schemaFromParams() {
  const properties = Object.create(null), required = [];
  for (const p of params) {
    const name = p.name.trim();
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) throw Error('Cada parámetro necesita un nombre válido en minúsculas, sin espacios.');
    if (Object.hasOwn(properties,name)) throw Error(`El parámetro «${name}» está repetido.`);
    const prop = { ...p.extra, type: 'string' }; delete prop.enum; delete prop.default; delete prop.description;
    if (p.description.trim()) prop.description = p.description.trim();
    if (p.enum.trim()) prop.enum = [...new Set(p.enum.split(',').map(s => s.trim()).filter(Boolean))];
    if (p.defaultValue !== '' || p.hasDefault) prop.default = p.defaultValue;
    if (prop.enum && Object.hasOwn(prop, 'default') && !prop.enum.includes(prop.default)) throw Error(`El valor predeterminado de «${name}» debe estar entre sus valores permitidos.`);
    properties[name] = prop; if (p.required) required.push(name);
  }
  return { ...schemaBase, type: 'object', properties, required };
}
function validateSchema(schema) {
  if (!schema || schema.type !== 'object' || !schema.properties || Array.isArray(schema.properties) || typeof schema.properties !== 'object') throw Error('El JSON necesita type: "object" y un objeto properties.');
  if (schema.required !== undefined && (!Array.isArray(schema.required) || schema.required.some(n => typeof n !== 'string' || !Object.hasOwn(schema.properties,n)))) throw Error('required debe listar nombres de parámetros existentes.');
  for (const [name,prop] of Object.entries(schema.properties)) {
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(name) || !prop || prop.type !== 'string') throw Error('Los parámetros deben tener nombres válidos y ser de tipo string.');
    if (prop.enum !== undefined && (!Array.isArray(prop.enum) || !prop.enum.length || prop.enum.some(v => typeof v !== 'string'))) throw Error(`enum de «${name}» debe ser una lista de textos.`);
    if (Object.hasOwn(prop,'default') && typeof prop.default !== 'string') throw Error(`default de «${name}» debe ser texto.`);
    if (prop.enum && Object.hasOwn(prop,'default') && !prop.enum.includes(prop.default)) throw Error(`default de «${name}» no está incluido en enum.`);
  }
  return schema;
}
function renderParams() {
  const list = $('parameters-list'); list.replaceChildren();
  if (!params.length) { list.append(el('div', 'Sin entradas. El script se ejecutará sin argumentos.', 'parameter-empty')); return; }
  params.forEach((p, i) => {
    const row = el('div', undefined, 'parameter-row'); const head = el('div', undefined, 'parameter-row-head');
    head.append(el('span', `PARÁMETRO ${String(i + 1).padStart(2,'0')} · TEXTO`), iconButton('Quitar parámetro ' + (i + 1), 'close', () => { params.splice(i,1); renderParams(); }));
    const grid = el('div', undefined, 'parameter-grid');
    const fields = [['name','Nombre','url'],['description','Descripción','URL del video'],['enum','Valores permitidos','video_best, audio_mp3'],['defaultValue','Valor predeterminado','video_best']];
    for (const [key,label,placeholder] of fields) {
      const field = el('div', undefined, 'field'); const id = `param-${i}-${key}`; const lab = el('label',label); lab.htmlFor = id;
      const input = el('input'); input.id = id; input.value = p[key]; input.placeholder = placeholder; input.maxLength = key === 'name' ? 64 : 1000;
      input.oninput = () => { p[key] = input.value; if (key === 'defaultValue') p.hasDefault = input.value !== ''; };
      field.append(lab,input); grid.append(field);
    }
    const check = el('label',undefined,'checkbox-label'); const input = el('input'); input.type = 'checkbox'; input.checked = p.required; input.onchange = () => p.required = input.checked;
    check.append(input, document.createTextNode('Obligatorio para ejecutar esta acción')); row.append(head,grid,check); list.append(row);
  });
}
function formSnapshot() { return JSON.stringify({fields:['nombre','descripcion','target_host','command_template'].map(id => $(id).value), params, json:jsonMode ? $('parameters').value : null}); }
function openEditor(t = null, duplicate = false) {
  if (!loaded) { openAccess(); return; }
  editing = duplicate ? null : t;
  $('action-form').reset(); jsonMode = false; saving = false;
  $('editor-title').textContent = t && !duplicate ? 'Editar acción' : duplicate ? 'Duplicar acción' : 'Nueva acción';
  $('save-label').textContent = editing ? 'Guardar cambios' : 'Guardar acción';
  $('template-section').hidden = !!t; $('parameter-builder').hidden = false; $('json-editor').hidden = true;
  $('toggle-json').replaceChildren(icon('code'), document.createTextNode('Editar JSON'));
  $('nombre').value = t ? (duplicate ? nameOf(t).slice(0,58) + '_copia' : nameOf(t)) : '';
  $('descripcion').value = t ? descOf(t) : ''; $('target_host').value = t?.target_host || '';
  $('command_template').value = t?.command_template || '';
  schemaToParams(t?.parameters || {type:'object',properties:{},required:[]}); renderParams(); formError('form-error','');
  if (Object.values(t?.parameters?.properties || {}).some(p => p.enum?.some(v => v.includes(',')))) {
    jsonMode = true; $('parameter-builder').hidden = true; $('json-editor').hidden = false;
    $('parameters').value = JSON.stringify(t.parameters,null,2);
    $('toggle-json').replaceChildren(icon('code'),document.createTextNode('Editor visual'));
  }
  initialForm = formSnapshot(); $('editor').showModal(); $('editor').querySelector('.editor-body').scrollTop = 0;
}
function closeEditor() {
  if (saving) return;
  if (formSnapshot() !== initialForm && !confirm('Hay cambios sin guardar. ¿Quieres descartarlos?')) return;
  $('editor').close();
}
$('editor').addEventListener('cancel', e => { e.preventDefault(); closeEditor(); });
$('new-action').onclick = () => openEditor(); $('close-editor').onclick = $('cancel-editor').onclick = closeEditor;
$('add-parameter').onclick = () => { params.push({name:'',description:'',enum:'',defaultValue:'',hasDefault:false,required:true,extra:{}}); renderParams(); $('parameters-list').lastElementChild.querySelector('input').focus(); };
$('toggle-json').onclick = () => {
  try {
    if (jsonMode) { const schema = validateSchema(JSON.parse($('parameters').value)); if (Object.values(schema.properties).some(p => p.enum?.some(v => v.includes(',')))) throw Error('Los valores con comas se conservan en el editor JSON. Guarda desde este modo.'); schemaToParams(schema); renderParams(); }
    else $('parameters').value = JSON.stringify(schemaFromParams(),null,2);
    jsonMode = !jsonMode; $('parameter-builder').hidden = jsonMode; $('json-editor').hidden = !jsonMode;
    $('toggle-json').replaceChildren(icon('code'), document.createTextNode(jsonMode ? 'Editor visual' : 'Editar JSON')); formError('form-error','');
  } catch (e) { formError('form-error', e instanceof SyntaxError ? 'El JSON no es válido. Revisa comas y comillas.' : e.message); }
};
const templates = {
  windows:{nombre:'estado_windows',descripcion:'Consulta el estado del PC Windows, su memoria RAM y espacio libre de disco.',target_host:'pc-windows.vpn',command_template:'C:\\AgentTools\\system_status.ps1',parameters:{type:'object',properties:{},required:[]}},
  fedora:{nombre:'estado_fedora',descripcion:'Consulta el estado del sistema Linux o macOS, carga y espacio libre de disco.',target_host:'macbook-fedora.vpn',command_template:'/opt/agent-tools/system_status.sh',parameters:{type:'object',properties:{},required:[]}},
  download:{nombre:'descargar_video',descripcion:'Descarga un video de YouTube o Vimeo, o su audio MP3, en el equipo Fedora.',target_host:'macbook-fedora.vpn',command_template:'/opt/agent-tools/download_video.sh "{url}" "{formato}"',parameters:{type:'object',properties:{url:{type:'string',description:'URL completa del video'},formato:{type:'string',enum:['video_best','audio_mp3'],default:'video_best'}},required:['url']}}
};
$('template').onchange = () => { const t = templates[$('template').value]; if (!t) return; for (const key of ['nombre','descripcion','target_host','command_template']) $(key).value = t[key]; schemaToParams(t.parameters); renderParams(); if (jsonMode) $('parameters').value = JSON.stringify(t.parameters,null,2); };
$('action-form').onsubmit = async e => {
  e.preventDefault(); if (saving) return; formError('form-error','');
  try {
    if (!$('action-form').reportValidity()) return;
    const body = {};
    for (const key of ['nombre','descripcion','target_host','command_template']) body[key] = $(key).value.trim();
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(body.nombre) || body.nombre === 'finish_transaction') throw Error('El nombre debe empezar con una letra minúscula y usar solo letras, números y guiones bajos.');
    if (!body.descripcion || !body.command_template) throw Error('Completa la descripción y la ruta del script.');
    if (!/^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$/.test(body.target_host)) throw Error('Introduce un host válido, sin usuario, espacios ni ruta.');
    body.parameters = validateSchema(jsonMode ? JSON.parse($('parameters').value) : schemaFromParams());
    for (const match of body.command_template.matchAll(/\{([a-zA-Z_][a-zA-Z0-9_]*)\}/g)) if (!Object.hasOwn(body.parameters.properties,match[1])) throw Error(`Añade el parámetro «${match[1]}» que utiliza tu script.`);
    if (editing) { body.user = editing.user || 'root'; body.port = editing.port || 22; }
    saving = true; $('save-action').disabled = true;
    await api(editing ? '/api/tools/' + editing.id : '/api/tools', {method:editing ? 'PUT' : 'POST',body:JSON.stringify(body)});
    $('editor').close(); await load(true); toast(editing ? 'Acción actualizada' : 'Nueva acción registrada');
  } catch (err) { formError('form-error', err instanceof SyntaxError ? 'El JSON de parámetros no es válido.' : err.status === 409 ? 'Ya existe una acción con ese nombre. Elige otro.' : err.message); }
  finally { saving = false; $('save-action').disabled = false; }
};
function askDelete(t) { deleting = t; $('delete-name').textContent = nameOf(t); formError('delete-error',''); $('delete-dialog').showModal(); }
$('confirm-delete').onclick = async () => {
  if (!deleting) return; $('confirm-delete').disabled = true;
  try { await api('/api/tools/' + deleting.id,{method:'DELETE'}); $('delete-dialog').close(); await load(true); toast('Acción eliminada del catálogo'); }
  catch (e) { formError('delete-error',e.message); } finally { $('confirm-delete').disabled = false; }
};
$('test-form').onsubmit = async e => {
  e.preventDefault(); if (!loaded) { openAccess(); return; }
  const query = $('query').value.trim(); if (!query) { $('query').focus(); return; }
  $('test-submit').disabled = true; $('test-results').setAttribute('aria-busy','true');
  empty($('test-results'),'Buscando acciones…','Revisando las herramientas de tu catálogo.','search');
  try {
    const result = await api('/tools/search',{method:'POST',body:JSON.stringify({query,top_k:3})});
    if (!result.tools.length) { empty($('test-results'),'No encontramos una acción para esa intención','Prueba otra descripción o añade una acción que cubra lo que necesitas.','search',button('Nueva acción','button secondary',()=>openEditor(),'plus')); return; }
    $('test-results').replaceChildren();
    result.tools.forEach((t,i) => {
      const card = el('article',undefined,'match-card'); card.append(el('span',String(i+1).padStart(2,'0'),'match-index'),el('h3',t.name),el('p',t.description));
      const host = el('span',undefined,'host-label'); host.append(icon('server'),document.createTextNode(t.transport.target_host)); card.append(host);
      const detail = el('details'); detail.append(el('summary','Ver comando y parámetros'),el('pre',t.transport.command_template + '\n\n' + JSON.stringify(t.parameters,null,2))); card.append(detail); $('test-results').append(card);
    });
  } catch (err) { empty($('test-results'),'No se pudo completar la búsqueda',err.message,'info'); }
  finally { $('test-submit').disabled = false; $('test-results').removeAttribute('aria-busy'); }
};
$('export-actions').onclick = () => {
  if (!loaded || !tools.length) return;
  const data = tools.map(t => ({nombre:nameOf(t),descripcion:descOf(t),target_host:t.target_host,command_template:t.command_template,parameters:t.parameters,...(t.user ? {user:t.user} : {}),...(t.port ? {port:t.port} : {})}));
  const url = URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'})); const a = el('a'); a.href = url; a.download = 'toolstore-acciones.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000); toast('Catálogo exportado');
};
document.querySelectorAll('[data-view]').forEach(n => n.onclick = () => showView(n.dataset.view));
document.querySelectorAll('[data-close]').forEach(n => n.onclick = () => $(n.dataset.close).close());
document.querySelectorAll('[data-prompt]').forEach(n => n.onclick = () => { $('query').value = n.dataset.prompt; $('query').focus(); });
$('filter').oninput = renderCatalog; $('host-filter').onchange = $('sort').onchange = renderCatalog;
$('refresh').onclick = () => { load(); health(); };
load(true); health();
