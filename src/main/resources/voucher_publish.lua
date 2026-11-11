-- A fresh, unpublished SQL activity may seed once. Replays never overwrite stock.
local stockType=redis.call('type',KEYS[1]).ok
local orderType=redis.call('type',KEYS[2]).ok
local ownerType=redis.call('type',KEYS[3]).ok
if stockType~='none' and stockType~='string' then return 2 end
if orderType~='none' and orderType~='set' then return 2 end
if ownerType~='none' and ownerType~='hash' then return 2 end
local raw=redis.call('get',KEYS[1])
if raw then
    if not (raw=='0' or string.match(raw,'^[1-9]%d*$')) or tonumber(raw)>2147483647 then return 2 end
    return 0
end
if ARGV[1]~='1' then return 1 end
if redis.call('exists',KEYS[2])==1 or redis.call('exists',KEYS[3])==1 then return 3 end
local initial=ARGV[2]
if not (initial=='0' or string.match(initial,'^[1-9]%d*$')) or tonumber(initial)>2147483647 then return 2 end
redis.call('set',KEYS[1],initial)
return 0
