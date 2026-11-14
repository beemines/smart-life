"""Pure offline reconciliation; IDs deliberately remain strings."""
from collections import Counter

def reconcile(d):
    errors=[]
    def require(ok, reason):
        if not ok: errors.append(reason)
    orders=d['orders'];requests=d['requests'];responses=d['responses']
    ids=[o['orderId'] for o in orders]; oid=set(ids)
    require(len(ids)==len(oid),'duplicate order identity')
    require(len({o['userId'] for o in orders})==len(orders),'duplicate user/voucher')
    keys=[(r['userId'],r['requestKey']) for r in requests]
    responsekeys=[(r['userId'],r['requestKey']) for r in responses]
    require(len(keys)==len(set(keys)) and len(responsekeys)==len(set(responsekeys)), 'duplicate request identity')
    expected=[(r['userId'],r['requestKey']) for r in d['expectedResponses']]
    require(len(expected)==len(set(expected)) and set(responsekeys)==set(expected),'manifest/HTTP workload identity mismatch')
    require(set(keys)==set(responsekeys),'HTTP/intents identity mismatch')
    bykey=dict(zip(keys,requests)); byorder={o['orderId']:o for o in orders}
    require(len({r['orderId'] for r in requests})==len(requests),'duplicate intent order identity')
    for response in responses:
        intent=bykey.get((response['userId'],response['requestKey']))
        if intent is None: continue
        order=byorder.get(intent['orderId'])
        outcome=response['outcome']
        require(outcome in ('ACCEPTED','BUSINESS_REJECTED'),'HTTP/system/JSON error')
        if outcome=='ACCEPTED':
            require(bool(response['orderId']) and response['orderId']==intent['orderId'],'accepted HTTP ID mismatch/missing')
            require(order is not None and order['userId']==response['userId'] and intent['state']=='CREATED','accepted intent not durably created')
        elif outcome=='BUSINESS_REJECTED':
            require(order is None and intent['state']=='REJECTED' and intent['reason']=='REJECTED_OUT_OF_STOCK','unexpected business rejection')
    require(oid=={r['orderId'] for r in requests if r['state']=='CREATED'},'SQL/intent order identity mismatch')
    require({f['orderId'] for f in d['failures']}=={r['orderId'] for r in requests if r['state']=='REJECTED'},'failure/intent identity mismatch')
    requestbyid={r['orderId']:r for r in requests}
    for failure in d['failures']:
        intent=requestbyid.get(failure['orderId'])
        require(intent is not None and failure.get('userId')==intent['userId'] and failure.get('reason')==intent['reason'],'failure/intent payload mismatch')
        require(failure['orderId'] not in oid,'order and failure conflict')
        require(failure['compensation']=='done','failure compensation unresolved/manual')
    # This workload never pays/cancels/refunds: any lifecycle transition/return is unexpected.
    require(all(o['status']=='1' for o in orders),'unexpected order lifecycle transition')
    require(not d['returns'],'unexpected stock return; investigate lifecycle before cleanup')
    require(0<=d['sqlStock']==d['redisStock']==d['initial']-len(orders),'SQL/Redis stock conservation')
    if 'expectedOrders' in d: require(len(orders)==d['expectedOrders'],'effective order count mismatch')
    # Recovery outcome SUCCEEDED is audit metadata only, never proof of order creation.
    return dict(passed=not errors,errors=errors,accepted=sum(r['outcome']=='ACCEPTED' for r in responses),
                businessRejected=sum(r['outcome']=='BUSINESS_REJECTED' for r in responses),effectiveOrders=len(orders),
                sqlStock=d['sqlStock'],redisStock=d['redisStock'])
