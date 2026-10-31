/** Isolated UI preview. Uses real Vue views with fixture GET responses; never proxies to backend. */
import { createServer } from '../web/node_modules/vite/dist/node/index.js';
import vue from '../web/node_modules/@vitejs/plugin-vue/dist/index.mjs';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
const root = fileURLToPath(new URL('../web', import.meta.url));
const permissions = ['reservations:read','resources:manage','roomapplications:create','roomapplications:approve:department','roomapplications:approve:resource','workorders:create','workorders:assign','workorders:accept'];
const user = {id:'preview-user',username:'preview',displayName:'界面预览',departmentCode:'CS',permissions};
const rooms = [
 {id:'room-a',name:'第一自习室',building:'教学楼 A',available:true,availabilityVersion:1,openTime:'07:00:00',closeTime:'23:00:00'},
 {id:'room-b',name:'阅览空间',building:'图书馆',available:false,availabilityVersion:2,openTime:'08:00:00',closeTime:'22:00:00'},
 {id:'room-c',name:'小组讨论室',building:'教学楼 B',available:true,availabilityVersion:1,openTime:'07:00:00',closeTime:'23:00:00'},
];
const start = new Date(); start.setMinutes(0,0,0);start.setHours(start.getHours()+2);
const time=(offset=0)=>new Date(start.getTime()+offset*3600000).toISOString();
const seats=Array.from({length:12},(_,i)=>({id:'seat-'+i,code:'A'+String(i+1).padStart(2,'0'),powerType:['FIXED','RAIL','NONE'][i%3],available:i%4!==2}));
const reservations=['CONFIRMED','CHECKED_IN','CANCELLED_MAINTENANCE'].map((status,i)=>({id:'reservation-'+i,seatId:'seat-'+i,roomId:'room-a',seatCode:seats[i].code,roomName:rooms[0].name,building:rooms[0].building,startAt:time(i*3),endAt:time(i*3+2),status,purpose:['课程复习','阅读与自习','项目资料整理'][i]}));
const applications=['DEPARTMENT_PENDING','RESOURCE_PENDING','APPROVED','REJECTED'].map((state,i)=>({id:'application-'+i,roomName:rooms[i%3].name,purpose:['工程伦理小组讨论','课程展示彩排','社团例会','读书分享'][i],startAt:time(24+i*3),endAt:time(26+i*3),state,applicantId:i===2?'preview-user':'another-preview'}));
const orders=['REPORTED','ASSIGNED','REPAIRING','AWAITING_ACCEPTANCE','CLOSED'].map((state,i)=>({id:'order-'+i,title:['A03 座位插座接触不良','教室照明需要检修','窗户锁扣松动','空调制冷异常','投影设备无法连接'][i],reporterId:'preview-user',repairerId:'preview-user',repairerName:i?'维修人员（示例）':'',state,escalationLevel:i===2?1:0}));
const notices=[{id:'notice-1',message:'你的第一自习室座位预约已确认，请在预约开始后按时签到。',mailState:'SENT'},{id:'notice-2',message:'小组讨论室申请已进入资源审批阶段，可在教室申请页面查看处理结果。',mailState:'SENT'},{id:'notice-3',message:'阅览空间当前处于维修限制中。维修验收通过后，空间可用状态将更新。',mailState:'PENDING'}];
const fixtures={
 '/rooms':{items:rooms},'/reservations':{items:reservations},'/reservations/violations':[],
 '/room-applications':applications,'/work-orders':orders,'/notifications':notices,
 '/directory/maintenance-workers':[{id:'preview-user',displayName:'维修人员（示例）',email:'preview@example.invalid'}],
 '/admin/reservation-statistics':{byStatus:[{status:'CONFIRMED',count:12},{status:'CHECKED_IN',count:28},{status:'CANCELLED',count:3}],byRoom:rooms.map((r,i)=>({roomName:r.name,count:[24,8,11][i]})),violations:0},
};
const pages=[['登录页','/?preview=login'],['学习空间','/'],['座位预约','/reservations'],['教室申请','/applications'],['物业报修','/work-orders'],['通知中心','/notifications'],['资源管理','/m1-admin']];
const previewPlugin={name:'isolated-frontend-preview',configureServer(server){server.middlewares.use((req,res,next)=>{
 const url=new URL(req.url,'http://127.0.0.1');
 if(url.pathname==='/__preview_annotations.js'){res.setHeader('Content-Type','text/javascript; charset=utf-8');res.setHeader('Cache-Control','no-store');res.end(readFileSync(new URL('./preview-annotations.js',import.meta.url),'utf8'));return;}
 if(!url.pathname.startsWith('/api/'))return next();
 const p=url.pathname.replace('/api/v1','');let data;
 res.setHeader('Content-Type','application/json; charset=utf-8');res.setHeader('Cache-Control','no-store');
 if(p==='/auth/login'&&req.method==='POST')data={token:'preview-only',user};
 else if(p==='/auth/logout'&&req.method==='POST')data={};
 else if(req.method!=='GET'){res.statusCode=409;res.end(JSON.stringify({message:'仅供界面预览：操作未提交，也不会写入真实系统。'}));return;}
 else if(p.endsWith('/seats'))data=seats;
 else if(p.endsWith('/check-in-code'))data={code:'123456'};
 else data=fixtures[p];
 if(data===undefined){res.statusCode=404;res.end(JSON.stringify({message:'预览未定义此接口'}));return;}
 res.end(JSON.stringify({data}));
 });},transformIndexHtml(){return [{tag:'style',children:'.preview-toolbar{position:relative;z-index:3000;background:#242424;color:#fff;padding:12px 20px;font:12px/1.7 system-ui;display:flex;align-items:center;gap:14px;flex-wrap:wrap}.preview-toolbar span{color:#c5c5bf}.preview-toolbar nav{display:flex;gap:14px;flex-wrap:wrap}.preview-toolbar a{color:#fff;text-decoration:none;border-bottom:1px solid #777}.preview-toolbar a:focus-visible{outline:2px solid #fff}.preview-toolbar a:hover{border-color:#fff}',injectTo:'head'},
 {tag:'div',attrs:{class:'preview-toolbar'},children:'<span>界面预览 / 示例数据 · 综合权限展示 · 业务操作不会提交</span><nav aria-label="预览页面切换">'+pages.map(([name,path])=>`<a href="${path}">${name}</a>`).join('')+'</nav>',injectTo:'body-prepend'},
 {tag:'script',attrs:{type:'module',src:'/__preview_annotations.js'},injectTo:'body'},
 {tag:'script',attrs:{type:'module'},children:`import '/src/main.ts';import {useSession} from '/src/session.ts';if(new URLSearchParams(location.search).get('preview')!=='login'){useSession().user=${JSON.stringify(user)};}`,injectTo:'body'}];}};
const server=await createServer({root,configFile:false,plugins:[vue(),previewPlugin],server:{host:'127.0.0.1',port:5174,strictPort:true}});
await server.listen();console.log('Isolated preview: http://127.0.0.1:5174/');
