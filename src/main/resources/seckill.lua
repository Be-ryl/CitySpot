-- 1. 读取Redis秒杀库存；key不存在时也按库存不足处理，避免对nil做数值转换
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 1
end

-- 2. 使用Set原子判断该用户是否已经购买当前优惠券
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 then
    return 2
end

-- 3. 资格校验通过后，原子预扣Redis库存并记录已下单用户
redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[2])

-- 4. 同一原子操作中写入Stream，确保预扣成功时订单消息一定存在
redis.call(
    'XADD', KEYS[3], '*',
    'id', ARGV[3],
    'userId', ARGV[2],
    'voucherId', ARGV[1]
)

return 0
