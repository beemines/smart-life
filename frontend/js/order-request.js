(function (root) {
  'use strict';
  function randomKey() {
    if (root.crypto.randomUUID) return root.crypto.randomUUID();
    return Array.from(root.crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
  }
  function create(options) {
    const {http, storage, sessionKey, onChange} = options;
    const newKey = options.newKey || randomKey;
    const schedule = options.schedule || root.setTimeout.bind(root);
    const cancel = options.cancel || root.clearTimeout.bind(root);
    const maxPolls = options.maxPolls || 12;
    const pollDelay = options.pollDelay || 1500;
    const generations = new Map(), timers = new Map(), active = new Map();
    const storageKey = id => `smartlife:order:${sessionKey}:${id}`;
    const read = id => { const raw = storage.getItem(storageKey(id)); return raw ? JSON.parse(raw) : null; };
    const save = (id, entry) => storage.setItem(storageKey(id), JSON.stringify(entry));
    function stop(id) {
      if (timers.has(id)) cancel(timers.get(id));
      timers.delete(id);
      generations.set(id, (generations.get(id) || 0) + 1);
      return generations.get(id);
    }
    function notify(id, entry, status) { onChange(id, {...entry, ...status}); }
    async function check(id, entry, generation, count) {
      try {
        const {data} = await http.get('/voucher-order/request/' + encodeURIComponent(entry.requestId));
        if (generations.get(id) !== generation) return;
        if (!data || typeof data.orderId !== 'string' || !/^\d+$/.test(data.orderId)
            || (entry.orderId && entry.orderId !== data.orderId)
            || !['PREPARING', 'PENDING', 'CREATED', 'REJECTED', 'REVIEW'].includes(data.state)) {
          throw new Error('订单结果格式不正确');
        }
        Object.assign(entry, data);
        save(id, entry);
        const pending = data.state === 'PREPARING' || data.state === 'PENDING';
        const bounded = pending && count + 1 >= maxPolls;
        notify(id, entry, {canContinue: bounded || data.state === 'REVIEW',
          message: bounded ? '仍在处理中，可稍后继续查询，请勿重复领取。' : data.message});
        if (pending && !bounded) {
          timers.set(id, schedule(() => check(id, entry, generation, count + 1), pollDelay));
        }
        return data;
      } catch (error) {
        if (generations.get(id) !== generation) return;
        notify(id, entry, {state: 'UNKNOWN', canContinue: true,
          message: '暂时无法确认订单结果，请点击继续查询。请求标识已保留。'});
      }
    }
    async function doSubmit(id, allowNew) {
      const generation = stop(id);
      let entry = read(id);
      if (entry && entry.state === 'CREATED') { notify(id, entry, {canContinue: false}); return entry; }
      if (entry && entry.state === 'REVIEW') return check(id, entry, generation, 0);
      if (!entry && !allowNew) return;
      if (!entry || (allowNew && entry.state === 'REJECTED')) entry = {requestId: newKey(), state: 'SUBMITTING'};
      // Save identity BEFORE network I/O. Timeout/refresh never silently generates another request.
      save(id, entry);
      notify(id, entry, {state: 'SUBMITTING', message: '正在提交，请稍候…', canContinue: false});
      try {
        const {data} = await http.post('/voucher-order/seckill/' + encodeURIComponent(id), null,
          {headers: {'Idempotency-Key': entry.requestId}, timeout: 10000});
        if (generations.get(id) !== generation) return;
        if (typeof data !== 'string' || !/^\d+$/.test(data)) throw new Error('订单ID必须使用字符串');
        entry.orderId = data;
        entry.state = 'PENDING';
        save(id, entry);
      } catch (error) {
        // Both rejected HTTP envelopes and network errors are resolved using the same request key.
        if (generations.get(id) !== generation) return;
      }
      return check(id, entry, generation, 0);
    }
    function submit(voucherId, allowNew) {
        const id = String(voucherId);
        if (active.has(id)) return active.get(id);
        const task = doSubmit(id, allowNew);
        active.set(id, task);
        task.then(() => active.delete(id), () => active.delete(id));
        return task;
    }
    return {
      submit(voucherId) { return submit(voucherId, true); },
      continue(voucherId) { return submit(voucherId, false); },
      restore(voucherId) {
        const id = String(voucherId), entry = read(id);
        if (!entry) return Promise.resolve();
        return check(id, entry, stop(id), 0);
      },
      dispose() { for (const id of generations.keys()) stop(id); }
    };
  }
  root.SmartLifeOrderClient = {create};
})(globalThis);
