import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('network timeout is reported without dereferencing a missing response', async () => {
  let errorHandler;
  const axios = {defaults:{},interceptors:{request:{use(){}},response:{use(ok,fail){errorHandler=fail;}}}};
  vm.runInNewContext(readFileSync(new URL('../frontend/js/common.js', import.meta.url),'utf8'), {
    axios, sessionStorage:{getItem(){return null;}},console:{log(){}},window:{location:{search:''}}
  });
  let pending;
  assert.doesNotThrow(() => {pending=errorHandler({code:'ECONNABORTED',message:'timeout'});});
  await assert.rejects(pending, error => String(error).includes('超时'));
});

const largeId = '658153834886987779';
function harness({http,storage=new Map(),session='owner-session',maxPolls=2}={}) {
  const tasks=[]; const changes=[]; let sequence=0;
  const context={};
  vm.runInNewContext(readFileSync(new URL('../frontend/js/order-request.js',import.meta.url),'utf8'),context);
  const client=context.SmartLifeOrderClient.create({http,sessionKey:session,
    storage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
    newKey:()=>`request-test-key-${++sequence}`,maxPolls,
    schedule:fn=>{tasks.push(fn);return fn;},cancel:fn=>{const i=tasks.indexOf(fn);if(i>=0)tasks.splice(i,1);},
    onChange:(id,status)=>changes.push({id,...status})});
  return {client,tasks,changes,storage};
}

test('accepted HTTP response remains processing until a created result is queried',async()=>{
  const h=harness({http:{post:async()=>({data:largeId}),get:async()=>({data:{orderId:largeId,state:'PENDING',message:'处理中'}})}});
  await h.client.submit('7');
  assert.equal(h.changes.at(-1).state,'PENDING');
  assert.equal(h.changes.some(change=>change.state==='CREATED'),false);
  assert.equal(h.tasks.length,1);
});

test('large order IDs remain exact strings through submit and result query',async()=>{
  const h=harness({http:{post:async()=>({data:largeId}),get:async()=>({data:{orderId:largeId,state:'CREATED',message:'订单创建成功'}})}});
  await h.client.submit('7');
  assert.equal(h.changes.at(-1).orderId,largeId);
  assert.equal(h.changes.at(-1).state,'CREATED');
  assert.equal(h.tasks.length,0);
});

test('timeout keeps the same idempotency key when the user continues',async()=>{
  const keys=[];let attempts=0;
  const h=harness({http:{post:async(path,body,config)=>{keys.push(config.headers['Idempotency-Key']);if(++attempts===1)throw Error('timeout');return {data:largeId};},
    get:async()=>{throw Error('network unavailable');}}});
  await h.client.submit('7');
  await h.client.submit('7');
  assert.equal(keys.length,2);
  assert.equal(keys[0],keys[1]);
  assert.equal(h.changes.at(-1).state,'UNKNOWN');
});

test('polling is bounded and lets the user continue without declaring failure',async()=>{
  let gets=0;
  const h=harness({http:{post:async()=>({data:largeId}),get:async()=>{gets++;return {data:{orderId:largeId,state:'PENDING',message:'处理中'}};}}});
  await h.client.submit('7');
  for(let safety=0;h.tasks.length&&safety<10;safety++)await h.tasks.shift()();
  assert.equal(gets,2);
  assert.equal(h.tasks.length,0);
  assert.equal(h.changes.at(-1).canContinue,true);
  assert.equal(h.changes.at(-1).state,'PENDING');
});

test('page reload restores the existing key without silently submitting a new purchase',async()=>{
  const first=harness({http:{post:async()=>{throw Error('timeout');},get:async()=>{throw Error('offline');}}});
  await first.client.submit('7');
  let posts=0;let query;
  const second=harness({storage:first.storage,http:{post:async()=>{posts++;},get:async path=>{query=path;return {data:{orderId:largeId,state:'CREATED',message:'订单创建成功'}};}}});
  await second.client.restore('7');
  assert.equal(posts,0);
  assert.equal(query,'/voucher-order/request/request-test-key-1');
  assert.equal(second.changes.at(-1).state,'CREATED');
});

test('another login session does not restore the previous sessions order',async()=>{
  const first=harness({http:{post:async()=>({data:largeId}),get:async()=>({data:{orderId:largeId,state:'PENDING'}})}});
  await first.client.submit('7');
  let reads=0;
  const second=harness({storage:first.storage,session:'different-owner',http:{get:async()=>{reads++;}}});
  await second.client.restore('7');
  assert.equal(reads,0);
  assert.equal(second.changes.length,0);
});

test('continue after an offline restore of a rejection never starts another purchase',async()=>{
  const keys=[];let offline=false;
  const h=harness({http:{post:async(path,body,config)=>{keys.push(config.headers['Idempotency-Key']);return {data:largeId};},
    get:async()=>{if(offline)throw Error('offline');return {data:{orderId:largeId,state:'REJECTED',message:'库存不足'}};}}});
  await h.client.submit('7');
  offline=true;await h.client.restore('7');
  await h.client.continue('7');
  assert.equal(keys.length,2);
  assert.equal(keys[0],keys[1]);
});

function pageContext(storage,postResult) {
  let config;
  const axios={defaults:{},interceptors:{request:{use(){}},response:{use(){}}},
    post:async()=>({data:postResult}),get:async()=>({data:{orderId:largeId,state:'PENDING'}})};
  const context=vm.createContext({axios,sessionStorage:{get length(){return storage.size;},key:index=>[...storage.keys()][index]??null,getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
    console:{log(){}},window:{location:{search:'?id=1'}},location:{},crypto:{randomUUID:()=> 'opaque-random-session-key'},
    setTimeout:()=>1,clearTimeout(){},mountSmartLife:options=>{config=options;return {};}});
  vm.runInContext(readFileSync(new URL('../frontend/js/common.js',import.meta.url),'utf8'),context);
  return {context,getConfig:()=>config};
}

test('actual shop page never puts the bearer credential in an order storage key',async()=>{
  const token='private-bearer-token-A';const storage=new Map([['token',token]]);
  const h=pageContext(storage,largeId);
  vm.runInContext(readFileSync(new URL('../frontend/js/order-request.js',import.meta.url),'utf8'),h.context);
  const html=readFileSync(new URL('../frontend/shop-detail.html',import.meta.url),'utf8');
  const inline=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match=>match[1]).find(script=>script.includes('mountSmartLife'));
  vm.runInContext(inline,h.context);
  const page={orderStates:{},queryShopById(){},queryVoucher(){}};
  h.getConfig().created.call(page);
  await page.orderClient.submit('7');
  const keys=[...storage.keys()].filter(key=>key.startsWith('smartlife:order:'));
  assert.equal(keys.length,1);
  assert.equal(keys.some(key=>key.includes(token)),false);
});

test('actual login rotates the opaque order session namespace',async()=>{
  const storage=new Map([['token','old-bearer'],['smartlife:order-session','old-session']]);
  const h=pageContext(storage,'new-bearer');
  const html=readFileSync(new URL('../frontend/login.html',import.meta.url),'utf8');
  const inline=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match=>match[1]).find(script=>script.includes('mountSmartLife'));
  vm.runInContext(inline,h.context);
  h.getConfig().methods.login.call({radio:true,form:{phone:'13900000000',code:'123456'},$message:{error:assert.fail}});
  await Promise.resolve();await Promise.resolve();
  assert.equal(storage.get('token'),'new-bearer');
  assert.notEqual(storage.get('smartlife:order-session'),'old-session');
});

test('recovery CLI rejects explicit nonpositive IDs before resolving any JAR',()=>{
  const script=fileURLToPath(new URL('./recover-orders.ps1',import.meta.url));
  for(const id of ['0','-1']){
    const result=spawnSync('pwsh',['-NoProfile','-File',script,'-Execute','-OrderId',id,'-Jar','does-not-exist.jar'],{encoding:'utf8'});
    assert.equal(result.error,undefined);
    assert.notEqual(result.status,0);
    assert.match(result.stderr,/OrderId/);
    assert.doesNotMatch(result.stderr,/Resolve-Path/);
  }
});
