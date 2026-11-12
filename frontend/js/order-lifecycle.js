(function () {
  'use strict';
  window.createOrderLifecycle = function (api, onError) {
    return {
      items: [], status: '', nextTime: null, nextId: null, hasMore: false,
      loading: false, generation: 0, busy: false, detail: null,
      detailOpen: false, merchantDetail: false, merchant: false, lookup: '',
      async capabilities() {
        try { this.merchant = (await api.get('/voucher-order/capabilities')).data.merchant === true; }
        catch (error) { onError(String(error)); }
      },
      async load(reset) {
        if (!reset && (this.loading || !this.hasMore)) return;
        if (reset) { this.generation++; this.items = []; this.nextTime = null; this.nextId = null; this.hasMore = false; }
        const generation = this.generation;
        this.loading = true;
        const params = {limit: 10};
        if (this.status) params.status = this.status;
        if (this.nextTime && this.nextId) { params.beforeTime = this.nextTime; params.beforeId = this.nextId; }
        try {
          const {data} = await api.get('/voucher-order/mine', {params});
          if (generation !== this.generation) return;
          const known = new Set(this.items.map(item => item.id));
          this.items = this.items.concat(data.items.filter(item => !known.has(item.id)));
          this.nextTime = data.nextTime; this.nextId = data.nextId; this.hasMore = data.hasMore;
        } catch (error) { if (generation === this.generation) onError(String(error)); }
        finally { if (generation === this.generation) this.loading = false; }
      },
      async open(id, merchant) {
        if (this.busy) return;
        this.detail = null; this.detailOpen = false; this.merchantDetail = !!merchant;
        this.busy = true;
        try {
          const {data} = await api.get('/voucher-order/' + (merchant ? 'merchant/' : 'detail/') + encodeURIComponent(id));
          this.detail = data; this.detailOpen = true;
        } catch (error) { onError(String(error)); }
        finally { this.busy = false; }
      },
      async lookupOrder() {
        const id = this.lookup.trim();
        if (!/^[1-9][0-9]{0,18}$/.test(id)) { this.detail = null; this.detailOpen = false; onError('请输入正确的订单号'); return; }
        await this.open(id, true);
      },
      async action(action) {
        const permission = {pay: 'canPay', cancel: 'canCancel', refund: 'canRefund', redeem: 'canRedeem'}[action];
        if (!permission || this.busy || !this.detail || !this.detail[permission]) return;
        const id = this.detail.id, merchant = this.merchantDetail;
        this.busy = true;
        try {
          await api.post('/voucher-order/' + encodeURIComponent(id) + '/' + action);
          this.detail = (await api.get('/voucher-order/' + (merchant ? 'merchant/' : 'detail/') + encodeURIComponent(id))).data;
          await this.load(true);
        } catch (error) {
          onError(String(error));
          // A competing payment/close/redeem may have changed the state. Refresh it.
          try {
            this.detail = (await api.get('/voucher-order/' + (merchant ? 'merchant/' : 'detail/') + encodeURIComponent(id))).data;
            await this.load(true);
          }
          catch (refreshError) { this.detail = null; this.detailOpen = false; }
        } finally { this.busy = false; }
      }
    };
  };
})();

const SmartLifeOrderPanel = {
  data() { return {client: window.createOrderLifecycle(axios, error => this.$message.error(error))}; },
  created() { this.client.load(true); this.client.capabilities(); },
  methods: {
    statusName(status) { return {1:'待支付',2:'已支付',3:'已核销',4:'已取消',5:'退款中',6:'已退款'}[status] || '需核对'; },
    money(value) { return value == null ? '待核对' : '￥' + (value / 100).toFixed(2); },
    time(value) { return value ? new Date(value).toLocaleString('zh-CN', {hour12:false}) : '—'; },
    async confirmAction(action) {
      const text = {pay:'确认模拟支付？不发生真实扣款。',cancel:'确认取消？返还库存后仍不能重新领取同一张券。',refund:'确认全额模拟退款？不发生真实资金操作，退款后不可核销。',redeem:'确认本店已完成服务并核销此券？'}[action];
      try { await this.$confirm(text, '确认操作', {confirmButtonText:'确认',cancelButtonText:'返回'}); }
      catch (cancelled) { return; }
      await this.client.action(action);
    }
  },
  template: `
  <section class="orders-panel">
    <div class="orders-toolbar">
      <label>订单状态 <select v-model="client.status" @change="client.load(true)" aria-label="订单状态">
        <option value="">全部</option><option value="1">待支付</option><option value="2">已支付</option>
        <option value="3">已核销</option><option value="4">已取消</option><option value="5">退款中</option><option value="6">已退款</option>
      </select></label>
      <el-button :loading="client.loading" @click="client.load(true)">刷新订单</el-button>
    </div>
    <p class="orders-note">模拟支付与退款，不涉及真实资金；每人每券限领一次，取消或退款后不可重领。</p>
    <p v-if="!client.loading && !client.items.length" role="status">暂无该状态的订单</p>
    <article v-for="order in client.items" :key="order.id" class="order-card">
      <div><strong>{{order.title || '历史订单'}}</strong><span>{{statusName(order.status)}}</span></div>
      <p>{{order.shopName || '商户资料待核对'}} · {{money(order.payValue)}}</p>
      <small>订单号：{{order.id}}</small>
      <div><small>{{time(order.createTime)}}</small><el-button :disabled="client.busy" @click="client.open(order.id,false)">查看详情</el-button></div>
    </article>
    <el-button v-if="client.hasMore" :loading="client.loading" @click="client.load(false)">加载更多</el-button>
    <section v-if="client.merchant" class="merchant-panel">
      <h4>商户核销</h4><p class="orders-note">仅能查询和核销您有权限管理的商户订单。</p>
      <el-input v-model="client.lookup" placeholder="输入完整订单号" aria-label="核销订单号" @keyup.enter="client.lookupOrder()"></el-input>
      <el-button :loading="client.busy" @click="client.lookupOrder()">查询核销订单</el-button>
    </section>
    <el-dialog v-model="client.detailOpen" title="订单详情" width="min(94vw,560px)">
      <div v-if="client.detail" class="order-details">
        <strong>{{client.detail.title || '历史订单'}}</strong>
        <p>{{client.detail.shopName}} · {{statusName(client.detail.status)}}</p>
        <p>订单号：{{client.detail.id}}</p>
        <p>应付金额：{{money(client.detail.payValue)}}　券面金额：{{money(client.detail.actualValue)}}</p>
        <p>支付截止：{{time(client.detail.payDeadline)}}</p>
        <p>使用期限：{{time(client.detail.useBeginTime)}} 至 {{time(client.detail.useEndTime)}}</p>
        <p v-if="client.detail.payTime">支付时间：{{time(client.detail.payTime)}}</p>
        <p v-if="client.detail.useTime">核销时间：{{time(client.detail.useTime)}}</p>
        <p v-if="client.detail.refundTime">退款时间：{{time(client.detail.refundTime)}}</p>
        <p v-if="client.detail.closeReason">关闭原因：{{client.detail.closeReason === 'TIMEOUT' ? '超时未支付' : '主动取消'}}</p>
        <p v-if="client.detail.stockReturnState">库存同步：{{client.detail.stockReturnState}}</p>
        <p class="orders-note">操作状态以服务器最新校验为准。模拟支付／退款不发生真实资金转移。</p>
        <template v-if="!client.merchantDetail">
          <el-button v-if="client.detail.canPay" :loading="client.busy" @click="confirmAction('pay')">模拟支付</el-button>
          <el-button v-if="client.detail.canCancel" :disabled="client.busy" @click="confirmAction('cancel')">取消订单</el-button>
          <el-button v-if="client.detail.canRefund" :disabled="client.busy" @click="confirmAction('refund')">全额模拟退款</el-button>
        </template>
        <el-button v-if="client.merchantDetail && client.detail.canRedeem" :disabled="client.busy" @click="confirmAction('redeem')">确认核销</el-button>
        <el-button :disabled="client.busy" @click="client.open(client.detail.id,client.merchantDetail)">刷新详情</el-button>
      </div>
    </el-dialog>
  </section>`
};
