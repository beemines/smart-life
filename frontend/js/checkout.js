(function () {
  'use strict';
  const idPattern = /^[1-9][0-9]{0,18}$/;
  function cents(value, allowZero) {
    const text = String(value).trim();
    if (!/^\d+(\.\d{1,2})?$/.test(text)) throw new Error('金额须为最多两位小数的人民币金额');
    const [whole, fraction = ''] = text.split('.');
    const amount = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
    if (!Number.isSafeInteger(amount) || amount < (allowZero ? 0 : 1) || amount > 100000000) throw new Error('金额须在0.01至1,000,000元范围内');
    return amount;
  }
  function validId(value) { if (typeof value !== 'string' || !idPattern.test(value)) throw new Error('请输入完整的有效编号'); return value; }
  function data(response) { if (response?.success === false) throw new Error(response.errorMsg || '操作失败'); return response.data; }
  window.createCheckout = function (api, onError) {
    return {
      items: [], nextTime: null, nextId: null, hasMore: false, loading: false, listGeneration: 0,
      detail: null, detailOpen: false, merchantDetail: false, detailGeneration: 0, quoteGeneration: 0,
      busy: false, quoting: false, lookup: '', merchant: false, admin: false,
      options: {vouchers: [], merchants: [], platforms: [], hasMoreVouchers: false, nextVoucherId: null}, voucherLoading: false,
      voucherId: '', merchantId: '', platformId: '', automatic: true,
      creation: null,
      async capabilities() {
        try { const result = data(await api.get('/checkout/capabilities')); this.merchant = result.merchant === true; this.admin = result.admin === true; }
        catch (e) { onError(String(e)); }
      },
      async create(shopId, total, excluded) {
        if (this.busy) return;
        try {
          const payload = {shopId: validId(shopId), total: cents(total, false), excluded: cents(excluded, true)};
          if (payload.excluded > payload.total) throw new Error('不参与优惠金额不能超过消费总额');
          const fingerprint = JSON.stringify(payload);
          if (!this.creation || this.creation.fingerprint !== fingerprint) this.creation = {fingerprint, key: crypto.randomUUID()};
          this.busy = true;
          this.detail = data(await api.post('/checkout/bills', {...payload, requestKey: this.creation.key}));
          this.detailOpen = true;
        } catch (e) { onError(String(e)); }
        finally { this.busy = false; }
      },
      async load(reset) {
        if (!reset && (this.loading || !this.hasMore)) return;
        if (reset) { this.listGeneration++; this.items = []; this.nextTime = null; this.nextId = null; this.hasMore = false; }
        const generation = this.listGeneration;
        const params = {limit: 10};
        if (this.nextTime && this.nextId) Object.assign(params, {beforeTime: this.nextTime, beforeId: this.nextId});
        this.loading = true;
        try {
          const page = data(await api.get('/checkout/bills', {params}));
          if (generation !== this.listGeneration) return;
          const known = new Set(this.items.map(item => item.id));
          this.items = this.items.concat(page.items.filter(item => !known.has(item.id)));
          this.nextTime = page.nextTime; this.nextId = page.nextId; this.hasMore = page.hasMore;
        } catch (e) { if (generation === this.listGeneration) onError(String(e)); }
        finally { if (generation === this.listGeneration) this.loading = false; }
      },
      selectionChanged() {
        this.quoteGeneration++; this.quoting = false;
        if (this.detail) this.detail.quote = null;
      },
      close() { this.detailGeneration++; this.selectionChanged(); this.voucherLoading = false; this.detail = null; this.detailOpen = false; },
      async open(id, merchant) {
        if (this.busy) return;
        this.close(); const generation = this.detailGeneration;
        this.merchantDetail = !!merchant;
        this.voucherId = ''; this.merchantId = ''; this.platformId = ''; this.automatic = true;
        this.options = {vouchers: [], merchants: [], platforms: [], hasMoreVouchers: false, nextVoucherId: null};
        try {
          const bill = data(await api.get('/checkout/' + (merchant ? 'merchant/' : '') + 'bills/' + encodeURIComponent(validId(id))));
          if (generation !== this.detailGeneration) return;
          this.detail = bill; this.detailOpen = true;
          if (!merchant && bill.canQuote) {
            // Selections start empty; an earlier token must not describe different choices.
            this.detail.quote = null;
            const options = data(await api.get('/checkout/bills/' + encodeURIComponent(id) + '/options'));
            if (generation === this.detailGeneration) this.options = options;
          }
        } catch (e) { if (generation === this.detailGeneration) { this.selectionChanged(); onError(String(e)); } }
      },
      async lookupBill() { await this.open(this.lookup.trim(), true); },
      async loadMoreVouchers() {
        if (this.voucherLoading || this.busy || this.merchantDetail || !this.detail?.canQuote || !this.options.hasMoreVouchers || !this.options.nextVoucherId) return;
        const id = this.detail.id, generation = this.detailGeneration;
        const cursor = this.options.nextVoucherId;
        this.voucherLoading = true;
        try {
          const page = data(await api.get('/checkout/bills/' + encodeURIComponent(id) + '/options', {params: {beforeVoucherId: cursor}}));
          if (generation !== this.detailGeneration || this.detail?.id !== id) return;
          const known = new Set(this.options.vouchers.map(v => v.id));
          this.options.vouchers = this.options.vouchers.concat(page.vouchers.filter(v => !known.has(v.id)));
          this.options.hasMoreVouchers = page.hasMoreVouchers;
          this.options.nextVoucherId = page.nextVoucherId;
        } catch (e) { if (generation === this.detailGeneration && this.detail?.id === id) onError(String(e)); }
        finally { if (generation === this.detailGeneration) this.voucherLoading = false; }
      },
      async quote() {
        if (this.busy || !this.detail?.canQuote || this.merchantDetail) return;
        const id = this.detail.id, generation = ++this.quoteGeneration, detailGeneration = this.detailGeneration;
        this.detail.quote = null; this.quoting = true;
        try {
          const result = data(await api.post('/checkout/bills/' + encodeURIComponent(id) + '/quote', {
            voucherId: this.voucherId || null, automatic: this.automatic,
            merchantId: this.automatic ? null : this.merchantId || null, platformId: this.automatic ? null : this.platformId || null
          }));
          if (generation === this.quoteGeneration && detailGeneration === this.detailGeneration && this.detail?.id === id) this.detail = result;
        } catch (e) { if (generation === this.quoteGeneration && detailGeneration === this.detailGeneration) onError(String(e)); }
        finally { if (generation === this.quoteGeneration) this.quoting = false; }
      },
      async action(action, fields = {}) {
        const permission = {pay: 'canPay', cancel: 'canCancel', confirm: 'canConfirm', reverse: 'canReverse'}[action];
        if (!permission || this.busy || this.quoting || !this.detail?.[permission]) return;
        const id = this.detail.id, generation = this.detailGeneration, merchant = this.merchantDetail;
        let body = {};
        try {
          if (action === 'pay') {
            if (merchant || !this.detail.quote?.token || Date.parse(this.detail.quote.expiresAt) <= Date.now() || !Number.isFinite(Date.parse(this.detail.quote.expiresAt))) throw new Error('报价已过期，请重新试算并确认');
            body = {token: this.detail.quote.token};
          }
          if (action === 'confirm') {
            if (!merchant) return;
            body = {total: cents(fields.total, false), excluded: cents(fields.excluded, true)};
            if (body.excluded > body.total) throw new Error('不参与优惠金额不能超过消费总额');
          }
          if (action === 'reverse') { if (!merchant) return; body = {reason: String(fields.reason || '').trim()}; if (!body.reason) throw new Error('请填写全额撤销原因'); }
          this.busy = true;
          const result = data(await api.post('/checkout/' + (merchant ? 'merchant/' : '') + 'bills/' + encodeURIComponent(id) + '/' + action, body));
          if (generation === this.detailGeneration) { this.selectionChanged(); this.detail = result; }
          await this.load(true);
        } catch (e) {
          onError(String(e));
          if (this.busy) {
            try {
              const refreshed = data(await api.get('/checkout/' + (merchant ? 'merchant/' : '') + 'bills/' + encodeURIComponent(id)));
              if (generation === this.detailGeneration) { this.selectionChanged(); this.detail = refreshed; }
              await this.load(true);
            } catch (refreshError) { if (generation === this.detailGeneration) this.close(); }
          }
        } finally { this.busy = false; }
      }
    };
  };
  const helpers = {
    money(v) { return v == null ? '—' : '￥' + (v / 100).toFixed(2); },
    time(v) { return v ? new Date(v).toLocaleString('zh-CN', {hour12: false}) : '—'; },
    state(v) { return {DRAFT:'待商户确认',CONFIRMED:'待模拟补付',PAID:'已结账核销',REVERSED:'已全额撤销',CANCELLED:'已取消'}[v] || v; },
    ruleText(r) {
      if (r.face != null) return '最低可优惠消费额' + this.money(r.threshold) + ' · 叠加门店：' + (r.stackMerchant ? '是':'否') + ' · 叠加平台：' + (r.stackPlatform ? '是':'否');
      return (r.kind === 'DISCOUNT' ? '需付比例' + r.payableBps + '/10000' : '减' + this.money(r.reduction)) + ' · 门槛' + this.money(r.threshold) + (r.cap ? ' · 封顶' + this.money(r.cap) : '') + ' · 叠加门店/代金券/平台：' + [r.stackMerchant,r.stackVoucher,r.stackPlatform].map(v=>v ? '是' : '否').join('/');
    }
  };
  window.SmartLifeBillCreate = {
    props: ['shopId'],
    data() { return {open: false, total: '', excluded: '0', client: window.createCheckout(axios, e=>this.$message.error(e))}; },
    methods: helpers,
    template: `<section class="checkout-entry"><el-button type="primary" @click="open=true">到店买单</el-button><span>商户确认消费金额后再模拟结账</span>
      <el-dialog v-model="open" title="创建到店消费账单" width="min(94vw,560px)">
        <div class="checkout-form"><p>金额待商户确认；模拟支付不发生真实扣款。</p>
        <label>消费总额（元）<input v-model="total" inputmode="decimal" :disabled="client.busy || !!client.detail" aria-label="消费总额"></label>
        <label>不参与优惠金额（元）<input v-model="excluded" inputmode="decimal" :disabled="client.busy || !!client.detail" aria-label="不参与优惠金额"></label>
        <el-button v-if="!client.detail" :loading="client.busy" @click="client.create(shopId,total,excluded)">保存待确认账单</el-button>
        <div v-if="client.detail" role="status"><strong>账单已保存 · {{state(client.detail.state)}}</strong><p>账单号：{{client.detail.id}}</p><p>请向本店商户出示账单号确认金额。</p><a href="/info.html#orders">查看消费账单 / 模拟补付</a></div>
        <p class="orders-note">网络超时可重试原金额，使用同一请求编号查询创建结果。</p></div>
      </el-dialog></section>`
  };
  window.SmartLifeCheckoutPanel = {
    data() { return {client: window.createCheckout(axios, e=>this.$message.error(e)), confirmTotal:'', confirmExcluded:'0', reason:'', ruleBusy:false, rules:[], ruleShop:'', rule:{title:'',scope:'MERCHANT',kind:'FULL_REDUCTION',threshold:'0',reduction:'1',payableBps:'9000',cap:'0',priority:'0',stackMerchant:true,stackVoucher:true,stackPlatform:true,beginTime:'',endTime:'',active:true}, grantRule:'',grantUser:'',grantKey:null,revokeId:''}; },
    created() { this.client.load(true); this.client.capabilities(); },
    beforeUnmount() { this.client.close(); this.client.listGeneration++; },
    computed: { calculation() { return this.client.detail?.settlement?.calculation || this.client.detail?.quote?.calculation; } },
    methods: {
      ...helpers,
      async openBill(id, merchant) { await this.client.open(id,merchant); this.fillConfirmation(); },
      fillConfirmation() { const bill=this.client.detail; if(bill){this.confirmTotal=(bill.total/100).toFixed(2);this.confirmExcluded=(bill.excluded/100).toFixed(2);} },
      async lookup() { await this.client.lookupBill(); this.fillConfirmation(); },
      async act(action) {
        const messages={pay:'确认按当前服务端报价模拟补付并核销？代金券整张核销，未用面值不找零。',cancel:'确认取消此消费账单？',confirm:'确认本店消费总额及不参与优惠金额？将使旧报价失效。',reverse:'确认全额撤销？仅模拟退回本次补付款，并恢复原券使用资格；券有效期不延长。'};
        try { await this.$confirm(messages[action],'确认操作',{confirmButtonText:'确认',cancelButtonText:'返回'}); } catch(e){return;}
        await this.client.action(action,{total:this.confirmTotal,excluded:this.confirmExcluded,reason:this.reason});
      },
      async loadRules() { if(this.ruleBusy)return;this.ruleBusy=true;try{this.rules=data(await axios.get('/checkout/rules',{params:{shopId:validId(this.ruleShop.trim())}}));}catch(e){this.$message.error(String(e));}finally{this.ruleBusy=false;} },
      async saveRule() {
        if(this.ruleBusy)return;this.ruleBusy=true;
        try {
          const r=this.rule;
          if(!r.title.trim())throw new Error('请填写规则名称');
          if(!r.beginTime || !r.endTime)throw new Error('请填写规则起止时间');
          const body={...r,shopId:validId(this.ruleShop.trim()),threshold:cents(r.threshold,true),reduction:r.kind==='FULL_REDUCTION'?cents(r.reduction,false):0,payableBps:r.kind==='DISCOUNT'?Number(r.payableBps):0,cap:r.kind==='DISCOUNT'?cents(r.cap,true):0,priority:Number(r.priority),beginTime:new Date(r.beginTime).toISOString(),endTime:new Date(r.endTime).toISOString()};
          if(!Number.isInteger(body.priority)||!Number.isInteger(body.payableBps)||(r.kind==='DISCOUNT'&&(body.payableBps<1||body.payableBps>9999)))throw new Error('优先级及折扣基点必须为有效整数');
          await axios.post('/checkout/rules',body);this.rule.id=undefined;this.$message.success('规则已保存');
        } catch(e){this.$message.error(String(e));}finally{this.ruleBusy=false;}
        await this.loadRules();
      },
      editRule(r) { this.rule={...r,threshold:(r.threshold/100).toFixed(2),reduction:(r.reduction/100).toFixed(2),cap:(r.cap/100).toFixed(2),beginTime:r.beginTime?new Date(new Date(r.beginTime).getTime()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16):'',endTime:r.endTime?new Date(new Date(r.endTime).getTime()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16):''}; },
      async grant() { if(this.ruleBusy)return;this.ruleBusy=true;try{const ruleId=validId(this.grantRule.trim()),userId=validId(this.grantUser.trim());const fingerprint=ruleId+':'+userId;if(this.grantKey?.fingerprint!==fingerprint)this.grantKey={fingerprint,key:crypto.randomUUID()};const coupon=data(await axios.post('/checkout/coupons',{ruleId,userId,requestKey:this.grantKey.key}));this.$message.success('已发放平台券：'+coupon.id);}catch(e){this.$message.error(String(e));}finally{this.ruleBusy=false;} },
      async revoke() { if(this.ruleBusy)return;this.ruleBusy=true;try{await axios.post('/checkout/coupons/'+validId(this.revokeId.trim())+'/revoke',{});this.$message.success('平台券已撤销');}catch(e){this.$message.error(String(e));}finally{this.ruleBusy=false;} }
    },
    template: `<section class="checkout-panel">
      <div class="orders-toolbar"><h3>消费账单</h3><el-button :loading="client.loading" @click="client.load(true)">刷新消费账单</el-button></div>
      <p class="orders-note">到店消费由商户确认；仅模拟补付与核销，不涉及真实资金。历史券仅支持原核销流程。</p>
      <p v-if="!client.loading && !client.items.length">暂无消费账单，可从商户详情发起到店买单。</p>
      <article class="order-card" v-for="bill in client.items" :key="bill.id"><div><strong>{{bill.shopName}}</strong><span>{{state(bill.state)}}</span></div><p>消费 {{money(bill.total)}} · 不参与优惠 {{money(bill.excluded)}}</p><small>账单号：{{bill.id}}</small><div><small>{{time(bill.createdAt)}}</small><el-button :disabled="client.busy" @click="openBill(bill.id,false)">查看消费账单</el-button></div></article>
      <el-button v-if="client.hasMore" :loading="client.loading" @click="client.load(false)">加载更多账单</el-button>
      <section v-if="client.merchant || client.admin" class="merchant-panel"><h4>商户消费账单</h4><p class="orders-note">查询并确认当前有权限管理的店铺消费，已结账可全额撤销。</p><el-input v-model="client.lookup" placeholder="输入完整消费账单号" aria-label="消费账单号" @keyup.enter="lookup"></el-input><el-button :disabled="client.busy" @click="lookup">查询消费账单</el-button>
      <details class="checkout-rules"><summary>优惠规则管理</summary><div class="checkout-form">
        <label>店铺编号<input v-model="ruleShop" aria-label="规则店铺编号"></label><el-button :loading="ruleBusy" @click="loadRules">读取规则</el-button>
        <article v-for="r in rules" :key="r.id" class="order-card"><strong>{{r.title}}</strong><p>{{r.scope}} · {{r.active ? '启用':'停用'}} · {{ruleText(r)}}</p><el-button v-if="r.scope==='MERCHANT'||client.admin" @click="editRule(r)">编辑规则</el-button></article>
        <label>规则名称<input v-model="rule.title"></label><label>类型<select v-model="rule.scope"><option value="MERCHANT">本店促销</option><option v-if="client.admin" value="PLATFORM">平台券模板（管理员）</option></select></label>
        <label>优惠形式<select v-model="rule.kind"><option value="FULL_REDUCTION">满减</option><option value="DISCOUNT">折扣</option></select></label>
        <label>最低可优惠金额（元）<input v-model="rule.threshold" inputmode="decimal"></label><label v-if="rule.kind==='FULL_REDUCTION'">减免金额（元）<input v-model="rule.reduction" inputmode="decimal"></label><label v-else>需付基点（9000为九折，1至9999）<input v-model="rule.payableBps" inputmode="numeric"></label>
        <label v-if="rule.kind==='DISCOUNT'">优惠封顶（元，0为不限）<input v-model="rule.cap" inputmode="decimal"></label><label>优先级<input v-model="rule.priority" inputmode="numeric"></label><label>开始时间（本地）<input type="datetime-local" v-model="rule.beginTime"></label><label>结束时间（本地）<input type="datetime-local" v-model="rule.endTime"></label>
        <label><input type="checkbox" v-model="rule.stackMerchant">允许叠加门店促销</label><label><input type="checkbox" v-model="rule.stackVoucher">允许叠加已购代金券</label><label><input type="checkbox" v-model="rule.stackPlatform">允许叠加平台券</label><label><input type="checkbox" v-model="rule.active">启用</label><el-button :loading="ruleBusy" @click="saveRule">保存{{rule.id?'新版本':'规则'}}</el-button>
      </div></details>
      <details v-if="client.admin" class="checkout-rules"><summary>管理员平台券发放 / 撤销</summary><div class="checkout-form"><label>平台规则编号<input v-model="grantRule"></label><label>用户编号<input v-model="grantUser"></label><el-button :loading="ruleBusy" @click="grant">发放单店平台券</el-button><p class="orders-note">重复提交同一用户/模板将返回原发放结果，保留规则快照。</p><label>撤销未用平台券编号<input v-model="revokeId"></label><el-button :loading="ruleBusy" @click="revoke">撤销未用权益</el-button></div></details></section>
      <el-dialog v-model="client.detailOpen" @closed="client.close()" title="消费账单详情" width="min(94vw,640px)"><div v-if="client.detail" class="order-details checkout-form">
        <strong>{{client.detail.shopName}} · {{state(client.detail.state)}}</strong><p>账单号：{{client.detail.id}}</p><p>消费总额 {{money(client.detail.total)}} · 不参与优惠 {{money(client.detail.excluded)}}</p><p>支付截止：{{time(client.detail.payDeadline)}}</p>
        <p v-if="client.detail.state==='DRAFT'" class="orders-note">请出示账单号，由本店商户确认金额后再试算。</p>
        <template v-if="client.merchantDetail && client.detail.canConfirm"><label>确认消费总额（元）<input v-model="confirmTotal" inputmode="decimal"></label><label>确认不参与优惠金额（元）<input v-model="confirmExcluded" inputmode="decimal"></label><el-button :disabled="client.busy" @click="act('confirm')">确认 / 修改消费金额</el-button></template>
        <template v-if="!client.merchantDetail && client.detail.canQuote"><fieldset :disabled="client.busy"><legend>选择优惠</legend>
          <label>已购代金券（整张核销，不找零）<select v-model="client.voucherId" @change="client.selectionChanged()"><option value="">不使用已购券</option><option v-for="v in client.options.vouchers" :key="v.id" :value="v.id" :disabled="v.eligible===false">{{v.title}} · 已付{{money(v.price)}} / 面值{{money(v.face)}} · 门槛{{money(v.threshold)}} {{v.disabledReason}}</option></select></label>
          <el-button v-if="client.options.hasMoreVouchers" :loading="client.voucherLoading" :disabled="client.busy || client.voucherLoading" @click="client.loadMoreVouchers()">加载更多已购券</el-button>
          <p v-for="v in client.options.vouchers" :key="'info'+v.id" class="orders-note">{{v.title}}：{{v.eligible===false?v.disabledReason:'可选择'}}；{{ruleText(v)}}</p>
          <label><input type="checkbox" v-model="client.automatic" @change="client.selectionChanged()">免费门店促销和平台券自动择优；已购券由您明确选择</label>
          <template v-if="!client.automatic"><label>门店促销<select v-model="client.merchantId" @change="client.selectionChanged()"><option value="">不使用</option><option v-for="r in client.options.merchants" :key="r.id" :value="r.id" :disabled="r.eligible===false">{{r.title}} {{r.disabledReason}}</option></select></label><label>平台券<select v-model="client.platformId" @change="client.selectionChanged()"><option value="">不使用</option><option v-for="r in client.options.platforms" :key="r.id" :value="r.id" :disabled="r.eligible===false">{{r.title}} {{r.disabledReason}}</option></select></label></template>
          <p v-for="r in client.options.merchants.concat(client.options.platforms)" :key="r.id" class="orders-note">{{r.title}}：{{ruleText(r)}} {{r.disabledReason}}</p>
        </fieldset><el-button :loading="client.quoting" :disabled="client.busy" @click="client.quote()">服务端试算优惠</el-button></template>
        <dl v-if="calculation" class="checkout-breakdown"><dt>原消费</dt><dd>{{money(calculation.total)}}</dd><dt>不参与优惠</dt><dd>{{money(calculation.excluded)}}</dd><dt>可优惠基数</dt><dd>{{money(calculation.eligible)}}</dd><dt>门店促销优惠</dt><dd>−{{money(calculation.merchantDiscount)}}</dd><dt>代金券门槛基数</dt><dd>{{money(calculation.eligible-calculation.merchantDiscount)}}</dd><dt>代金券抵扣</dt><dd>−{{money(calculation.voucherDeduction)}}</dd><dt>券未用面值（不找零）</dt><dd>{{money(calculation.unusedVoucherValue)}}</dd><dt>平台券门槛基数</dt><dd>{{money(calculation.eligible-calculation.merchantDiscount-calculation.voucherDeduction)}}</dd><dt>平台优惠</dt><dd>−{{money(calculation.platformDiscount)}}</dd><dt>已付购券成本（不重复收费）</dt><dd>{{money(calculation.previousPaid)}}</dd><dt class="checkout-total">本次模拟补付</dt><dd class="checkout-total">{{money(calculation.payable)}}</dd><dt>累计现金成本</dt><dd>{{money(calculation.totalCash)}}</dd><dt>相对原消费现金节省（可能为负）</dt><dd>{{money(calculation.cashSaving)}}</dd></dl>
        <p v-if="client.detail.quote">报价截止：{{time(client.detail.quote.expiresAt)}}；修改选择后须重新试算。</p>
        <p v-if="client.detail.state==='REVERSED'" class="orders-note">已全额撤销，模拟退回本次补付款。购券款未退，面值不折现；恢复权益的原期限不延长，过期或已撤销权益不可用。以上保留原结算快照。</p>
        <p class="orders-note">金额和可操作状态由服务器校验；零补付也需明确确认。网络超时请刷新原账单核对结果。</p>
        <el-button v-if="!client.merchantDetail && client.detail.canPay" :disabled="client.busy || client.quoting || !client.detail.quote" @click="act('pay')">确认模拟补付并核销</el-button><el-button v-if="!client.merchantDetail && client.detail.canCancel" :disabled="client.busy" @click="act('cancel')">取消消费账单</el-button>
        <template v-if="client.merchantDetail && client.detail.canReverse"><label>全额撤销原因<input v-model="reason"></label><el-button :disabled="client.busy" @click="act('reverse')">全额撤销消费账单</el-button></template><el-button :disabled="client.busy || client.quoting" @click="openBill(client.detail.id,client.merchantDetail)">刷新账单详情</el-button>
      </div></el-dialog>
    </section>`
  };
})();
