-- KEYS: global, address, session, requester. 같은 Redis Cluster 해시 슬롯을 사용한다.
-- ARGV: 고유 예약 ID, 서비스 시간당 상한, REQUEST 또는 RETRY.
local current = redis.call('TIME')
local now = tonumber(current[1]) * 1000 + math.floor(tonumber(current[2]) / 1000)
local hour = now - 3600000
local day = now - 86400000

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', hour)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
    return -1
end

local function reserve(key, ttl)
    redis.call('ZADD', key, now, ARGV[1])
    redis.call('EXPIRE', key, ttl)
end

if ARGV[3] == 'RETRY' then
    redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', day)
    if redis.call('ZCOUNT', KEYS[2], '(' .. hour, '+inf') >= 5
            or redis.call('ZCARD', KEYS[2]) >= 20 then
        return 0
    end
    reserve(KEYS[1], 3601)
    reserve(KEYS[2], 86401)
    return 1
end

for index = 2, 4 do
    redis.call('ZREMRANGEBYSCORE', KEYS[index], '-inf', day)
end

-- 요청자 IP: 1시간 30회/24시간 100회, 시작 세션: 1시간 10회/24시간 40회.
if redis.call('ZCOUNT', KEYS[3], '(' .. hour, '+inf') >= 10
        or redis.call('ZCARD', KEYS[3]) >= 40
        or redis.call('ZCOUNT', KEYS[4], '(' .. hour, '+inf') >= 30
        or redis.call('ZCARD', KEYS[4]) >= 100 then
    return -1
end

local addressAllowed = redis.call('ZCOUNT', KEYS[2], '(' .. hour, '+inf') < 5
        and redis.call('ZCARD', KEYS[2]) < 20

-- 계정 존재 여부/반송 여부/주소 잠금에 관계없이 같은 요청 경로로 예약한다.
reserve(KEYS[1], 3601)
if addressAllowed then reserve(KEYS[2], 86401) end
reserve(KEYS[3], 86401)
reserve(KEYS[4], 86401)
if addressAllowed then return 1 end
return 0
