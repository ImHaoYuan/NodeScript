package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.domain.engine.RunIdentityLease
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 每壳一份执行身份账（§7.5/A5）。票据只允许领取一次，绑定后不再按活性探针拒绝尾帧。
 * 所有状态在同锁转换，回调/等待/活性探针均在锁外；不以同 UID 凭据代替沙箱或能力授权。
 */
class RunIdentityRegistry(
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
    private val admissionMillis: Long = 10_000,
) : RunIdentityIssuer, AutoCloseable {
    private val lock = Any()
    private val random = SecureRandom()
    private val tokens = HashMap<String, Lease>()
    private val leases = HashSet<Lease>()
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(admissionMillis > 0)
        scope.launch {
            while (isActive) {
                delay(250)
                expireDue()
            }
        }
    }

    override fun issue(engineId: EngineId, engineRunId: Long): RunIdentityLease = synchronized(lock) {
        check(!closed) { "桥身份入口已关闭" }
        require(engineRunId > 0)
        var token: String
        do {
            token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        } while (tokens.containsKey(token))
        Lease(token, engineId, engineRunId, clock.nowMillis() + admissionMillis).also {
            tokens[token] = it
            leases.add(it)
        }
    }

    /** hello 先原子占票；spawn 确认晚到时挂起，不调用 controller，不占业务请求表。 */
    suspend fun authenticate(
        token: String,
        peerPid: Int?,
        connectionId: Long,
        onDrain: () -> Unit,
        onRevoke: () -> Unit,
    ): Binding {
        val lease = synchronized(lock) {
            val found = tokens.remove(token) ?: denied()
            if (closed || found.state != State.ISSUED || found.deadline <= clock.nowMillis()) {
                found.state = State.CLOSED
                leases.remove(found)
                denied()
            }
            found.state = State.CLAIMED
            found.onDrain = onDrain
            found.onRevoke = onRevoke
            found
        }
        var bound = false
        try {
            val spawn = lease.spawn.await()
            if (peerPid != null && spawn.pid != null && peerPid != spawn.pid) denied()
            if (!spawn.isAlive()) denied()
            return synchronized(lock) {
                if (closed || lease.state != State.CLAIMED || lease.deadline <= clock.nowMillis()) denied()
                lease.state = State.ACTIVE
                bound = true
                Binding(AuthenticatedRunContext(lease.engineId, lease.runId, connectionId)) { lease.disconnect() }
            }
        } finally {
            if (!bound) lease.revoke()
        }
    }

    /** 不再接入后立即释放；lease 仍由引擎持有，但旧清理永远不能碰到另一 lease。 */
    class Binding internal constructor(
        val caller: AuthenticatedRunContext,
        private val release: () -> Unit,
    ) : AutoCloseable {
        override fun close() = release()
    }

    private enum class State { ISSUED, CLAIMED, ACTIVE, DRAINING, CLOSED }
    private class Spawn(val pid: Int?, val isAlive: () -> Boolean)

    private inner class Lease(
        override val token: String,
        val engineId: EngineId,
        val runId: Long,
        val deadline: Long,
    ) : RunIdentityLease {
        var state = State.ISSUED
        val spawn = CompletableDeferred<Spawn>()
        var onDrain: (() -> Unit)? = null
        var onRevoke: (() -> Unit)? = null

        override fun confirmSpawn(pid: Int?, isAlive: () -> Boolean) {
            // completion 不需要持注册表锁；撤销若先发生，cancel 后的 deferred 不会再完成。
            spawn.complete(Spawn(pid, isAlive))
        }

        override fun naturalExit() {
            val callback = synchronized(lock) {
                if (state == State.DRAINING || state == State.CLOSED) return
                if (state == State.ACTIVE) {
                    state = State.DRAINING
                    onDrain
                } else {
                    detachLocked()
                    onRevoke.also { onDrain = null; onRevoke = null }
                }
            }
            spawn.cancel()
            callback?.invoke()
        }

        override fun revoke() {
            val callback = synchronized(lock) {
                if (state == State.CLOSED) return
                detachLocked()
                onRevoke.also { onDrain = null; onRevoke = null }
            }
            spawn.cancel()
            callback?.invoke()
        }

        fun disconnect() {
            synchronized(lock) {
                detachLocked()
                onDrain = null
                onRevoke = null
            }
            spawn.cancel()
        }

        private fun detachLocked() {
            state = State.CLOSED
            tokens.remove(token, this)
            leases.remove(this)
        }
    }

    /** 可推进时钟的收割入口；已认证连接不受建连期限影响。 */
    fun expireDue() {
        val expired = synchronized(lock) {
            leases.filter { (it.state == State.ISSUED || it.state == State.CLAIMED) && it.deadline <= clock.nowMillis() }
        }
        expired.forEach { lease ->
            // authenticate 可能刚刚赢得转换；不能撤掉一条已经 ACTIVE 的连接。
            val callback = synchronized(lock) {
                if (lease.state != State.ISSUED && lease.state != State.CLAIMED) return@forEach
                lease.state = State.CLOSED
                tokens.remove(lease.token, lease)
                leases.remove(lease)
                lease.onRevoke.also { lease.onDrain = null; lease.onRevoke = null }
            }
            lease.spawn.cancel()
            callback?.invoke()
        }
    }

    fun size(): Int = synchronized(lock) { leases.size }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            leases.toList()
        }
        all.forEach { it.revoke() }
        scope.cancel()
    }

    private fun denied(): Nothing = throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "桥连接身份无效或已失效")
}
