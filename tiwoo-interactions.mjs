import { chromium } from 'playwright';
import { randomUUID } from 'node:crypto';

const base='https://tiwoo.vercel.app';
const result={base,actions:{},issues:[]};
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000},permissions:['clipboard-read','clipboard-write']});
const page=await context.newPage();
let primary={accessToken:'',refreshToken:'',username:''};
let peer={accessToken:'',refreshToken:'',username:''};
const sleep=ms=>page.waitForTimeout(ms);
const sanitize=s=>String(s||'').replace(/https?:\/\/[^\s)]+/g,'<url>').slice(0,300);
const bad=[];
page.on('response',r=>{if(r.status()>=400){try{const u=new URL(r.url());if(u.hostname==='tiwoo.vercel.app'||u.hostname.endsWith('.supabase.co'))bad.push({path:u.pathname,status:r.status()})}catch{}}});

async function register(label){
  const username=`ix_${label}_${Date.now().toString(36).slice(-6)}_${Math.random().toString(36).slice(2,5)}`.slice(0,20);
  const password=`T!${randomUUID().replace(/-/g,'').slice(0,20)}a9`;
  const reg=await page.request.post(`${base}/api/tiwoo-auth`,{data:{action:'register',username,password,name:`Tiwoo ${label} E2E`}});
  const body=await reg.json().catch(()=>({}));
  if(!reg.ok()||!body?.session?.accessToken||!body?.session?.refreshToken)throw Error(`register_${label}_${reg.status()}`);
  return {username,password,accessToken:String(body.session.accessToken),refreshToken:String(body.session.refreshToken)};
}

async function setSession(s){
  await page.evaluate(async x=>{const c=window.TiwooSupabase?.sb;if(!c?.auth?.setSession)throw Error('client_missing');const {error}=await c.auth.setSession({access_token:x.accessToken,refresh_token:x.refreshToken});if(error)throw Error(error.message)},s);
  await page.reload({waitUntil:'domcontentloaded',timeout:30000});
  await sleep(3000);
}

async function toastText(){return sanitize(await page.locator('#toast').innerText().catch(()=>''))}
function collectBad(start){return bad.slice(start)}

async function actOnPost(postId,name){
  const btn=page.locator(`[data-post="${postId}"] [data-act="${name}"]`).first();
  if(!await btn.count())return {available:false};
  const before={class:await btn.getAttribute('class'),text:await btn.innerText().catch(()=>''),disabled:await btn.isDisabled().catch(()=>false)};
  const startBad=bad.length;
  await btn.click({timeout:10000});
  await sleep(1400);
  const current=page.locator(`[data-post="${postId}"] [data-act="${name}"]`).first();
  const after={class:await current.getAttribute('class').catch(()=>null),text:await current.innerText().catch(()=>''),disabled:await current.isDisabled().catch(()=>false)};
  return {available:true,postId,before,after,toast:await toastText(),badResponses:collectBad(startBad)};
}

async function createPost(){
  const text=`interaction-e2e-${Date.now()}`;
  const startBad=bad.length;
  const input=page.locator('#inlineText').first();
  const send=page.locator('#inlineSend').first();
  if(!await input.count()||!await send.count())return {available:false};
  await input.fill(text); await send.click(); await sleep(1500);
  const article=page.locator('[data-post]').filter({hasText:text}).first();
  const postId=await article.getAttribute('data-post').catch(()=>null);
  return {available:true,text,postId,toast:await toastText(),badResponses:collectBad(startBad)};
}

async function replyTo(postId){
  const startBad=bad.length;
  const btn=page.locator(`[data-post="${postId}"] [data-act="reply"]`).first();
  if(!await btn.count())return {available:false};
  await btn.click(); await sleep(700);
  const input=page.locator('#replyModalText').first(),send=page.locator('#replyModalSend').first();
  if(!await input.count()||!await input.isVisible().catch(()=>false))return {available:true,error:'reply_modal_missing',badResponses:collectBad(startBad)};
  const text=`reply-e2e-${Date.now()}`;
  await input.fill(text); await send.click(); await sleep(1600);
  return {available:true,text,toast:await toastText(),visible:await page.getByText(text,{exact:true}).count().catch(()=>0),badResponses:collectBad(startBad)};
}

async function deleteOwnPost(postId){
  const startBad=bad.length;
  page.once('dialog',d=>d.accept());
  const btn=page.locator(`[data-post="${postId}"] [data-delete]`).first();
  if(!await btn.count())return {available:false};
  await btn.click(); await sleep(1500);
  return {available:true,remaining:await page.locator(`[data-post="${postId}"]`).count(),toast:await toastText(),badResponses:collectBad(startBad)};
}

async function openPeerProfile(){
  const found=await page.evaluate(username=>{
    try{
      const u=(typeof social!=='undefined'&&Array.isArray(social.directory)?social.directory:[]).find(x=>String(x.handle||'').toLowerCase()===`@${String(username).toLowerCase()}`||String(x.name||'').toLowerCase()===String(username).toLowerCase());
      if(!u?.key)return null;
      if(typeof go==='function'){go('profile',{profile:u.key});return {key:u.key,handle:u.handle}}
      return null;
    }catch{return null}
  },peer.username);
  if(!found){
    const explore=page.getByText(/^Keşfet$/).first(); if(await explore.count())await explore.click().catch(()=>{}); await sleep(600);
    const search=page.locator('input[type="search"]:visible').first(); if(await search.count())await search.fill(peer.username); await sleep(800);
    const row=page.locator('[data-user]').filter({hasText:peer.username}).first(); if(await row.count())await row.click();
  }
  await sleep(900);
  return {found:Boolean(found)||await page.getByText(new RegExp(peer.username,'i')).count()>0};
}

async function followPeer(){
  const startBad=bad.length;
  const profile=await openPeerProfile();
  const btn=page.locator('#followProfile').first();
  if(!await btn.count())return {available:false,profile};
  const before=await btn.innerText().catch(()=> ''); await btn.click(); await sleep(1400);
  const after=await page.locator('#followProfile').first().innerText().catch(()=> '');
  return {available:true,profile,before,after,toast:await toastText(),badResponses:collectBad(startBad)};
}

async function messagePeer(){
  const startBad=bad.length;
  await openPeerProfile();
  const msg=page.locator('#msgProfile').first();
  if(!await msg.count())return {available:false};
  await msg.click(); await sleep(1100);
  const input=page.locator('#dmText').first(),send=page.locator('#dmSend').first();
  if(!await input.count()||!await send.count())return {available:true,error:'dm_compose_missing',badResponses:collectBad(startBad)};
  const text=`dm-e2e-${Date.now()}`; await input.fill(text); await send.click(); await sleep(1600);
  return {available:true,text,toast:await toastText(),badResponses:collectBad(startBad)};
}

async function cleanupAccount(s){
  if(!s?.accessToken||!s?.username)return {status:0,ok:false};
  const del=await page.request.post(`${base}/api/tiwoo-auth`,{headers:{authorization:`Bearer ${s.accessToken}`},data:{action:'account_delete',confirmation:s.username,acknowledged:true}}).catch(()=>null);
  return del?{status:del.status(),ok:del.ok()}:{status:0,ok:false};
}

try{
  await page.goto(base,{waitUntil:'domcontentloaded',timeout:30000}); await sleep(2500);
  peer=await register('peer');
  primary=await register('main');
  await setSession(primary);

  result.actions.create=await createPost();
  const postId=result.actions.create?.postId;
  if(!postId)result.issues.push(`create: no post id (${result.actions.create?.toast||'no toast'})`);
  if(result.actions.create?.badResponses?.length)result.issues.push(`create: HTTP ${result.actions.create.badResponses.map(x=>x.status).join(',')}`);

  if(postId){
    for(const name of ['like','repost','bookmark','share']){
      try{result.actions[name]=await actOnPost(postId,name)}catch(e){result.actions[name]={error:sanitize(e.message)};result.issues.push(`${name}: ${sanitize(e.message)}`)}
    }
    result.actions.reply=await replyTo(postId).catch(e=>({error:sanitize(e.message)}));
  }

  result.actions.follow=await followPeer().catch(e=>({error:sanitize(e.message)}));
  result.actions.message=await messagePeer().catch(e=>({error:sanitize(e.message)}));

  for(const name of ['like','repost','bookmark']){
    const a=result.actions[name];
    if(!a?.available||a?.error){if(!a?.error)result.issues.push(`${name}: button missing`);continue}
    if(a.badResponses?.length)result.issues.push(`${name}: HTTP ${a.badResponses.map(x=>x.status).join(',')}`);
    const successToast=name==='like'?/Beğenildi|Beğeni kaldırıldı/i:name==='repost'?/Yeniden paylaşıldı|geri alındı/i:/Kaydedildi|Kayıttan kaldırıldı/i;
    if(!successToast.test(a.toast||''))result.issues.push(`${name}: no success toast (${a.toast||'empty'})`);
  }
  if(result.actions.share?.available&&!/Bağlantı kopyalandı|kopyalanamadı/i.test(result.actions.share.toast||''))result.issues.push('share: no clipboard result toast');
  if(result.actions.reply?.badResponses?.length||result.actions.reply?.error||!/Yanıt gönderildi/i.test(result.actions.reply?.toast||''))result.issues.push(`reply: ${result.actions.reply?.error||result.actions.reply?.toast||'failed'}`);
  if(result.actions.follow?.badResponses?.length||result.actions.follow?.error||!/takip|istek/i.test(result.actions.follow?.toast||''))result.issues.push(`follow: ${result.actions.follow?.error||result.actions.follow?.toast||'failed'}`);
  if(result.actions.message?.badResponses?.length||result.actions.message?.error||!/Mesaj gönderildi|zaten gönderilmişti/i.test(result.actions.message?.toast||''))result.issues.push(`message: ${result.actions.message?.error||result.actions.message?.toast||'failed'}`);

  if(postId)result.actions.delete=await deleteOwnPost(postId).catch(e=>({error:sanitize(e.message)}));
  if(result.actions.delete?.available&&(result.actions.delete.remaining||result.actions.delete.badResponses?.length))result.issues.push(`delete: ${result.actions.delete.toast||'failed'}`);
}catch(e){result.issues.push(`fatal: ${sanitize(e.message)}`)}finally{
  result.cleanup={primary:await cleanupAccount(primary),peer:await cleanupAccount(peer)};
  if(!result.cleanup.primary.ok)result.issues.push('cleanup primary failed');
  if(!result.cleanup.peer.ok)result.issues.push('cleanup peer failed');
  await context.close(); await browser.close();
}
console.log(JSON.stringify(result,null,2));
process.exitCode=result.issues.length?1:0;
