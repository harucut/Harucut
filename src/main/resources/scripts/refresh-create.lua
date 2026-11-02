-- 새 기기 세션. 사용자당 상한을 넘으면 오래 안 쓴 세션부터 끊는다 — Redis 는 64MB noeviction 이라 무한정 쌓으면 쓰기가 막힌다.
-- KEYS[1] 세션 키          REFRESH_TOKEN:USER:{publicId}:{sessionId}
-- KEYS[2] 세션 목록(ZSET)  REFRESH_SESSIONS:USER:{publicId}
-- ARGV[1] 토큰
-- ARGV[2] 세션 TTL (초)
-- ARGV[3] sessionId
-- ARGV[4] 지금 (ms)
-- ARGV[5] 사용자당 세션 상한
-- ARGV[6] 세션 키 접두사   REFRESH_TOKEN:USER:{publicId}:

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('ZADD', KEYS[2], ARGV[4], ARGV[3])

-- 마지막 사용이 TTL 보다 오래된 세션은 키가 이미 만료됐다 — 목록에서만 남아 있다
local expiredBefore = string.format('%.0f', tonumber(ARGV[4]) - tonumber(ARGV[2]) * 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. expiredBefore)

local over = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[5])
if over > 0 then
    local oldest = redis.call('ZRANGE', KEYS[2], 0, over - 1)
    for _, sessionId in ipairs(oldest) do
        redis.call('DEL', ARGV[6] .. sessionId)
        redis.call('ZREM', KEYS[2], sessionId)
    end
end

redis.call('EXPIRE', KEYS[2], ARGV[2])
return 1
