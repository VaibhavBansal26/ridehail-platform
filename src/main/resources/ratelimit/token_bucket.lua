-- Token bucket, evaluated atomically inside Redis so that every application instance shares one bucket per key.
--   KEYS[1]  bucket key
--   ARGV[1]  capacity (burst)
--   ARGV[2]  refill rate, tokens per millisecond
--   ARGV[3]  key time-to-live, ms (an idle bucket is full anyway, so it can be forgotten)
--   ARGV[4]  "now" in ms, or -1 to use Redis's own clock (production: instances never need synchronized clocks)
-- Returns { allowed (1/0), retry_after_ms }
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local ttl      = tonumber(ARGV[3])
local now      = tonumber(ARGV[4])
if now < 0 then
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local data   = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], ttl)
return { allowed, retry }
