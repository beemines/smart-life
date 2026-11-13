import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync,existsSync} from 'node:fs';
import {compile} from '@vue/compiler-dom';
const file=new URL('../frontend/js/voucher-batches.js',import.meta.url);
const task=(id='9007199254740997')=>({id,voucherId:'123',shopId:'1',state:'READY',total:2,ready:2,processing:0,succeeded:0,failed:0,retryableFailed:0,error:null,createdAt:'2026-11-13T00:00:00Z'});
const row={rowNumber:2,userId:'9007199254740999',orderId:'9007199254740998',state:'PROCESSING',retryable:false,attempts:1,error:null};
function setup(api,onError=()=>{}) {
 let keys=0;const scope={window:{},console,FormData,crypto:{randomUUID:()=>`request-key-number-${++keys}`}};
 vm.runInNewContext(existsSync(file)?readFileSync(file,'utf8'):'',scope);
 assert.equal(typeof scope.window.createVoucherBatches,'function','actual batch client is missing');
 return {client:scope.window.createVoucherBatches(api,onError),scope};
}
test('submit keeps exact recipient IDs and rejects out of range IDs before HTTP',async()=>{
 const calls=[];const {client:c}=setup({post:async(p,b)=>{calls.push([p,b]);return {data:task()};},get:async p=>({data:p.includes('?')?{}:{items:[],hasMore:false}})});
 c.voucherId='123';c.recipients='9007199254740999\n2\n2';await c.submit();
 assert.equal(calls[0][1].userIds[0],'9007199254740999');assert.equal(calls[0][1].userIds.length,3);
 c.recipients='9223372036854775808';await c.submit();assert.equal(calls.length,1);
});
test('uncertain submit and retry preserve request key until payload changes',async()=>{
 const keys=[];const {client:c}=setup({post:async(p,b)=>{keys.push(b.requestKey);throw 'network timeout';}});
 c.voucherId='123';c.recipients='1';await c.submit();await c.submit();assert.equal(keys[0],keys[1]);
 c.recipients='2';await c.submit();assert.notEqual(keys[1],keys[2]);assert.equal(c.recipients,'2');
});
test('double click while submit is pending cannot create a second batch',async()=>{
 let finish,posts=0;const {client:c}=setup({post:()=>{posts++;return new Promise(r=>finish=r);},get:async()=>({data:{items:[],hasMore:false}})});
 c.voucherId='123';c.recipients='1';const first=c.submit();await c.submit();assert.equal(posts,1);finish({data:task()});await first;assert.equal(c.busy,false);
});
test('manual retry retains its identity across an intervening import',async()=>{
 const keys=[];const {client:c}=setup({post:async(p,b)=>{keys.push(p.endsWith('/import')?b.get('requestKey'):b.requestKey);throw 'timeout';}});
 c.voucherId='123';c.recipients='1';await c.submit();c.file=new File(['x'],'a.xlsx');await c.importFile();await c.submit();assert.equal(keys[0],keys[2]);
});
test('original file retry retains its identity across an intervening manual task',async()=>{
 const keys=[];const {client:c}=setup({post:async(p,b)=>{keys.push(p.endsWith('/import')?b.get('requestKey'):b.requestKey);throw 'timeout';}});
 c.voucherId='123';c.file=new File(['x'],'a.xlsx');await c.importFile();c.recipients='1';await c.submit();await c.importFile();assert.equal(keys[0],keys[2]);
});
test('xlsx upload sends file and fields without fabricating multipart headers',async()=>{
 let request;const {client:c}=setup({post:async(...args)=>{request=args;return {data:task()};},get:async()=>({data:{items:[],hasMore:false}})});
 c.voucherId='123';c.file=new File(['xlsx fixture'],'recipients.xlsx');await c.importFile();
 assert.equal(request[0],'/voucher/batches/import');assert.equal(request[1].get('voucherId'),'123');assert.equal(request[1].get('file').name,'recipients.xlsx');assert.equal(request[2],undefined);
});
test('manual identity survives a different payload and normalizes whitespace',async()=>{
 const keys=[];const {client:c}=setup({post:async(p,b)=>{keys.push(b.requestKey);throw 'timeout';}});
 c.voucherId='123';c.recipients='1';await c.submit();c.recipients='2';await c.submit();
 c.voucherId=' 123 ';c.recipients=' 1\n';await c.submit();
 assert.notEqual(keys[0],keys[1]);assert.equal(keys[0],keys[2]);
});
test('import identity distinguishes same-name same-size files and voucher IDs',async()=>{
 const keys=[];const {client:c}=setup({post:async(p,b)=>{keys.push(b.get('requestKey'));throw 'timeout';}});
 const original=new File(['x'],'a.xlsx'),different=new File(['y'],'a.xlsx');
 c.voucherId='123';c.file=original;await c.importFile();c.file=different;await c.importFile();
 c.file=original;c.voucherId='124';await c.importFile();c.voucherId='123';await c.importFile();
 assert.notEqual(keys[0],keys[1]);assert.notEqual(keys[0],keys[2]);assert.equal(keys[0],keys[3]);
});
test('late task selection response cannot replace currently selected task',async()=>{
 const requests=[];const {client:c}=setup({get:()=>new Promise(resolve=>requests.push(resolve))});
 const old=c.open('111');const current=c.open('222');requests[1]({data:{task:task('222'),items:[row],hasMore:false,nextRow:null}});await current;
 requests[0]({data:{task:task('111'),items:[],hasMore:false,nextRow:null}});await old;assert.equal(c.detail.id,'222');assert.equal(c.rows[0].userId,'9007199254740999');
});
test('task detail pagination uses row cursor and preserves task ID string',async()=>{
 const calls=[];const {client:c}=setup({get:async(p,o)=>{calls.push([p,o]);return {data:{task:task(),items:[row],hasMore:calls.length===1,nextRow:2}};}});
 await c.open('9007199254740997');await c.moreRows();assert.equal(calls[1][0],'/voucher/batches/9007199254740997');assert.equal(calls[1][1].params.beforeRow,2);
});
test('only retryable failures are submitted and detail refreshes from authority',async()=>{
 const posts=[];const {client:c}=setup({post:async p=>{posts.push(p);return {data:{...task(),retryableFailed:0}};},get:async p=>({data:p==='/voucher/batches'?{items:[],hasMore:false}:{task:task(),items:[row],hasMore:false,nextRow:null}})});
 c.detail=task();await c.retry();assert.equal(posts.length,0);c.detail.retryableFailed=1;await c.retry();assert.equal(posts[0],'/voucher/batches/9007199254740997/retry');assert.equal(c.rows.length,1);
});
test('foreign or unavailable task clears old recipient details',async()=>{
 const errors=[];const {client:c}=setup({get:async()=>{throw '无权查看';}},e=>errors.push(e));c.detail=task();c.rows=[row];await c.open('333');assert.equal(c.detail,null);assert.equal(c.rows.length,0);assert.equal(errors[0],'无权查看');
});
test('actual existing-page batch component template compiles',()=>{
 const {scope}=setup({});vm.runInNewContext('window.panel=SmartLifeVoucherBatchPanel;',scope);
 compile(scope.window.panel.template,{onError(e){throw e;}});
});
