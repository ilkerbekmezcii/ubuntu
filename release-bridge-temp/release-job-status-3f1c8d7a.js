import { Sandbox } from '@vercel/sandbox';
const VALID=new Set(['adam','apple','blue','brain','cengel','chess','gameconsole']);
function one(v){return Array.isArray(v)?v[0]:v}
async function read(box,path){try{const b=await box.readFileToBuffer({path});return b?b.toString('utf8'):''}catch{return ''}}
export default async function handler(req,res){try{const app=String(one(req.query?.app)||'').toLowerCase();if(!VALID.has(app))return res.status(400).json({ok:false,error:'invalid_app'});const box=await Sandbox.get({name:'earth-games-release-vm',resume:true});const exit=(await read(box,`jobs/${app}.exit`)).trim();const log=await read(box,`jobs/${app}.log`);const result=await read(box,`jobs/${app}.result.json`);return res.status(200).json({ok:true,app,status:exit===''?'running':exit==='0'?'success':'failed',exitCode:exit===''?null:Number(exit),result:result.slice(-1200),log:log.split('\n').slice(-80).join('\n')})}catch(e){return res.status(500).json({ok:false,error:e?.message||String(e)})}}
