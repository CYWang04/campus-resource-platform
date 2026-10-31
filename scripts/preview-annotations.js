/** Preview-only annotations. No application API calls or production imports. */
const STORAGE_KEY = 'campus-preview-annotations-v1';
const host = document.createElement('div');
host.id = 'preview-annotations';
document.body.append(host);
const shadow = host.attachShadow({ mode: 'open' });
shadow.innerHTML = `
<style>
:host{font:13px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;color:#242424;position:relative;z-index:10000}
*{box-sizing:border-box}button,input,textarea{font:inherit}button{cursor:pointer;background:#fff;color:#242424;border:1px solid #d1d1ca;border-radius:5px;padding:7px 11px;min-height:36px}button:hover{background:#f0f0eb}button:focus-visible,a:focus-visible,textarea:focus-visible{outline:2px solid #b14c24;outline-offset:3px}button.primary{background:#292923;color:#fff;border-color:#292923}button.danger{color:#a64038}button:disabled{opacity:.5;cursor:default}[hidden]{display:none!important}
.launcher{z-index:4;position:fixed;bottom:22px;right:22px;display:flex;gap:6px;background:#fff;padding:7px;border:1px solid #ccc;border-radius:9px;box-shadow:0 5px 24px #0002}.launcher button[aria-pressed=true]{background:#a34120;color:#fff;border-color:#a34120}
.panel{z-index:3;position:fixed;right:22px;bottom:88px;width:350px;max-height:70vh;overflow:auto;background:#fff;border:1px solid #ccc;border-radius:9px;box-shadow:0 8px 32px #0002;padding:18px}.row{display:flex;gap:8px;justify-content:space-between;align-items:center;flex-wrap:wrap}h2{font-size:16px;margin:0}p{margin:8px 0}small,.muted{color:#707069;font-size:12px}.help{font-size:12px;color:#6b6b62;margin:10px 0 15px}.item{border-top:1px solid #e3e3dc;padding:14px 0}.item p{white-space:pre-wrap;overflow-wrap:anywhere}.item button{font-size:12px;padding:5px 9px}.item a{color:#242424}.item .location{display:block;margin:8px 0;font-size:11px;color:#73736b}.tools{border-top:1px solid #ddd;padding-top:14px;margin-top:12px;display:flex;flex-wrap:wrap;gap:8px}
.marker{position:fixed;transform:translate(-50%,-50%);width:27px;height:27px;min-height:27px;border-radius:50%;padding:0;border:2px solid #fff;background:#a34120;color:#fff;box-shadow:0 1px 6px #0005;font-size:12px;z-index:1}.marker:hover{background:#7c2b10}.highlight{position:fixed;pointer-events:none;border:2px solid #a34120;background:#b14c240d;z-index:0}
dialog{color:#242424;background:#fff;border:1px solid #bbb;border-radius:10px;padding:22px;width:430px;max-width:calc(100vw - 28px);box-shadow:0 12px 60px #0004}dialog::backdrop{background:#0004}label{display:block;margin:15px 0 8px}textarea{width:100%;min-height:140px;resize:vertical;padding:12px;border:1px solid #bbb;border-radius:4px;font-size:16px}dialog .row{margin-top:16px}.status{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#292923;color:#fff;padding:10px 18px;border-radius:6px;max-width:calc(100vw - 32px);z-index:3}.selected{border-left:3px solid #a34120;padding-left:10px}.count{font-variant-numeric:tabular-nums}
@media(max-width:600px){.launcher{right:10px;bottom:10px}.panel{right:10px;bottom:77px;width:calc(100vw - 20px);max-height:60vh}.status{bottom:80px}button{min-height:44px}.marker{min-height:32px;width:32px;height:32px}}
@media print{.launcher,.panel,dialog,.status{display:none}}
</style>
<div id="markers"></div><div class="highlight" hidden></div>
<div class="launcher" aria-label="预览批注工具"><button id="mode" aria-pressed="false">＋ 添加批注</button><button id="list-toggle" aria-expanded="false">批注 <span class="count">0</span></button></div>
<section class="panel" aria-label="批注列表" hidden><div class="row"><h2>页面改进批注</h2><button id="close-panel" aria-label="关闭批注列表">关闭</button></div><p class="help">添加批注后点击页面位置。批注仅保存在当前浏览器；导出文件发到聊天即可交给我修改。</p><div class="row"><button id="scope" aria-pressed="false">查看全部页面</button><span id="page-count" class="muted"></span></div><div id="notes"></div><div class="tools"><button id="export-md">导出修改清单</button><button id="export-json">导出定位数据</button><button id="copy">复制全部批注</button></div></section>
<dialog aria-labelledby="editor-title"><form method="dialog"><h2 id="editor-title">添加批注</h2><p id="editor-target" class="muted"></p><label for="note-text">希望如何修改？</label><textarea id="note-text" maxlength="4000" placeholder="例如：标题字号小一点，缩短下方留白。" required></textarea><p id="editor-error" role="alert"></p><div class="row"><button id="cancel" type="button">取消</button><button class="primary" id="save" type="submit">保存批注</button></div></form></dialog>
<div class="status" role="status" hidden></div>`;
const $ = (s) => shadow.querySelector(s);
let notes = [], mode = false, allPages = false, draft = null, selectedId = null, queued = false;
let statusTimer;
const names = {'/':'学习空间','/reservations':'座位预约','/applications':'教室申请','/work-orders':'物业报修','/notifications':'通知中心','/m1-admin':'资源管理'};
function pageInfo() {
  const login = !!document.querySelector('#app .login');
  return {key:login?'login':location.pathname,title:login?'登录页':names[location.pathname]??location.pathname,url:login?'/?preview=login':location.pathname};
}
function toast(text) { $('.status').textContent=text;$('.status').hidden=false;clearTimeout(statusTimer);statusTimer=setTimeout(()=>$('.status').hidden=true,3500); }
function validNote(n) { return n && typeof n.id==='string' && typeof n.text==='string' && typeof n.page==='string' && typeof n.selector==='string' && Number.isFinite(n.x) && Number.isFinite(n.y); }
try { const data=JSON.parse(localStorage.getItem(STORAGE_KEY)??'[]');if(!Array.isArray(data)||!data.every(validNote))throw new Error('invalid');notes=data; }
catch { toast('本地批注无法读取。新增批注前请先导出或备份原数据。'); }
function persist(next) {
  try {localStorage.setItem(STORAGE_KEY,JSON.stringify(next));notes=next;return true;}
  catch {toast('保存失败：浏览器存储不可用或空间不足，请先复制/导出当前批注。');return false;}
}
function selectorFor(element) {
  const parts=[];let e=element;
  while(e && e.id!=='app') {const parent=e.parentElement;if(!parent)break;parts.unshift(e.tagName.toLowerCase()+':nth-child('+(Array.from(parent.children).indexOf(e)+1)+')');e=parent;}
  return '#app > '+parts.join(' > ');
}
function targetText(e) {return (e.textContent||e.getAttribute('placeholder')||e.getAttribute('aria-label')||'').trim().replace(/\s+/g,' ').slice(0,120);}
function elementFor(note) {try {const e=document.querySelector(note.selector);if(!e)return null;const text=targetText(e);if(note.targetText && !text.includes(note.targetText.slice(0,50)))return null;return e;}catch{return null;}}
function setMode(value) {mode=value;$('#mode').setAttribute('aria-pressed',String(mode));$('#mode').textContent=mode?'完成标记（Esc）':'＋ 添加批注';if(!mode)$('.highlight').hidden=true;}
function openEditor(note,editing=false) {draft={...note};$('#editor-title').textContent=editing?'编辑批注':'添加批注';$('#editor-target').textContent=note.pageTitle+' / '+(note.targetText||'页面区域');$('#note-text').value=note.text??'';$('#editor-error').textContent='';$('dialog').showModal();$('#note-text').focus();}
function displayList() {
  const info=pageInfo(), list=allPages?notes:notes.filter(n=>n.page===info.key);
  $('.count').textContent=String(notes.length);$('#page-count').textContent=(allPages?'全部页面':info.title)+' · '+list.length+' 条';
  $('#notes').replaceChildren();
  if(!list.length){const p=document.createElement('p');p.className='help';p.textContent='还没有批注。开启“添加批注”，再点击需要修改的位置。';$('#notes').append(p);}
  for(const n of list) {
    const item=document.createElement('article');item.className='item'+(n.id===selectedId?' selected':'');
    const title=document.createElement('strong');title.textContent='#'+n.number+' · '+n.pageTitle;
    const text=document.createElement('p');text.textContent=n.text;
    const target=document.createElement('span');target.className='location';target.textContent='位置：'+(n.targetText||n.tag)+' · '+n.viewport.width+'px 视口';
    const actions=document.createElement('div');actions.className='row';
    const locate=document.createElement('button');locate.textContent=n.page===info.key?'定位':'前往页面';locate.onclick=()=>{
      if(n.page!==pageInfo().key){location.href=n.url;return;}
      const e=elementFor(n);if(!e){toast('该元素已变化或尚未展开，批注仍保留在列表中。');return;}
      if(innerWidth<600)$('.panel').hidden=true;
      e.scrollIntoView({block:'center',behavior:'auto'});selectedId=n.id;displayList();schedule();
    };
    const edit=document.createElement('button');edit.textContent='编辑';edit.onclick=()=>openEditor(n,true);
    const remove=document.createElement('button');remove.textContent='删除';remove.className='danger';remove.onclick=()=>{if(confirm('删除这条批注？')&&persist(notes.filter(x=>x.id!==n.id))){render();toast('批注已删除');}};
    actions.append(locate,edit,remove);item.append(title,text,target,actions);$('#notes').append(item);
  }
}
function positionMarkers() {
  const current=pageInfo().key;
  for(const marker of $('#markers').children){const n=notes.find(n=>n.id===marker.dataset.noteId);const e=n&&n.page===current?elementFor(n):null;
    if(!e){marker.hidden=true;continue;}const r=e.getBoundingClientRect();const x=r.left+n.x*r.width,y=r.top+n.y*r.height;
    marker.hidden=r.width===0||r.height===0||y<0||y>innerHeight||x<0||x>innerWidth;
    marker.style.left=Math.max(18,Math.min(innerWidth-18,x))+'px';marker.style.top=Math.max(18,Math.min(innerHeight-18,y))+'px';
  }
}
function schedule(){if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;positionMarkers();});}
function render() {
  displayList();$('#markers').replaceChildren();
  notes.filter(n=>n.page===pageInfo().key).forEach(n=>{const b=document.createElement('button');b.className='marker';b.textContent=String(n.number);b.dataset.noteId=n.id;b.setAttribute('aria-label','批注 '+n.number+'：'+n.text);b.onclick=()=>{selectedId=n.id;$('.panel').hidden=false;$('#list-toggle').setAttribute('aria-expanded','true');displayList();};$('#markers').append(b);});schedule();
}
function captureTarget(event) {const e=event.target;if(!(e instanceof Element)||!e.closest('#app'))return null;return e.closest('button,a,input,textarea,label,h1,h2,h3,p,td,th,.el-select,.el-form-item,.room-card,.panel')??e;}
window.addEventListener('pointermove',e=>{if(!mode||$('dialog').open)return;const target=captureTarget(e);if(!target){$('.highlight').hidden=true;return;}const r=target.getBoundingClientRect();Object.assign($('.highlight').style,{left:r.left+'px',top:r.top+'px',width:r.width+'px',height:r.height+'px'});$('.highlight').hidden=false;},true);
window.addEventListener('click',e=>{
  if(!mode||$('dialog').open||e.composedPath().includes(host))return;
  const target=captureTarget(e);if(!target)return;e.preventDefault();e.stopImmediatePropagation();const r=target.getBoundingClientRect(), info=pageInfo();
  $('.highlight').hidden=true;
  openEditor({id:crypto.randomUUID(),number:Math.max(0,...notes.map(n=>n.number))+1,page:info.key,pageTitle:info.title,url:info.url,selector:selectorFor(target),tag:target.tagName.toLowerCase(),targetText:targetText(target),x:r.width?Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)):.5,y:r.height?Math.max(0,Math.min(1,(e.clientY-r.top)/r.height)):.5,viewport:{width:innerWidth,height:innerHeight},createdAt:new Date().toISOString(),text:''});
},true);
$('#mode').onclick=()=>{setMode(!mode);if(mode)toast('点击页面上需要修改的位置。Esc 退出批注模式。');};
$('#list-toggle').onclick=()=>{$('.panel').hidden=!$('.panel').hidden;$('#list-toggle').setAttribute('aria-expanded',String(!$('.panel').hidden));displayList();};
$('#close-panel').onclick=()=>{$('.panel').hidden=true;$('#list-toggle').setAttribute('aria-expanded','false');};
$('#scope').onclick=()=>{allPages=!allPages;$('#scope').textContent=allPages?'只看当前页面':'查看全部页面';$('#scope').setAttribute('aria-pressed',String(allPages));displayList();};
$('#cancel').onclick=()=>$('dialog').close();
$('form').onsubmit=e=>{e.preventDefault();const text=$('#note-text').value.trim();if(!text){$('#editor-error').textContent='请填写修改意见。';return;}const note={...draft,text,updatedAt:new Date().toISOString()};const next=notes.some(n=>n.id===note.id)?notes.map(n=>n.id===note.id?note:n):[...notes,note];if(persist(next)){$('dialog').close();selectedId=note.id;render();toast('已保存在当前浏览器。可继续点击添加批注。');}};
window.addEventListener('keydown',e=>{if(e.key==='Escape')setMode(false);});
window.addEventListener('scroll',schedule,true);window.addEventListener('resize',schedule);
function markdown(){return '# 校园资源平台前端修改清单\n\n> 预览示例数据；请同时提供定位 JSON。\n\n'+notes.map(n=>'## #'+n.number+' '+n.pageTitle+'\n\n- 页面：'+n.url+'\n- 位置：'+(n.targetText||n.tag)+'\n- 视口：'+n.viewport.width+' × '+n.viewport.height+'\n- 元素：`'+n.selector+'`\n\n'+n.text+'\n').join('\n');}
function download(text,type,extension){const blob=new Blob([text],{type});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='campus-frontend-comments-'+new Date().toISOString().slice(0,10)+'.'+extension;a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);toast('已导出 '+notes.length+' 条批注。将文件发送到聊天即可。');}
$('#export-md').onclick=()=>download(markdown(),'text/markdown;charset=utf-8','md');
$('#export-json').onclick=()=>download(JSON.stringify({version:1,exportedAt:new Date().toISOString(),notes},null,2),'application/json;charset=utf-8','json');
$('#copy').onclick=async()=>{try{await navigator.clipboard.writeText(markdown());toast('已复制全部批注，可粘贴到聊天。');}catch{toast('复制不可用，请使用导出修改清单。');}};
window.addEventListener('storage',e=>{if(e.key!==STORAGE_KEY)return;try{const next=JSON.parse(e.newValue??'[]');if(Array.isArray(next)&&next.every(validNote)){notes=next;render();}}catch{toast('另一标签页的批注数据无法读取。');}});
let lastPage=pageInfo().key;
new MutationObserver(()=>{const current=pageInfo().key;if(current!==lastPage){lastPage=current;setMode(false);render();}else schedule();}).observe(document.getElementById('app'),{childList:true,subtree:true,characterData:true});
render();
