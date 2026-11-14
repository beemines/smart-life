"""Bounded, synthetic localhost HTTP evidence. Python stdlib only; see GUIDE.md."""
from contextvars import ContextVar
import argparse,csv,hashlib,json,os,platform,secrets,shutil,socket,subprocess,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from reconcile import reconcile

ROOT=Path(__file__).resolve().parents[2]
PRIVATE=ROOT/'.superpowers/load'
DOCKER=shutil.which('docker') or 'C:/Users/beemines/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
JAVA='D:/JDK/jdk21/bin/java.exe'
BASE='http://127.0.0.1:18187'
MYSQL='smart-life-upgrade-mysql-1'; REDIS='smart-life-upgrade-redis-1'

DEPENDENCY_DEADLINE=ContextVar('dependency_deadline',default=None)

def dependency_timeout():
    deadline=DEPENDENCY_DEADLINE.get()
    remaining=30 if deadline is None else deadline-time.monotonic()
    if remaining<=0:raise subprocess.TimeoutExpired('dependency drain budget',0)
    return min(30,remaining)

def command(args,**kwargs):
    kwargs.setdefault('timeout',dependency_timeout())
    return subprocess.check_output(args,stderr=subprocess.DEVNULL,**kwargs).decode('utf-8').strip()
def sql(q):
    result=subprocess.run([DOCKER,'exec','-i',MYSQL,'mysql','-uroot','-plocal-test-only','--default-character-set=utf8mb4','-N','-B','smartlife'],input=q.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=dependency_timeout())
    return result.stdout.decode('utf-8').strip()
def redis(*args):return command([DOCKER,'exec',REDIS,'redis-cli','--raw',*map(str,args)])
def redis_pipeline(commands):
    payload=b''
    for args in commands:
        fields=[str(a).encode() for a in args]
        payload+=b'*'+str(len(fields)).encode()+b'\r\n'
        for field in fields:payload+=b'$'+str(len(field)).encode()+b'\r\n'+field+b'\r\n'
    result=subprocess.run([DOCKER,'exec','-i',REDIS,'redis-cli','--pipe'],input=payload,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=dependency_timeout())
    assert b'errors: 0' in result.stdout,result.stdout

def guard():
    for name,port in [(MYSQL,'13306'),(REDIS,'16379'),('smart-life-upgrade-nameserver-1','19876')]:
        info=json.loads(command([DOCKER,'inspect',name]))[0]
        bindings=info['NetworkSettings']['Ports']
        assert any(x.get('HostPort')==port for rows in bindings.values() if rows for x in rows),(name,port)
def api(path,payload=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['authorization']=token
    with urlopen(Request(BASE+path,data=None if payload is None else json.dumps(payload).encode(),headers=headers),timeout=20) as r:return json.load(r)
def write(path,value):path.write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def save(m):write(Path(m['private'])/'manifest.json',m)
def rows(q,columns):
    raw=sql(q)
    return [dict(zip(columns,line.split('\t'))) for line in raw.splitlines()] if raw else []

def fixture(directory):
    directory.mkdir(parents=True,exist_ok=False)
    # Random IDs in an exact, signed-long-safe synthetic range; INSERT is never REPLACE/IGNORE.
    start=8_000_000_000_000+secrets.randbelow(900_000_000)*1000
    m=dict(run=directory.name,private=str(directory),shopIds=[],userIds=[],tokens=[],vouchers=[],initial=10,rounds=3)
    save(m)
    # Each batch is one transaction; failed INSERT rolls back when mysql disconnects.
    shops=[str(start+i) for i in range(901)]
    values=','.join(f"({sid},'Load synthetic {directory.name}',1,'','Synthetic local fixture',113,23,0,0,45)" for sid in shops)
    sql('START TRANSACTION; INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES '+values+'; COMMIT')
    m['shopIds']=shops;save(m)
    users=[str(start+2000+i) for i in range(101)]
    # Unique reserved synthetic phone range per run; transaction fails rather than touching collisions.
    phonebase=19000000000+secrets.randbelow(9_000_000)*100
    values=','.join(f"({uid},'{phonebase+i}','Load synthetic')" for i,uid in enumerate(users))
    sql('START TRANSACTION; INSERT INTO tb_user(id,phone,nick_name) VALUES '+values+f"; INSERT INTO tb_shop_permission(user_id,shop_id,role) VALUES ({users[0]},{shops[0]},'OWNER'); COMMIT")
    m['userIds']=users;m['tokens']=[secrets.token_hex(24) for _ in users];save(m)
    commands=[]
    for uid,token in zip(users,m['tokens']):
        commands.extend([['HSET','login:token:'+token,'id',uid,'nickName','Load synthetic'],['EXPIRE','login:token:'+token,'14400']])
    redis_pipeline(commands)
    save(m);return m

def stop_app(p):
    if p.poll() is None:
        p.terminate()
        try:p.wait(timeout=20)
        except subprocess.TimeoutExpired:p.kill();p.wait()

def app_resources(pid):
    raw=command(['powershell','-NoProfile','-Command',
        f"Get-Process -Id {int(pid)} | Select-Object CPU,WorkingSet64,PeakWorkingSet64 | ConvertTo-Json"])
    return json.loads(raw)

def start_app(m,jar):
    env=os.environ.copy();env.update(SMARTLIFE_SERVER_ADDRESS='127.0.0.1',SMARTLIFE_DB_HOST='127.0.0.1',SMARTLIFE_DB_USER='root',SMARTLIFE_DB_NAME='smartlife',SMARTLIFE_REDIS_HOST='127.0.0.1',SMARTLIFE_REDIS_PASSWORD='',SMARTLIFE_REDIS_DATABASE='0',SMARTLIFE_REDIS_SSL='false',SMARTLIFE_ORDER_CONSUMER_ENABLED='true',SMARTLIFE_ORDER_RECOVERY_ENABLED='false',SMARTLIFE_LIFECYCLE_ENABLED='false',SMARTLIFE_SYNC_ENABLED='false',SMARTLIFE_BATCH_ENABLED='false',SMARTLIFE_DB_PORT='13306',SMARTLIFE_DB_PASSWORD='local-test-only',SMARTLIFE_REDIS_PORT='16379',SMARTLIFE_MQ_NAME_SERVER='127.0.0.1:19876',SMARTLIFE_SERVER_PORT='18187',SMARTLIFE_MQ_TOPIC='smartlife-load-'+m['run'],SMARTLIFE_MQ_CONSUMER_GROUP='smartlife-load-'+m['run'],SMARTLIFE_LOG_LEVEL='info')
    private=Path(m['private']);shutil.copy2(jar,private/'app.jar')
    with (private/'app.log').open('w') as out:
        p=subprocess.Popen([JAVA,'-jar',str(private/'app.jar')],cwd=ROOT,env=env,stdout=out,stderr=out,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    m['pid']=p.pid;save(m)
    try:
        for _ in range(90):
            if p.poll() is not None:raise RuntimeError('Dedicated app exited; inspect private app.log')
            try:
                if api('/shop-type/list')['success']:return p
            except Exception:pass
            time.sleep(1)
        raise RuntimeError('Dedicated app did not become ready')
    except BaseException:
        stop_app(p)
        raise

def publish(m):
    result=api('/voucher/seckill',dict(shopId=m['shopIds'][0],title='Local load synthetic',subTitle='Paid voucher',rules='Synthetic measurement only',payValue=8000,actualValue=10000,stock=10,beginTime='2026-01-01T00:00:00',endTime='2030-01-01T00:00:00'),m['tokens'][0])
    assert result['success'],result
    v=str(result['data']);m['vouchers'].append(v);save(m);return v

def jmeter(m,jhome,public,profile,roundno,voucher=None):
    name=f'{profile}-{roundno}'; private=Path(m['private']);auth=private/(name+'.csv')
    with auth.open('w',newline='',encoding='utf-8') as f:
        out=csv.writer(f)
        if profile=='orders':
            for i in range(1,101):out.writerow([m['userIds'][i],m['tokens'][i],f'{m["run"]}_r{roundno}_u{i}',voucher])
        elif profile=='cold':
            for sid in m['shopIds'][1+(roundno-1)*300:1+roundno*300]:out.writerow(['','','',sid])
        else:
            for _ in range(100000):out.writerow(['','','',m['shopIds'][0]])
    params=dict(profile=profile,csv=str(auth),threads=100 if profile=='orders' else 10,ramp=1,loops=-1 if profile=='hot' else (1 if profile=='orders' else 30),duration=10 if profile=='hot' else 30,path='/voucher-order/seckill' if profile=='orders' else '/shop',method='POST' if profile=='orders' else 'GET')
    args=[JAVA,'-Xms256m','-Xmx512m','-jar',str(jhome/'bin/ApacheJMeter.jar'),'-n','-i',str(ROOT/'scripts/load/log4j2.xml'),'-t',str(ROOT/'scripts/load/workload.jmx'),'-q',str(ROOT/'scripts/load/results.properties'),'-l',str(public/(name+'.jtl')),'-j',str(private/(name+'.log'))]+[f'-J{k}={v}' for k,v in params.items()]
    resource_before=app_resources(m['pid']);resource_started=time.monotonic()
    with (private/(name+'-stdout.txt')).open('w') as log:subprocess.run(args,cwd=ROOT,stdout=log,stderr=log,timeout=100,check=True)
    resource_seconds=time.monotonic()-resource_started
    resource_after=app_resources(m['pid'])
    data=list(csv.DictReader((public/(name+'.jtl')).open(encoding='utf-8-sig')))
    assert data,'JMeter did not produce samples; inspect private log'
    elapsed=sorted(int(d['elapsed']) for d in data)
    seconds=(max(int(d['timeStamp'])+int(d['elapsed']) for d in data)-min(int(d['timeStamp']) for d in data))/1000
    result=dict(profile=profile,round=roundno,samples=len(data),errors=sum(d['success']!='true' for d in data),seconds=seconds,rps=len(data)/seconds,p50=elapsed[(len(elapsed)-1)*50//100],p95=elapsed[(len(elapsed)-1)*95//100],p99=elapsed[(len(elapsed)-1)*99//100],parameters={k:v for k,v in params.items() if k!='csv'},command=['java','-Xms256m','-Xmx512m','-jar','<JMETER>/bin/ApacheJMeter.jar','-n','-t','scripts/load/workload.jmx','-q','scripts/load/results.properties','-i','scripts/load/log4j2.xml','-l',name+'.jtl','-j','<PRIVATE>/'+name+'.log']+[f'-J{k}={"<PRIVATE>/"+name+".csv" if k=="csv" else v}' for k,v in params.items()])
    result['appResources']=dict(before=resource_before,after=resource_after,measurementWallSeconds=resource_seconds,averageCpuCores=(resource_after['CPU']-resource_before['CPU'])/resource_seconds,method='Windows Get-Process CPU seconds and working-set boundary samples; includes JMeter startup/shutdown, not continuous sampling')
    write(public/(name+'-summary.json'),result)
    if profile=='orders':return data,result
    assert len(data)==300 if profile=='cold' else len(data)>0
    assert result['errors']==0,result
    return data,result

def snapshot(m,voucher,responses,roundno,deadline=None):
    token=DEPENDENCY_DEADLINE.set(deadline)
    try:return snapshot_values(m,voucher,responses,roundno)
    finally:DEPENDENCY_DEADLINE.reset(token)

def snapshot_values(m,voucher,responses,roundno):
    return dict(initial=10,expectedOrders=10,expectedResponses=[dict(userId=m['userIds'][i],requestKey=f'{m["run"]}_r{roundno}_u{i}') for i in range(1,101)],responses=[{k:r[k] for k in ('userId','requestKey','outcome','orderId')} for r in responses],
      orders=rows(f'SELECT id,user_id,status FROM tb_voucher_order WHERE voucher_id={voucher}',['orderId','userId','status']),
      requests=rows(f'SELECT order_id,user_id,request_key,state,reason FROM tb_voucher_order_request WHERE voucher_id={voucher}',['orderId','userId','requestKey','state','reason']),
      failures=rows(f'SELECT order_id,user_id,reason,compensation_status FROM tb_voucher_order_failure WHERE voucher_id={voucher}',['orderId','userId','reason','compensation']),
      returns=rows(f'SELECT order_id,state FROM tb_order_stock_return WHERE voucher_id={voucher}',['orderId','state']),
      recoveries=rows(f'SELECT order_id,outcome FROM tb_voucher_order_recovery WHERE voucher_id={voucher}',['orderId','outcome']),
      sqlStock=int(sql(f'SELECT stock FROM tb_seckill_voucher WHERE voucher_id={voucher}')),redisStock=int(redis('GET','seckill:stock:'+voucher)))

def cleanup(m):
    # Caller must stop the dedicated app and retain authoritative evidence before entering here.
    for v in m['vouchers']:
        orderids=sql(f'SELECT id FROM tb_voucher_order WHERE voucher_id={v}').splitlines()
        if orderids:sql('DELETE FROM tb_order_lifecycle WHERE order_id IN ('+','.join(orderids)+')')
        for table in ['tb_order_stock_return','tb_voucher_order_recovery','tb_voucher_order_failure','tb_voucher_order_request','tb_voucher_order','tb_voucher_publication','tb_seckill_voucher']:
            sql(f'DELETE FROM {table} WHERE voucher_id={v}')
        sql(f'DELETE FROM tb_voucher WHERE id={v}')
        redis('DEL',*[k+v for k in ['seckill:stock:','seckill:order:','seckill:reservation:']])
    shops=','.join(m['shopIds']);users=','.join(m['userIds'])
    sql(f"START TRANSACTION; DELETE FROM tb_shop_permission WHERE shop_id IN ({shops}) AND user_id={m['userIds'][0]}; DELETE FROM tb_shop_cache_sync WHERE shop_id IN ({shops}); DELETE FROM tb_shop WHERE id IN ({shops}); DELETE FROM tb_user WHERE id IN ({users}); COMMIT")
    redis_pipeline([['DEL','login:token:'+t] for t in m['tokens']]+[['DEL','cache:shop:'+sid] for sid in m['shopIds']])
    m['cleaned']=True;save(m)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--jmeter',type=Path,required=True);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    with socket.socket() as probe:probe.bind(('127.0.0.1',18187))
    assert args.jar.is_file() and (args.jmeter/'bin/ApacheJMeter.jar').is_file(),'JAR/JMeter missing'
    source_sha=command(['git','rev-parse','HEAD'],cwd=ROOT)
    assert not command(['git','diff','--name-only','--','src/main'],cwd=ROOT),'Commit app source before measuring the packaged JAR'
    guard()
    args.output.mkdir(parents=True,exist_ok=False)
    run=time.strftime('%Y%m%d%H%M%S')+'-'+uuid.uuid4().hex[:6]
    m=fixture(PRIVATE/run);p=None;passed=False
    m['sourceGitSha']=source_sha;save(m)
    try:
        p=start_app(m,args.jar)
        summaries=[]
        for r in range(1,4):
            # One unmeasured request primes the single hot shop; cold shops have never been requested.
            assert api('/shop/'+m['shopIds'][0])['success']
            for profile in ['hot','cold']:
                _,summary=jmeter(m,args.jmeter,args.output,profile,r);summaries.append(summary)
            v=publish(m);data,summary=jmeter(m,args.jmeter,args.output,'orders',r,v);summaries.append(summary)
            deadline=time.monotonic()+60
            while True:
                try:state=snapshot(m,v,data,r,deadline=deadline)
                except subprocess.TimeoutExpired:
                    write(args.output/f'orders-{r}-timeout.json',dict(round=r,passed=False,reason='dependency timeout during reconciliation',fixturesRetained=True))
                    raise
                result=reconcile(state)
                if result['passed'] or time.monotonic()>=deadline:break
                time.sleep(1)
            write(args.output/f'orders-{r}-reconciliation.json',dict(voucherId=v,**result,snapshot=state))
            assert result['passed'],result
        environment=dict(
            run=run,gitSha=m['sourceGitSha'],jarSha256=sha(args.jar),
            planSha256=sha(ROOT/'scripts/load/workload.jmx'),
            propertiesSha256=sha(ROOT/'scripts/load/results.properties'),
            jmeter=command([JAVA,'-jar',str(args.jmeter/'bin/ApacheJMeter.jar'),
                '-i',str(ROOT/'scripts/load/log4j2.xml'),'-j',str(Path(m['private'])/'version.log'),'-v']),
            java=subprocess.run([JAVA,'-version'],stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,check=True,timeout=30).stdout.decode('utf-8',errors='replace'),
            os=platform.platform(),logicalCpus=os.cpu_count(),
            hardware=json.loads(command(['powershell','-NoProfile','-Command',
                "Get-CimInstance Win32_ComputerSystem | Select-Object Model,NumberOfLogicalProcessors,TotalPhysicalMemory | ConvertTo-Json"])),
            cpu=json.loads(command(['powershell','-NoProfile','-Command',
                "Get-CimInstance Win32_Processor | Select-Object Name | ConvertTo-Json"])),
            containerImages={name:json.loads(command([DOCKER,'inspect','--format','{{json .Config.Image}}',name]))
                for name in [MYSQL,REDIS,'smart-life-upgrade-nameserver-1','smart-life-upgrade-broker-1']},
            coLocated=True,
            backgroundScanners=dict(batch=False,lifecycle=False,orderRecovery=False,cacheSync=False),
            fixture=dict(shops=len(m['shopIds']),authenticatedUsers=100,rounds=3,initialStockEach=10),
            summaries=summaries)
        write(args.output/'environment.json',environment);passed=True
    finally:
        if p is not None:stop_app(p)
        if passed:cleanup(m)
        else:print('UNRESOLVED: fixtures retained for investigation:',m['private'])
    print('PASS',args.output)

if __name__=='__main__':main()
