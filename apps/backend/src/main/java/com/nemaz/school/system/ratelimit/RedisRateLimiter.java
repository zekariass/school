package com.nemaz.school.system.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.system.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link RateLimiter} on Redis. One atomic script per hit: increment the window's counter,
 * set its expiry on the first hit, return the count and the remaining time.
 */
@Component
class RedisRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	private static final RedisScript<List> HIT = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
			return {count, ttl}
			""", List.class);

	private final StringRedisTemplate redis;

	RedisRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void consume(String bucket, String key, int limit, Duration window, String problemCode) {
		String redisKey = "rl:" + bucket + ":" + digest(key);
		List<?> result;
		try {
			result = this.redis.execute(HIT, List.of(redisKey), Long.toString(window.toMillis()));
		}
		catch (DataAccessException ex) {
			log.warn("Rate limiter unavailable, allowing request for bucket {}: {}", bucket, ex.getMessage());
			return;
		}
		if (result == null || result.size() != 2) {
			return;
		}
		long count = ((Number) result.get(0)).longValue();
		long ttlMillis = ((Number) result.get(1)).longValue();
		if (count > limit) {
			throw ApiException.tooManyRequests(problemCode, (ttlMillis + 999) / 1000);
		}
	}

	private static String digest(String key) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
