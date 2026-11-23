-- 그 사용자의 모든 기기 세션을 끊는다 — 전체 로그아웃·비밀번호 변경·탈퇴.
-- KEYS[1] 세션 목록(ZSET)  REFRESH_SESSIONS:USER:{publicId}
-- ARGV[1] 세션 키 접두사   REFRESH_TOKEN:USER:{publicId}:

local sessions = redis.call('ZRANGE', KEYS[1], 0, -1)
for _, sessionId in ipairs(sessions) do
    redis.call('DEL', ARGV[1] .. sessionId)
end
redis.call('DEL', KEYS[1])
return #sessions
