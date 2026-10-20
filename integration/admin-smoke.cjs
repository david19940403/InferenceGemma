// Runs against the local Tool Store. Creates and removes only its own test actions.
const { chromium } = require('../.cache/admin-tests/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const root = path.resolve(__dirname, '..');
  const token = (await fs.readFile(path.join(root,'.cache/device-test/token'),'utf8')).trim();
  const base = process.env.ADMIN_TEST_URL || 'http://127.0.0.1:18000';
  const output = path.join(root,'.cache/admin-tests/screens'); await fs.mkdir(output,{recursive:true});
  const created = new Set();
  const api = async (route, method='GET', body) => {
    const res = await fetch(base+route,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body ? {body:JSON.stringify(body)} : {})});
    assert.ok(res.ok, `API ${method} ${route}: ${res.status}`); return res.status === 204 ? null : res.json();
  };
  const before = await api('/api/tools');
  const browser = await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const context = await browser.newContext({viewport:{width:1440,height:1050},deviceScaleFactor:1});
  const page = await context.newPage(); const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  page.on('dialog', d => d.accept());
  const name = 'admin_ui_smoke_' + Date.now();
  try {
    await page.goto(base,{waitUntil:'networkidle'});
    await page.getByText('Conecta tu catálogo',{exact:true}).waitFor();
    await page.locator('#access-button').click();
    await page.locator('#token').fill('incorrect-token'); await page.locator('#connect').click();
    await page.getByText('El token no es válido. Revisa el acceso al catálogo.',{exact:true}).waitFor();
    await page.locator('#token').fill(token); await page.locator('#connect').click();
    await page.locator('#access-dialog').waitFor({state:'hidden'});
    await page.getByText('Acceso conectado',{exact:true}).waitFor();
    assert.equal(await page.locator('.action-card').count(),before.length);
    await page.reload({waitUntil:'networkidle'}); await page.getByText('Acceso conectado',{exact:true}).waitFor();

    await page.locator('#new-action').click();
    await page.locator('#template').selectOption('download');
    assert.equal(await page.locator('.parameter-row').count(),2);
    await page.locator('#nombre').fill(name);
    await page.locator('#descripcion').fill('Consulta de memoria y disco para la prueba del administrador.');
    await page.locator('#target_host').fill('admin-smoke.vpn');
    await page.locator('#command_template').fill('/opt/agent-tools/status.sh "{url}" "{formato}"');
    await page.locator('#toggle-json').click();
    await page.locator('#parameters').fill('{invalid json'); await page.locator('#save-action').click();
    await page.getByText('El JSON de parámetros no es válido.',{exact:true}).waitFor();
    await page.locator('#parameters').fill(JSON.stringify({type:'object',properties:{url:{type:'string'},formato:{type:'string',enum:['rapido','completo'],default:'rapido'}},required:['url']}));
    await page.locator('#toggle-json').click();
    await page.locator('#save-action').click(); await page.locator('#editor').waitFor({state:'hidden'});
    const tool = (await api('/api/tools')).find(t=>t.name===name); assert.ok(tool); created.add(tool.id);
    assert.equal(tool.parameters.properties.formato.default,'rapido');
    let card = page.locator(`.action-card[data-id="${tool.id}"]`); await card.waitFor();
    await card.getByRole('button',{name:'Editar '+name,exact:true}).click();
    const description = 'Memoria y disco <img src=x onerror="window.adminXSS=true">';
    await page.locator('#descripcion').fill(description); await page.locator('#save-action').click();
    await page.locator('#editor').waitFor({state:'hidden'}); card = page.locator(`.action-card[data-id="${tool.id}"]`);
    await card.getByText(description,{exact:true}).waitFor(); assert.equal(await card.locator('img').count(),0);
    const changed = (await api('/api/tools')).find(t=>t.id===tool.id); assert.equal(changed.description,description);
    await card.getByRole('button',{name:'Duplicar '+name,exact:true}).click();
    assert.equal(await page.locator('#nombre').inputValue(),name+'_copia');
    await page.locator('#save-action').click(); await page.locator('#editor').waitFor({state:'hidden'});
    const duplicate=(await api('/api/tools')).find(t=>t.name===name+'_copia'); assert.ok(duplicate); created.add(duplicate.id);

    await page.locator('#filter').fill('does-not-exist-smoke');
    await page.getByText('No encontramos coincidencias',{exact:true}).waitFor();
    await page.getByRole('button',{name:'Limpiar filtros',exact:true}).click();
    await page.locator('nav [data-view="hosts"]').click();
    const hostCard=page.locator('.host-card').filter({hasText:'admin-smoke.vpn'});
    await hostCard.getByRole('button',{name:'Ver acciones',exact:true}).click(); assert.equal(await page.locator('.action-card').count(),2);
    await page.locator('nav [data-view="test"]').click(); await page.locator('#query').fill(name);
    await page.locator('#test-submit').click(); await page.locator('.match-card').filter({hasText:name}).first().waitFor();
    assert.ok((await page.locator('.match-card').count())<=3);
    await page.locator('nav [data-view="catalog"]').click(); await page.locator('#host-filter').selectOption('');
    const downloadEvent=page.waitForEvent('download'); await page.locator('#export-actions').click();
    const download=await downloadEvent; const exported=JSON.parse(await fs.readFile(await download.path(),'utf8'));
    assert.ok(exported.some(t=>t.nombre===name)); assert.ok(!JSON.stringify(exported).includes(token));
    await page.locator(`.action-card[data-id="${tool.id}"]`).getByRole('button',{name:'Eliminar '+name,exact:true}).click();
    await page.locator('#confirm-delete').click(); await page.locator('#delete-dialog').waitFor({state:'hidden'}); created.delete(tool.id);
    assert.ok(!(await api('/api/tools')).some(t=>t.id===tool.id));
    await api('/api/tools/'+duplicate.id,'DELETE'); created.delete(duplicate.id);
    await page.locator('#refresh').click(); await page.getByText(`${before.length} de ${before.length} ${before.length===1?'acción':'acciones'}`,{exact:true}).waitFor();
    await page.screenshot({path:path.join(output,'catalog-desktop.png'),fullPage:true});
    await page.locator('#new-action').click(); await page.locator('#template').selectOption('download');
    await page.screenshot({path:path.join(output,'editor-desktop.png')});
    await page.locator('#cancel-editor').click();
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'Mobile page overflow');
    await page.screenshot({path:path.join(output,'catalog-mobile.png'),fullPage:true});
    await page.locator('#new-action').click(); await page.locator('#template').selectOption('windows');
    assert.equal(await page.locator('#command_template').inputValue(),'C:\\AgentTools\\system_status.ps1');
    await page.screenshot({path:path.join(output,'editor-mobile.png')}); await page.locator('#cancel-editor').click();
    assert.deepEqual(errors,[],'Browser runtime errors');
    const after=await api('/api/tools');
    assert.deepEqual(after.map(t=>t.id).sort(),before.map(t=>t.id).sort(),'Original catalog changed');
    console.log('PASS: authentication, tab persistence, create, schema validation, edit, XSS escaping, duplicate, filters, hosts, retrieval, export, delete, responsive layouts.');
    console.log('Evidence: '+output);
  } finally {
    for (const id of created) await api('/api/tools/'+id,'DELETE').catch(()=>{});
    await browser.close();
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
