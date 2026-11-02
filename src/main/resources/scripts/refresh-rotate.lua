-- 기기 세션 하나의 회전. 재사용이 잡히면 그 세션(토큰 가족)만 끊는다 — 다른 기기는 그대로다.
-- KEYS[1] 세션 키          REFRESH_TOKEN:USER:{publicId}:{sessionId}
-- KEYS[2] grace 키         REFRESH_GRACE:{제시된 토큰}
-- KEYS[3] 세션 목록(ZSET)  REFRESH_SESSIONS:USER:{publicId}
-- ARGV[1] 제시된 토큰
-- ARGV[2] 새로 만든 토큰
-- ARGV[3] 세션 TTL (초)
-- ARGV[4] grace TTL (초)
-- ARGV[5] sessionId
-- ARGV[6] 지금 (ms)

local current = redis.call('GET', KEYS[1])

if not current then
    redis.call('ZREM', KEYS[3], ARGV[5])
    return {'NO_SESSION', ''}
end

if current == ARGV[1] then
    redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
    redis.call('SET', KEYS[2], '1', 'EX', ARGV[4])
    redis.call('ZADD', KEYS[3], ARGV[6], ARGV[5])
    redis.call('EXPIRE', KEYS[3], ARGV[3])
    return {'ROTATED', ARGV[2]}
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    return {'GRACE', current}
end

redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[3], ARGV[5])
return {'REUSE', ''}
