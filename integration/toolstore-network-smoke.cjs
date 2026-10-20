// Requires the device fixture, a LAN Tool Store, and the ignored local test tokens.
const assert=require('node:assert/strict');const fs=require('node:fs/promises');const crypto=require('node:crypto');const path=require('node:path');
const {chromium}=require('../.cache/admin-tests/node_modules/playwright');
(async()=>{
 const root=path.resolve(__dirname,'..'), base=process.env.REMOTE_BASE, lan=process.env.NETWORK_CATALOG_URL;
 assert.ok(base&&lan,'Set REMOTE_BASE and NETWORK_CATALOG_URL');
 const token=(await fs.readFile(path.join(root,'.cache/remote-test/access-token'),'utf8')).trim();
 const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
 const api=async(route,method='GET',body,expected=200)=>{const r=await fetch(base+'/remote/'+route,{method,headers,...(body?{body:JSON.stringify(body)}:{})});const data=await r.json();assert.equal(r.status,expected,JSON.stringify(data));return data};
 const saved=await api('toolstore');assert.equal(saved.url,lan,'Address saved from native Android dialog');
 const wait=async(id)=>{for(let n=0;n<180;n++){const s=await api('status');if(s.job?.id===id&&!['queued','running'].includes(s.job.status)){assert.equal(s.job.status,'succeeded',s.job.message);return s}await new Promise(r=>setTimeout(r,1000))}throw Error('Timeout')};
 const engine=async(action)=>{const j=await api('engine','POST',{action,request_id:crypto.randomUUID()},202);return wait(j.id)};
 const query='Averigua conectividad hacia ::1';
 try {
  assert.match((await api('toolstore/test','POST',saved)).message,/autorizada/);
  const catalogToken=(await fs.readFile(path.join(root,'.cache/device-test/token'),'utf8')).trim();
  const raw=await(await fetch(lan+'/tools/search',{method:'POST',headers:{Authorization:'Bearer '+catalogToken,'Content-Type':'application/json'},body:JSON.stringify({query,top_k:3})})).json();assert.deepEqual(raw.tools,[]);
  await api('toolstore','PUT',{...saved,url:'http://10.12.14.1/24'},400);
  await api('toolstore','PUT',{...saved,rewriteSearch:false});
  const baseline=(await api('status')).engine.evaluations;
  let job=await api('intents','POST',{query,request_id:crypto.randomUUID()},202);let result=await wait(job.id);
  assert.match(result.job.result,/No encontré herramientas/);assert.equal(result.engine.evaluations,baseline);console.log('PASS disabled rewriting does not load/evaluate the model');
  await api('toolstore','PUT',{...saved,rewriteSearch:true});
  job=await api('intents','POST',{query,request_id:crypto.randomUUID()},202);
  await api('toolstore','PUT',saved,409);
  result=await wait(job.id);console.log('Real rewritten-search result:',result.job.result);assert.equal(result.job.query,query);assert.doesNotMatch(result.job.result,/No encontré herramientas/);assert.match(result.job.result,/::1/);assert.ok(result.engine.evaluations>=baseline+3);console.log('PASS real LiteRT rewrite retrieves ping from an initially empty search');
  await engine('load');
  for(const url of ['http://10.12.14.1:8000','http://192.168.9.1:8000',lan]){await api('toolstore','PUT',{...saved,url});assert.equal((await api('status')).engine.modelLoaded,true)}
  await engine('unload');console.log('PASS changes between LAN/VPN addresses without unloading the idle model');
  const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  try {const page=await browser.newPage({viewport:{width:1400,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(base);await page.locator('#token').fill(token);await page.locator('#login-form button').click();await page.locator('#toolstore-url').waitFor({state:'visible'});await page.waitForFunction(()=>document.getElementById('toolstore-url').value.length>0);await page.locator('#vpn-preset').click();assert.equal(await page.locator('#toolstore-url').inputValue(),'http://10.12.14.1:18001');await page.locator('#lan-preset').click();assert.equal(await page.locator('#toolstore-url').inputValue(),'http://192.168.9.1:18001');await page.locator('#toolstore-url').fill(lan);await page.locator('#save-toolstore').click();await page.getByText('Tool Store guardado. Se usará en la siguiente intención.',{exact:true}).waitFor();await page.locator('#test-toolstore').click();await page.getByText('Conexión autorizada con el Tool Store',{exact:true}).waitFor();await page.screenshot({path:path.join(root,'.cache/toolstore-network-test/desktop.png'),fullPage:true});await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);await page.screenshot({path:path.join(root,'.cache/toolstore-network-test/mobile.png'),fullPage:true});assert.deepEqual(errors,[]);console.log('PASS web network settings, presets, save, probe and responsive layout')} finally {await browser.close()}
 }finally {
  const state=await api('status');if(!state.terminal.busy&&state.engine.modelLoaded)await engine('unload');
  await api('toolstore','PUT',{...saved,url:process.env.RESTORE_TOOLSTORE_URL||saved.url});
 }
})().catch(e=>{console.error(e);process.exitCode=1});
