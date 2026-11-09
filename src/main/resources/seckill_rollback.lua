-- 1: released; 0: no owned reservation; 2: manual reconciliation needed.
local stockKey, orderKey, ownerKey = KEYS[1], KEYS[2], KEYS[3]
local userId, orderId, retainedId = ARGV[2], ARGV[3], ARGV[4]
local orderType = redis.call('type', orderKey).ok
local ownerType = redis.call('type', ownerKey).ok
if orderType ~= 'set' and orderType ~= 'none' then return 2 end
if ownerType ~= 'hash' and ownerType ~= 'none' then return 2 end
local owner = redis.call('hget', ownerKey, userId)
if not owner then
    -- Legacy eligibility has no proven owner: do not release it speculatively.
    if redis.call('sismember', orderKey, userId) == 1 then return 2 end
    return 0
end
if owner ~= orderId then return 0 end
if redis.call('type', stockKey).ok ~= 'string' then return 2 end
local rawStock = redis.call('get', stockKey)
local canonical = rawStock == '0' or string.match(rawStock, '^[1-9]%d*$')
if not canonical or tonumber(rawStock) >= 2147483647 then return 2 end
if retainedId == orderId then return 2 end
-- Ownership check and all changes are one atomic operation.
redis.call('incrby', stockKey, 1)
if retainedId and retainedId ~= '' then
    redis.call('sadd', orderKey, userId)
    redis.call('hset', ownerKey, userId, retainedId)
else
    redis.call('srem', orderKey, userId)
    redis.call('hdel', ownerKey, userId)
end
return 1
