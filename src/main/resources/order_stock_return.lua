-- KEYS stock, lifetime eligibility, original owners, per-order returned marker.
-- Marker first: a committed return remains safe even if other keys later disappear.
local markerType=redis.call('type',KEYS[4]).ok
if markerType~='none' and markerType~='string' then return 2 end
local marker=redis.call('get',KEYS[4])
local proof=ARGV[1]..':'..ARGV[2]..':'..ARGV[3]
if marker then if marker==proof then return 1 else return 2 end end
if redis.call('type',KEYS[1]).ok~='string' or redis.call('type',KEYS[2]).ok~='set' or redis.call('type',KEYS[3]).ok~='hash' then return 2 end
local stock=redis.call('get',KEYS[1])
if not (stock=='0' or string.match(stock,'^[1-9]%d*$')) or tonumber(stock)>=2147483647 then return 2 end
if redis.call('sismember',KEYS[2],ARGV[2])~=1 or redis.call('hget',KEYS[3],ARGV[2])~=ARGV[3] then return 2 end
redis.call('incr',KEYS[1])
redis.call('set',KEYS[4],proof)
return 0
