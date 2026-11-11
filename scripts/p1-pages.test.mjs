import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

function page(file,axios,initial=[]) {
  let definition;const storage=new Map(initial);
  axios.defaults={};axios.interceptors={request:{use(){}},response:{use(){}}};
  const context=vm.createContext({axios,sessionStorage:{get length(){return storage.size;},key:i=>[...storage.keys()][i]??null,
    getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
    console:{log(){}},window:{location:{search:''}},location:{},crypto:{randomUUID:()=> 'opaque-session'},
    FormData:class {append(){}},mountSmartLife:options=>{definition=options;return {};}});
  vm.runInContext(readFileSync(new URL('../frontend/js/common.js',import.meta.url),'utf8'),context);
  const html=readFileSync(new URL('../frontend/'+file,import.meta.url),'utf8');
  const script=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]).find(s=>s.includes('mountSmartLife'));
  vm.runInContext(script,context);return {definition,storage,context,html};
}
const flush=async()=>{await Promise.resolve();await Promise.resolve();await Promise.resolve();};

test('actual response handler preserves actionable image and permission errors',async()=>{
  let rejectResponse;
  const axios={defaults:{},interceptors:{request:{use(){}},response:{use(ok,fail){rejectResponse=fail;}}}};
  vm.runInNewContext(readFileSync(new URL('../frontend/js/common.js',import.meta.url),'utf8'),{
    axios,sessionStorage:{getItem(){return null;}},console:{log(){}},window:{location:{search:''}}
  });
  await assert.rejects(rejectResponse({response:{status:400,data:{success:false,errorMsg:'文件不是有效图片'}}}),error=>error==='文件不是有效图片');
  await assert.rejects(rejectResponse({response:{status:403,data:{success:false,errorMsg:'无权删除该文件'}}}),error=>error==='无权删除该文件');
});

test('actual upload handler stores the server name without a fabricated multipart boundary',async()=>{
  let config;
  const p=page('blog-edit.html',{post:async(path,body,opts)=>{config=opts;return {data:'/blogs/1/2/id.png'};}});
  const state={fileList:[],$refs:{fileInput:{files:[{}]}},$message:{error:assert.fail}};
  p.definition.methods.fileSelected.call(state);await flush();
  assert.deepEqual(state.fileList,['/blogs/1/2/id.png']);
  assert.equal(config?.headers?.['Content-Type'],undefined);
  assert.match(p.html,/:src="['"]\/imgs['"]\s*\+\s*f"/);
});
test('actual delete handler uses DELETE and the original server name',async()=>{
  let sent;
  const p=page('blog-edit.html',{delete:async(path,options)=>{sent={path,name:options.params.name};return {};},get:async()=>{assert.fail('GET must not delete');}});
  const state={fileList:['/blogs/1/2/id.png'],$message:{error:assert.fail}};
  p.definition.methods.deletePic.call(state,0);await flush();
  assert.equal(sent.path,'/upload/blog');assert.equal(sent.name,'/blogs/1/2/id.png');assert.deepEqual(state.fileList,[]);
});
test('blog publishing keeps display URLs while internal filenames stay original',async()=>{
  let body;const p=page('blog-edit.html',{post:async(path,data)=>{body=data;return {};}});
  p.definition.methods.submitBlog.call({params:{title:'fixture'},fileList:['/blogs/1/2/id.png'],selectedShop:{id:1},$message:{error:assert.fail}});await flush();
  assert.equal(body.images,'/imgs/blogs/1/2/id.png');
});
test('actual logout clears token, user info and order cache for the login',async()=>{
  const p=page('info.html',{post:async()=>({})},[['token','old-token'],['userInfo','old-info'],['smartlife:order-session','old-session'],['smartlife:order:old-session:1','old-order']]);
  p.definition.methods.logout.call({$message:{error:assert.fail}});await flush();
  for(const key of ['token','userInfo','smartlife:order-session','smartlife:order:old-session:1']) assert.equal(p.storage.has(key),false,key);
});
