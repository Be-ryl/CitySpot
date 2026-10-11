-- 只撤销仍存在的用户预扣；重复执行不会重复增加库存。
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCRBY', KEYS[1], 1)
    return 1
end
return 0
