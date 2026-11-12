import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync,existsSync} from 'node:fs';
import {compile} from '@vue/compiler-dom';
const file=new URL('../frontend/js/order-lifecycle.js',import.meta.url);
function client(api,error=()=>{}){const context={window:{},console,Date};vm.runInNewContext(existsSync(file)?readFileSync(file,'utf8'):'',context);assert.equal(typeof context.window.createOrderLifecycle,'function','missing actual lifecycle client');return context.window.createOrderLifecycle(api,error);}
test('list uses server cursor as exact strings and resets it on a new filter',async()=>{
 const calls=[];const c=client({get:async(path,config)=>{calls.push(config.params);return {data:{items:[{id:'9007199254740997'}],nextTime:'2026-11-12T00:00:00Z',nextId:'9007199254740997',hasMore:true}};}});
 await c.load(true);await c.load(false);assert.equal(calls[1].beforeId,'9007199254740997');c.status='2';await c.load(true);assert.equal(calls[2].beforeId,undefined);assert.equal(calls[2].status,'2');assert.equal(c.items.length,1);
});
test('late response from previous filter cannot overwrite current orders',async()=>{
 const pending=[];const c=client({get:()=>new Promise(resolve=>pending.push(resolve))});const a=c.load(true);c.status='2';const b=c.load(true);pending[1]({data:{items:[{id:'paid'}],hasMore:false}});await b;pending[0]({data:{items:[{id:'old'}],hasMore:false}});await a;assert.equal(c.items[0].id,'paid');
});
test('duplicate action is guarded and authoritative detail and list refresh after success',async()=>{
 let finish,posts=0;const calls=[];const c=client({post:()=>{posts++;return new Promise(r=>finish=r);},get:async path=>{calls.push(path);return {data:path.includes('/mine')?{items:[],hasMore:false}:{id:'9007199254740999',status:2,canPay:false}};}});
 c.detail={id:'9007199254740999',canPay:true};const a=c.action('pay');await c.action('pay');assert.equal(posts,1);finish({});await a;assert.equal(c.detail.status,2);assert.ok(calls.includes('/voucher-order/detail/9007199254740999'));assert.ok(calls.includes('/voucher-order/mine'));assert.equal(c.busy,false);
});
test('foreign merchant lookup clears stale detail and reports server error',async()=>{
 const errors=[];const c=client({get:async()=>{throw '订单不存在';}},e=>errors.push(e));c.detail={id:'old'};c.lookup='123456789012345678';await c.lookupOrder();assert.equal(c.detail,null);assert.equal(errors[0],'订单不存在');assert.equal(c.detailOpen,false);
});
test('unsupported action never posts and merchant redemption uses the same exact order id',async()=>{
 const calls=[];const c=client({post:async path=>{calls.push(path);},get:async path=>({data:path.includes('/mine')?{items:[],hasMore:false}:{id:'9007199254740999',canRedeem:false}})});c.detail={id:'9007199254740999',canRedeem:true};c.merchantDetail=true;await c.action('delete');assert.equal(calls.length,0);await c.action('redeem');assert.equal(calls[0],'/voucher-order/9007199254740999/redeem');
});
test('actual order component template compiles under the pinned Vue compiler',()=>{
 const context=vm.createContext({window:{},Date});vm.runInContext(readFileSync(file,'utf8')+'\nwindow.panel=SmartLifeOrderPanel;',context);
 compile(context.window.panel.template,{onError(error){throw error;}});
});
test('successful mutation still refreshes list when first detail read fails transiently',async()=>{
 let details=0,lists=0;const c=client({post:async()=>({}),get:async path=>{
  if(path.includes('/mine')){lists++;return {data:{items:[{id:'123',status:2}],hasMore:false}};}
  if(++details===1)throw 'temporary read timeout';return {data:{id:'123',status:2,canPay:false}};
 }});c.items=[{id:'123',status:1}];c.detail={id:'123',canPay:true};await c.action('pay');assert.equal(c.detail.status,2);assert.equal(lists,1);assert.equal(c.items[0].status,2);
});
