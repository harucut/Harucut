-- 고정 창 빈도 제한. 증가와 만료 설정을 한 번에 — 따로 하면 증가 뒤 죽었을 때 키가 영구히 남는다(Redis 64MB noeviction)
-- KEYS[1] = 카운터 key, ARGV[1] = 창 길이(ms)
-- 반환: 이번 요청을 포함한 창 안의 횟수
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
