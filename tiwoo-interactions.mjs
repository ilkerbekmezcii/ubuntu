import { chromium } from 'playwright';
import { randomUUID } from 'node:crypto';

const base='https://tiwoo.vercel.app';
const result={base,actions:{},issues:[]};
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();
let accessToken='',username='';
const sleep=ms=>page.waitForTimeout(ms);
const sanitize=s=>String(s||'').replace(/https?:\/\/[^\s)]+/g,'<url>').slice(0,300);
const bad=[];
page.on('response',r=>{if(r.status()>=400){try{const u=new URL(r.url());if(u.hostname==='tiwoo.vercel.app'||u.hostname.endsWith('.supabase.co'))bad.push({path:u.pathname,status:r.status()})}catch{}}});

async function register(){
  username=`ix_${Date.now().toString(36).slice(-8)}`.slice(0,20);
  const password=`T!${randomUUID().replace(/-/g,'').slice(0,20)}a9`;
  const reg=await page.request.post(`${base}/api/tiwoo-auth`,{data:{action:'register',username,password,name:'Tiwoo Interaction E2E'}});
  const body=await reg.json().catch(()=>({}));
  if(!reg.ok()||!body?.session?.accessToken||!body?.session?.refreshToken)throw Error(`register_${reg.status()}`);
  accessToken=String(body.session.accessToken);
  await page.evaluate(async s=>{const c=window.TiwooSupabase?.sb;const {error}=await c.auth.setSession({access_token:s.accessToken,refresh_token:s.refreshToken});if(error)throw Error(error.message)},body.session);
  await page.reload({waitUntil:'domcontentloaded',timeout:30000});
  await sleep(3000);
}

async function action(name){
  const selector=`[data-post] [data-act="${name}"]`;
  const btn=page.locator(selector).first();
  if(!await btn.count())return {available:false};
  const post=btn.locator('xpath=ancestor::*[@data-post][1]');
  const postId=await post.getAttribute('data-post');
  const before={class:await btn.getAttribute('class'),text:await btn.innerText().catch(()=>''),disabled:await btn.isDisabled().catch(()=>false)};
  const startBad=bad.length;
  await btn.click({timeout:10000});
  await sleep(1400);
  const current=page.locator(`[data-post="${postId}"] [data-act="${name}"]`).first();
  const after={class:await current.getAttribute('class').catch(()=>null),text:await current.innerText().catch(()=>''),disabled:await current.isDisabled().catch(()=>false)};
  const toast=await page.locator('#toast').innerText().catch(()=>'');
  return {available:true,postId,before,after,toast:sanitize(toast),badResponses:bad.slice(startBad)};
}

try{
  await page.goto(base,{waitUntil:'domcontentloaded',timeout:30000});
  await sleep(2500);
  await register();
  for(const name of ['like','repost','bookmark','share']){
    try{result.actions[name]=await action(name)}catch(e){result.actions[name]={error:sanitize(e.message)};result.issues.push(`${name}: ${sanitize(e.message)}`)}
  }
  for(const name of ['like','repost','bookmark']){
    const a=result.actions[name];
    if(!a?.available||a?.error){if(!a?.error)result.issues.push(`${name}: button missing`);continue}
    if(a.badResponses?.length)result.issues.push(`${name}: HTTP ${a.badResponses.map(x=>x.status).join(',')}`);
    const successToast=name==='like'?/Beğenildi|Beğeni kaldırıldı/i:name==='repost'?/Yeniden paylaşıldı|geri alındı/i:/Kaydedildi|Kayıttan kaldırıldı/i;
    if(!successToast.test(a.toast||''))result.issues.push(`${name}: no success toast (${a.toast||'empty'})`);
  }
  if(result.actions.share?.available&&!/Bağlantı kopyalandı|kopyalanamadı/i.test(result.actions.share.toast||''))result.issues.push('share: no clipboard result toast');
}catch(e){result.issues.push(`fatal: ${sanitize(e.message)}`)}finally{
  if(accessToken&&username){
    const del=await page.request.post(`${base}/api/tiwoo-auth`,{headers:{authorization:`Bearer ${accessToken}`},data:{action:'account_delete',confirmation:username,acknowledged:true}}).catch(()=>null);
    result.cleanup=del?{status:del.status(),ok:del.ok()}:{status:0,ok:false};
  }
  await context.close();await browser.close();
}
console.log(JSON.stringify(result,null,2));
process.exitCode=result.issues.length?1:0;
