-- KEYS: stock, eligible users, reservation owners. ARGV: voucher, user, order.
local stockKey, orderKey, ownerKey = KEYS[1], KEYS[2], KEYS[3]
local userId, orderId = ARGV[2], ARGV[3]

-- Validate before mutating: a Lua error does not undo earlier writes.
local stockType = redis.call('type', stockKey).ok
local orderType = redis.call('type', orderKey).ok
local ownerType = redis.call('type', ownerKey).ok
if stockType ~= 'string' and stockType ~= 'none' then return 3 end
if orderType ~= 'set' and orderType ~= 'none' then return 3 end
if ownerType ~= 'hash' and ownerType ~= 'none' then return 3 end
local rawStock = redis.call('get', stockKey)
if rawStock then
    local canonical = rawStock == '0' or string.match(rawStock, '^[1-9]%d*$')
    if not canonical or tonumber(rawStock) > 2147483647 then return 3 end
end
if redis.call('sismember', orderKey, userId) == 1 then
    if redis.call('hget', ownerKey, userId) == orderId then
        if not rawStock then return 3 end
        return 4
    end
    return 2
end
if redis.call('hexists', ownerKey, userId) == 1 then return 3 end
-- PENDING has already reserved; missing ownership is not permission to reserve twice.
if ARGV[4] == '0' then return 5 end
-- Same-order ownership above survives expiry/down-shelf. New reservations use
-- authoritative rules read under the shared activity lock, and Redis's clock.
if ARGV[5] ~= '1' then return 6 end
local beginTime,endTime=tonumber(ARGV[6]),tonumber(ARGV[7])
if not beginTime or not endTime or beginTime>=endTime then return 6 end
local now=tonumber(redis.call('TIME')[1])
if now<beginTime then return 7 end
if now>=endTime then return 8 end
if not rawStock then return 3 end
if tonumber(rawStock) <= 0 then return 1 end
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', ownerKey, userId, orderId)
return 0
