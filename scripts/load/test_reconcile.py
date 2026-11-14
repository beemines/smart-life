import copy
import unittest
from reconcile import reconcile

class ReconciliationTest(unittest.TestCase):
    def good(self):
        return dict(initial=1, sqlStock=0, redisStock=0,expectedResponses=[dict(userId='11',requestKey='key')],
          responses=[dict(userId='11',requestKey='key',outcome='ACCEPTED',orderId='9223372036854775000')],
          requests=[dict(userId='11',requestKey='key',orderId='9223372036854775000',state='CREATED',reason='NULL')],
          orders=[dict(userId='11',orderId='9223372036854775000',status='1')],failures=[],returns=[],recoveries=[])
    def test_valid_large_id(self):
        self.assertTrue(reconcile(self.good())['passed'])
    def test_reject_corruption(self):
        for kind in ['pending','mismatch','duplicate','sql_stock','redis_stock','missing_http_id','system','manual','failure_and_order','recovery_only','return_pending']:
            with self.subTest(kind=kind):
                d=self.good()
                if kind=='pending': d['requests'][0]['state']='SENT';d['orders']=[]
                if kind=='mismatch': d['orders'][0]['orderId']='123'
                if kind=='duplicate': d['orders']*=2
                if kind=='sql_stock': d['sqlStock']=1
                if kind=='redis_stock': d['redisStock']=-1
                if kind=='missing_http_id': d['responses'][0]['orderId']=''
                if kind=='system': d['responses'][0]['outcome']='SYSTEM_ERROR'
                if kind=='manual': d['failures']=[dict(orderId=d['orders'][0]['orderId'],compensation='manual')]
                if kind=='failure_and_order': d['failures']=[dict(orderId=d['orders'][0]['orderId'],compensation='done')]
                if kind=='recovery_only': d['orders']=[];d['recoveries']=[dict(orderId=d['requests'][0]['orderId'],outcome='SUCCEEDED')]
                if kind=='return_pending': d['returns']=[dict(orderId=d['orders'][0]['orderId'],state='PENDING')]
                self.assertFalse(reconcile(d)['passed'])
    def test_missing_attempts_even_if_observed_state_consistent(self):
        d=self.good()
        d['expectedResponses'].append(dict(userId='12',requestKey='key2'))
        self.assertFalse(reconcile(d)['passed'])

    def test_expected_business_rejection(self):
        d=self.good();d['expectedResponses'].append(dict(userId='12',requestKey='key2'));d['responses'].append(dict(userId='12',requestKey='key2',outcome='BUSINESS_REJECTED',orderId=''))
        d['requests'].append(dict(userId='12',requestKey='key2',orderId='987',state='REJECTED',reason='REJECTED_OUT_OF_STOCK'))
        d['failures'].append(dict(userId='12',orderId='987',compensation='done',reason='REJECTED_OUT_OF_STOCK'))
        self.assertTrue(reconcile(d)['passed'])

if __name__=='__main__': unittest.main()
