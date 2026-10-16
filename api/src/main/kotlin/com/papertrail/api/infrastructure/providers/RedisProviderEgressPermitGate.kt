package com.papertrail.api.infrastructure.providers

import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class RedisProviderEgressPermitGate(
    private val redis: StringRedisTemplate,
) : ProviderEgressPermitGate {
    override fun acquire(providerId: String, deadlineAt: Instant): ProviderEgressPermitGate.AcquireResult {
        if (providerId !in ProviderChainSnapshot.PROVIDER_IDS) {
            return ProviderEgressPermitGate.AcquireResult.Unavailable
        }

        val token = UUID.randomUUID().toString()
        val leaseKey = "$KEY_PREFIX:$providerId:lease"
        val rateKey = "$KEY_PREFIX:$providerId:last-grant"
        while (Instant.now().isBefore(deadlineAt)) {
            val result: Any? = try {
                redis.execute(
                    ACQUIRE_PERMIT_SCRIPT,
                    listOf(leaseKey, rateKey),
                    token,
                    LEASE_TTL_MILLIS.toString(),
                )
            } catch (_: DataAccessException) {
                return ProviderEgressPermitGate.AcquireResult.Unavailable
            } catch (_: RuntimeException) {
                return ProviderEgressPermitGate.AcquireResult.Unavailable
            }

            when (result) {
                GRANTED -> return ProviderEgressPermitGate.AcquireResult.Granted(
                    RedisPermit(redis, leaseKey, token),
                )
                null, INVALID -> return ProviderEgressPermitGate.AcquireResult.Unavailable
                is String -> {
                    if (!result.startsWith(BUSY_PREFIX)) return ProviderEgressPermitGate.AcquireResult.Unavailable
                    val waitMillis = result.substringAfter(BUSY_PREFIX).toLongOrNull()
                        ?.takeIf { it in 1..MAX_WAIT_MILLIS }
                        ?: return ProviderEgressPermitGate.AcquireResult.Unavailable
                    val remainingMillis = deadlineAt.toEpochMilli() - System.currentTimeMillis()
                    if (remainingMillis <= 0) return ProviderEgressPermitGate.AcquireResult.DeadlineExceeded
                    try {
                        Thread.sleep(minOf(waitMillis, remainingMillis))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return ProviderEgressPermitGate.AcquireResult.Unavailable
                    }
                }
                else -> return ProviderEgressPermitGate.AcquireResult.Unavailable
            }
        }
        return ProviderEgressPermitGate.AcquireResult.DeadlineExceeded
    }

    private class RedisPermit(
        private val redis: StringRedisTemplate,
        private val leaseKey: String,
        private val token: String,
    ) : ProviderEgressPermitGate.Permit {
        override fun release(): ProviderEgressPermitGate.ReleaseResult = try {
            when (redis.execute(RELEASE_PERMIT_SCRIPT, listOf(leaseKey), token)) {
                RELEASED -> ProviderEgressPermitGate.ReleaseResult.RELEASED
                NOT_OWNER -> ProviderEgressPermitGate.ReleaseResult.NOT_OWNER
                else -> ProviderEgressPermitGate.ReleaseResult.UNAVAILABLE
            }
        } catch (_: DataAccessException) {
            ProviderEgressPermitGate.ReleaseResult.UNAVAILABLE
        } catch (_: RuntimeException) {
            ProviderEgressPermitGate.ReleaseResult.UNAVAILABLE
        }
    }

    companion object {
        private const val KEY_PREFIX = "paper-trail:provider-chain:egress:v1"
        private const val LEASE_TTL_MILLIS = 30_000L
        private const val RATE_WINDOW_MILLIS = 1_000L
        private const val RATE_KEY_TTL_MILLIS = 2_000L
        private const val MAX_WAIT_MILLIS = 1_000L
        private const val GRANTED = "GRANTED"
        private const val INVALID = "INVALID"
        private const val RELEASED = "RELEASED"
        private const val NOT_OWNER = "NOT_OWNER"
        private const val BUSY_PREFIX = "BUSY:"

        private val ACQUIRE_PERMIT_SCRIPT = DefaultRedisScript(
            """
            local server_time = redis.call('TIME')
            local seconds = tonumber(server_time[1])
            local microseconds = tonumber(server_time[2])
            if not seconds or not microseconds then return 'INVALID' end
            local now_millis = seconds * 1000 + math.floor(microseconds / 1000)
            if redis.call('GET', KEYS[1]) then
                local lease_ttl = redis.call('PTTL', KEYS[1])
                if lease_ttl < 0 then return 'INVALID' end
                if lease_ttl > 1000 then lease_ttl = 1000 end
                if lease_ttl < 1 then lease_ttl = 1 end
                return 'BUSY:' .. tostring(lease_ttl)
            end
            local previous_grant = redis.call('GET', KEYS[2])
            if previous_grant then
                local previous_millis = tonumber(previous_grant)
                if not previous_millis then return 'INVALID' end
                local elapsed = now_millis - previous_millis
                if elapsed < $RATE_WINDOW_MILLIS then
                    local wait_millis = $RATE_WINDOW_MILLIS - elapsed
                    if wait_millis < 1 then wait_millis = 1 end
                    return 'BUSY:' .. tostring(wait_millis)
                end
            end
            redis.call('SET', KEYS[2], tostring(now_millis), 'PX', $RATE_KEY_TTL_MILLIS)
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 'GRANTED'
            """.trimIndent(),
            String::class.java,
        )

        private val RELEASE_PERMIT_SCRIPT = DefaultRedisScript(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 'RELEASED'
            end
            return 'NOT_OWNER'
            """.trimIndent(),
            String::class.java,
        )
    }
}
