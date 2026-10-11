-- RabbitMQ 版本只在 Redis 中原子预扣库存和记录用户，不写 Stream。
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
return 0
