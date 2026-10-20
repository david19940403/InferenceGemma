// Display regression: draft probe is not a save; other clients update without losing edits.
// Requires REMOTE_BASE, the ignored fixture token, and Android adb reverse tcp:18002 tcp:18000.
const assert=require('node:assert/strict');const fs=require('node:fs/promises');const path=require('node:path');const {chromium}=require('../.cache/admin-tests/node_modules/playwright');
(async()=>{
 const root=path.resolve(__dirname,'..'),base=process.env.REMOTE_BASE;assert.ok(base,'Set REMOTE_BASE');
 const token=(await fs.readFile(path.join(root,'.cache/remote-test/access-token'),'utf8')).trim();
 const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
 const api=async(method='GET',value)=>{const r=await fetch(base+'/remote/toolstore',{method,headers,...(value?{body:JSON.stringify(value)}:{})});assert.ok(r.ok,await r.clone().text());return r.json()};
 const saved=await api();const alternate='http://127.0.0.1:18002';assert.notEqual(saved.url,alternate);
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try {
  const contexts=await Promise.all([browser.newContext(),browser.newContext()]);const pages=[];const errors=[];
  for(const context of contexts){const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));await p.goto(base);await p.locator('#token').fill(token);await p.locator('#login-form button').click();await p.locator('#toolstore-saved-url').getByText(saved.url,{exact:true}).waitFor();pages.push(p)}
  const [a,b]=pages;
  await a.locator('#toolstore-url').fill(alternate);await a.locator('#toolstore-pending').waitFor({state:'visible'});await a.locator('#test-toolstore').click();await a.getByText('Última prueba correcta: '+alternate+'. Probar no guarda la dirección; pulsa Guardar para aplicarla.',{exact:true}).waitFor();assert.equal((await api()).url,saved.url);assert.equal(await a.locator('#toolstore-saved-url').textContent(),saved.url);
  await a.locator('#toolstore-form').screenshot({path:path.join(root,'.cache/connection-display-draft.png')});
  await a.locator('#save-toolstore').click();await a.getByText('Tool Store guardado. Se usará en la siguiente intención.',{exact:true}).waitFor();await b.waitForFunction(value=>document.getElementById('toolstore-saved-url').textContent===value&&document.getElementById('toolstore-url').value===value,alternate);assert.equal((await api()).url,alternate);
  const draft='http://192.168.9.1:8000';await b.locator('#toolstore-url').fill(draft);await api('PUT',saved);await b.waitForFunction(value=>document.getElementById('toolstore-saved-url').textContent===value,saved.url);assert.equal(await b.locator('#toolstore-url').inputValue(),draft);await b.locator('#toolstore-pending').waitFor({state:'visible'});
  await a.reload();await a.locator('#toolstore-saved-url').getByText(saved.url,{exact:true}).waitFor();assert.equal(await a.locator('#toolstore-url').inputValue(),saved.url);await a.locator('#toolstore-pending').waitFor({state:'hidden'});assert.deepEqual(errors,[]);
  console.log('PASS draft probe, explicit save, live cross-client sync, preserved unsaved edits and reload');
 }finally {await api('PUT',saved);await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
