(function () {
  'use strict';
  const validId = value => /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n;
  window.createVoucherBatches = function (api, onError) {
    const manualKeys=new Map(), importKeys=new WeakMap();
    return {
      voucherId:'', recipients:'', file:null, busy:false, loading:false,
      tasks:[], hasMore:false, nextId:null, listGeneration:0,
      detail:null, rows:[], rowMore:false, nextRow:null, rowLoading:false, detailGeneration:0,
      requestSignature:null, requestKey:null,
      key(signature) {
        if (!manualKeys.has(signature)) manualKeys.set(signature,crypto.randomUUID());
        this.requestSignature = signature; this.requestKey = manualKeys.get(signature);
        return this.requestKey;
      },
      async load(reset) {
        if (!reset && (this.loading || !this.hasMore)) return;
        if (reset) { this.listGeneration++; this.tasks=[]; this.hasMore=false; this.nextId=null; }
        const generation=this.listGeneration; this.loading=true;
        const params={limit:10}; if(this.nextId) params.beforeId=this.nextId;
        try {
          const {data}=await api.get('/voucher/batches',{params});
          if(generation !== this.listGeneration) return;
          const ids=new Set(this.tasks.map(t=>t.id));
          this.tasks=this.tasks.concat(data.items.filter(t=>!ids.has(t.id)));
          this.hasMore=data.hasMore;this.nextId=data.nextId;
        } catch(error) { if(generation===this.listGeneration) onError(String(error)); }
        finally { if(generation===this.listGeneration) this.loading=false; }
      },
      async open(id) {
        const generation=++this.detailGeneration;
        this.detail=null;this.rows=[];this.rowMore=false;this.nextRow=null;this.rowLoading=true;
        try {
          const {data}=await api.get('/voucher/batches/'+encodeURIComponent(id),{params:{limit:20}});
          if(generation!==this.detailGeneration) return;
          this.detail=data.task || null;this.rows=data.items || [];this.rowMore=!!data.hasMore;this.nextRow=data.nextRow;
        } catch(error) { if(generation===this.detailGeneration) onError(String(error)); }
        finally { if(generation===this.detailGeneration) this.rowLoading=false; }
      },
      async moreRows() {
        if(this.rowLoading || !this.detail || !this.rowMore) return;
        const generation=this.detailGeneration,id=this.detail.id;this.rowLoading=true;
        try {
          const {data}=await api.get('/voucher/batches/'+encodeURIComponent(id),{params:{limit:20,beforeRow:this.nextRow}});
          if(generation!==this.detailGeneration) return;
          const known=new Set(this.rows.map(r=>r.rowNumber));
          this.detail=data.task;this.rows=this.rows.concat(data.items.filter(r=>!known.has(r.rowNumber)));this.rowMore=data.hasMore;this.nextRow=data.nextRow;
        } catch(error) { if(generation===this.detailGeneration) onError(String(error)); }
        finally { if(generation===this.detailGeneration) this.rowLoading=false; }
      },
      async submit() {
        if(this.busy) return;
        const voucherId=this.voucherId.trim();
        const userIds=this.recipients.split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
        if(!validId(voucherId) || !userIds.length || userIds.length>1000 || userIds.some(id=>!validId(id))) {
          onError('请输入正确的券ID和用户ID，每行一个用户，最多1000行');return;
        }
        const requestKey=this.key(JSON.stringify([voucherId,userIds]));this.busy=true;
        try {
          const {data}=await api.post('/voucher/batches',{voucherId,requestKey,userIds});
          await this.load(true);await this.open(data.id);
        } catch(error) { onError(String(error)); }
        finally { this.busy=false; }
      },
      async importFile() {
        if(this.busy) return;
        const voucherId=this.voucherId.trim(),file=this.file;
        if(!validId(voucherId) || !file || !/\.xlsx$/i.test(file.name) || file.size>5*1024*1024) {
          onError('请选择不超过5MiB的.xlsx名单，并填写正确的券ID');return;
        }
        // Keep the key for the exact selected File object across a network retry.
        if(!importKeys.has(file)) importKeys.set(file,new Map());
        const keys=importKeys.get(file);
        if(!keys.has(voucherId)) keys.set(voucherId,crypto.randomUUID());
        this.requestSignature={voucherId,file};this.requestKey=keys.get(voucherId);
        const body=new FormData();body.append('voucherId',voucherId);body.append('requestKey',this.requestKey);body.append('file',file);
        this.busy=true;
        try {
          const {data}=await api.post('/voucher/batches/import',body);
          await this.load(true);await this.open(data.id);
        } catch(error) { onError(String(error)); }
        finally { this.busy=false; }
      },
      async retry() {
        if(this.busy || !this.detail || !(this.detail.retryableFailed>0)) return;
        const id=this.detail.id;this.busy=true;
        try { await api.post('/voucher/batches/'+encodeURIComponent(id)+'/retry');await this.load(true);await this.open(id); }
        catch(error) { onError(String(error)); }
        finally { this.busy=false; }
      }
    };
  };
})();

const SmartLifeVoucherBatchPanel={
  data() { return {batch:window.createVoucherBatches(axios,e=>this.$message.error(e))}; },
  created() { this.batch.load(true); },
  methods:{
    state(value) { return {IMPORTING:'导入中',READY:'待处理',RUNNING:'处理中',COMPLETED:'已完成',PARTIAL:'含失败记录',IMPORT_FAILED:'导入失败',PROCESSING:'等待订单结果',SUCCEEDED:'订单已创建',FAILED:'失败'}[value] || value; },
    selectFile(event) { this.batch.file=event.target.files[0] || null; },
    async submitBatch(importing) {
      if(this.batch.busy) return;
      try { await this.$confirm('将为名单用户创建待支付券订单，用户付款后方可使用。确认提交？','批量发券',{confirmButtonText:'确认提交',cancelButtonText:'返回'}); }
      catch(cancelled) { return; }
      if(importing) await this.batch.importFile(); else await this.batch.submit();
    }
  },
  template:`<details class="batch-panel">
    <summary>批量发券与任务进度</summary>
    <p class="orders-note">按名单生成待支付订单，非免费赠券。成功指订单已落库，用户付款后使用；每人每券限领一次。</p>
    <label>券ID <el-input v-model="batch.voucherId" :disabled="batch.busy" placeholder="已有秒杀券ID" aria-label="批量发券券ID"></el-input></label>
    <label>用户ID名单 <el-input v-model="batch.recipients" :disabled="batch.busy" type="textarea" :rows="3" placeholder="每行一个用户ID，最多1000行" aria-label="发券用户ID名单"></el-input></label>
    <el-button :loading="batch.busy" @click="submitBatch(false)">提交名单</el-button>
    <label class="batch-upload">或导入Excel <input type="file" accept=".xlsx" :disabled="batch.busy" aria-label="Excel发券名单" @change="selectFile"></label>
    <p class="orders-note">仅.xlsx，首行 userId，单列单工作表；用户ID建议设为文本，最多10000行，文件不超过5MiB。</p>
    <el-button :loading="batch.busy" :disabled="!batch.file" @click="submitBatch(true)">导入名单</el-button>
    <div class="batch-toolbar"><strong>发券任务</strong><el-button :loading="batch.loading" @click="batch.load(true)">刷新任务</el-button></div>
    <p v-if="!batch.loading && !batch.tasks.length">暂无有权管理的发券任务</p>
    <article v-for="task in batch.tasks" :key="task.id" class="order-card">
      <div><strong>{{state(task.state)}}</strong><el-button :disabled="batch.busy" @click="batch.open(task.id)">查看明细</el-button></div>
      <small>任务：{{task.id}} · 券：{{task.voucherId}}</small>
      <p>共{{task.total}}行 · 成功{{task.succeeded}} · 失败{{task.failed}} · 待处理{{task.ready}} · 处理中{{task.processing}}</p>
      <p v-if="task.error" class="batch-error">{{task.error}}</p>
    </article>
    <el-button v-if="batch.hasMore" :loading="batch.loading" @click="batch.load(false)">更多任务</el-button>
    <section v-if="batch.detail" class="batch-details" aria-label="发券任务明细">
      <div class="batch-toolbar"><strong>任务明细：{{state(batch.detail.state)}}</strong><el-button :disabled="batch.busy || batch.rowLoading" @click="batch.open(batch.detail.id)">刷新明细</el-button></div>
      <small>任务号：{{batch.detail.id}}</small>
      <p>成功{{batch.detail.succeeded}} / 总计{{batch.detail.total}}；可重试失败{{batch.detail.retryableFailed}}</p>
      <p v-if="batch.detail.error" class="batch-error">{{batch.detail.error}}</p>
      <el-button v-if="batch.detail.retryableFailed>0" :loading="batch.busy" @click="batch.retry()">重试可恢复的失败项</el-button>
      <p class="orders-note">用户不存在、重复领取等不会反复重试。结果未确定的订单继续核对，勿重新发放。</p>
      <article v-for="row in batch.rows" :key="row.rowNumber" class="batch-row">
        <strong>第{{row.rowNumber}}行 · {{state(row.state)}}</strong>
        <p>用户：{{row.userId || '无效'}}<br>订单：{{row.orderId || '—'}}</p>
        <small>尝试{{row.attempts}}次<span v-if="row.retryable"> · 可重试</span></small>
        <p v-if="row.error" class="batch-error">{{row.error}}</p>
      </article>
      <el-button v-if="batch.rowMore" :loading="batch.rowLoading" @click="batch.moreRows()">更多明细</el-button>
    </section>
  </details>`
};
