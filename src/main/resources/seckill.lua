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
if not rawStock then return 1 end
local canonical = rawStock == '0' or string.match(rawStock, '^[1-9]%d*$')
if not canonical or tonumber(rawStock) > 2147483647 then return 3 end
if tonumber(rawStock) <= 0 then return 1 end
if redis.call('sismember', orderKey, userId) == 1 then return 2 end
if redis.call('hexists', ownerKey, userId) == 1 then return 3 end
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', ownerKey, userId, orderId)
return 0
