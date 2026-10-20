// Physical-device LAN test. Token stays in the ignored .cache directory.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {chromium} = require('../.cache/admin-tests/node_modules/playwright');
(async()=>{
 const root=path.resolve(__dirname,'..');
 const base=process.env.REMOTE_BASE; assert.ok(base,'Set REMOTE_BASE to the Android server URL');
 const token=(await fs.readFile(path.join(root,'.cache/remote-test/access-token'),'utf8')).trim();
 const request=async(route,method='GET',body,expected=200,extra={})=>{
   const response=await fetch(base+'/remote/'+route,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...extra},...(body?{body:JSON.stringify(body)}:{})});
   const data=await response.json();assert.equal(response.status,expected,`${route}: ${JSON.stringify(data)}`);return data;
 };
 assert.equal((await fetch(base+'/remote/status')).status,401);
 await request('status','GET',null,401,{Authorization:'Bearer invalid'});
 await request('status','GET',null,403,{Origin:'https://example.com'});
 const settings=(await request('settings')).settings;
 await request('settings','PUT',{...settings,contextTokens:4096},400);
 await request('settings','PUT',settings);
 const downloaded=Buffer.from(await (await fetch(base+'/client.apk')).arrayBuffer());
 const apk=await fs.readFile(path.join(root,'client/build/outputs/apk/debug/client-debug.apk'));
 assert.equal(crypto.createHash('sha256').update(downloaded).digest('hex'),crypto.createHash('sha256').update(apk).digest('hex'));
 console.log('PASS authentication, origin guard, settings validation, client APK integrity');
 const wait=async(id)=>{
   const until=Date.now()+180000;let status;
   while(Date.now()<until){status=await request('status');if(status.job?.id===id&&!['queued','running'].includes(status.job.status)){assert.equal(status.job.status,'succeeded',status.job.message);return status}await new Promise(r=>setTimeout(r,1000))}
   throw Error('Engine operation timed out');
 };
 const request_id=crypto.randomUUID();
 const job=await request('engine','POST',{action:'load',request_id},202);
 assert.equal((await request('engine','POST',{action:'load',request_id},202)).id,job.id);
 await request('engine','POST',{action:'unload',request_id:crypto.randomUUID()},409);
 const loaded=await wait(job.id);assert.equal(loaded.engine.modelLoaded,true);
 await request('settings','PUT',settings,409);
 console.log('PASS background model loading, current request idempotency, concurrent operation guard');
 const intent=await request('intents','POST',{query:'Consulta el estado del servidor Windows de prueba',request_id:crypto.randomUUID()},202);
 const finished=await wait(intent.id);assert.ok(finished.job.result);console.log('Remote intent result:',finished.job.result.slice(0,650));
 const unload=await request('engine','POST',{action:'unload',request_id:crypto.randomUUID()},202);
 assert.equal((await wait(unload.id)).engine.modelLoaded,false);
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try {
   const page=await browser.newPage({viewport:{width:1440,height:1050}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.goto(base);await page.locator('#login').waitFor({state:'visible'});
   await page.locator('#token').fill(token);await page.locator('#login-form button').click();await page.locator('#dashboard').waitFor({state:'visible'});
   assert.equal(await page.locator('#token').inputValue(),'');
   const cookie=(await page.context().cookies()).find(c=>c.name==='remote_session');assert.ok(cookie.httpOnly);assert.equal(cookie.sameSite,'Strict');
   await page.screenshot({path:path.join(root,'.cache/remote-test/desktop.png'),fullPage:true});
   await page.reload();await page.locator('#dashboard').waitFor({state:'visible'});
   await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(root,'.cache/remote-test/mobile.png'),fullPage:true});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
   await page.locator('#logout').click();await page.locator('#login').waitFor({state:'visible'});
   assert.equal(errors.length,0,errors.join('\n'));console.log('PASS browser login, HttpOnly session, reload, logout, desktop and mobile layout');
 }finally{await browser.close()}
 console.log('PASS remote engine and browser smoke tests');
})().catch(e=>{console.error(e);process.exitCode=1});
