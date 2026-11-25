package arisa.app.player

import java.net.InetAddress

/**
 * 一个下载域名背后的服务器 (DNS 给的那串 IP), 记每个的速度, 给分段下载挑这次连哪个.
 *
 * 夸克的下载域名 (如 `dl-pc-zb.drive.quark.cn`) 每次解析给出的 IP 不一样, 2026-11-25 查了 30 次一共 29 个,
 * 分在好几个省. 同一个 IP 的速度会随时间变: 那天晚上投影仪连 116.253.26.62 单个连接 1.4~3.6MB/s,
 * 同时连别的节点 7~11MB/s, 半小时后它又有 5~6MB/s. 系统 DNS 加 OkHttp 的连接复用会让一场播放一直连着
 * 起播时碰到的那个, 它慢下来就一直卡. 所以这里自己挑:
 * - 开头几个连接分给不同的 IP, 下完就知道各自多快;
 * - 之后优先给最快的, 按正在用的连接数分摊 (速度 / (连接数 + 1)), 几个连接不全挤在一个 IP 上;
 * - 每 [EXPLORE_EVERY] 次挑一次没量过的, 或者很久没量的, 慢的节点过一阵可能又快了;
 * - 下得慢被换掉的、连不上的, [AVOID_MS] 内不再挑.
 *
 * DNS 每 [RESOLVE_EVERY_MS] 重新解析一次, 新出现的 IP 加进来, 之前的不删.
 */
class DownloadNodes(
    private val resolve: () -> List<InetAddress>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 收到这么多字节以上的连接才算一次测速, 太少的受连接开头的等待影响太大. */
    private val minSample: Long = 256 * 1024,
) {
    class Node(val address: InetAddress) {
        /** 字节每秒, 最近几次测速的平均; [measuredAt] 为 0 时是没量过. */
        var speed = 0L
            internal set
        var measuredAt = 0L
            internal set
        /** 正在用它下载的连接数. */
        var inUse = 0
            internal set
        var avoidUntil = 0L
            internal set
        val ip: String get() = address.hostAddress ?: address.toString()
        override fun toString() = ip
    }

    private val nodes = mutableListOf<Node>()
    private var resolvedAt = 0L
    private var resolved = false
    private var picks = 0

    /** 挑一个 IP 并记为在用, 用完要 [release]. DNS 解析不出来时返回 null (照常按域名连). */
    @Synchronized
    fun pick(): Node? {
        val now = clock()
        if (!resolved || now - resolvedAt >= RESOLVE_EVERY_MS) refresh(now)
        if (nodes.isEmpty()) return null
        val usable = nodes.filter { it.avoidUntil <= now }.ifEmpty { nodes }
        picks++
        val explore = picks % EXPLORE_EVERY == 0
        val tested = usable.filter { it.measuredAt > 0 }
        val untested = usable.filter { it.measuredAt == 0L }
        val node = when {
            untested.isNotEmpty() && (tested.size < 2 || explore) -> untested.minBy { it.inUse }
            explore -> tested.filter { now - it.measuredAt > STALE_MS }.minByOrNull { it.measuredAt } ?: best(tested)
            else -> best(tested)
        }
        node.inUse++
        return node
    }

    private fun best(tested: List<Node>) = tested.maxBy { it.speed / (it.inUse + 1) }

    /**
     * 一个连接用完了. [bytes] 是从收到第一个字节到最后一个字节的 [millis] 毫秒里收到的,
     * [bad] 是因为慢、卡住或者出错被换掉的.
     */
    @Synchronized
    fun release(node: Node, bytes: Long, millis: Long, bad: Boolean) {
        node.inUse--
        val now = clock()
        if (bytes >= minSample && millis > 0) {
            val sample = bytes * 1000 / millis
            node.speed = if (node.measuredAt == 0L) sample else (node.speed + sample) / 2
            node.measuredAt = now
        }
        if (bad) {
            node.avoidUntil = now + AVOID_MS
            if (node.measuredAt == 0L) node.measuredAt = now
        }
    }

    /** 除了 [node] 还有能换的 IP. */
    @Synchronized
    fun hasOther(node: Node): Boolean {
        val now = clock()
        return nodes.any { it !== node && it.avoidUntil <= now }
    }

    /** 测试用: 所有 IP 的快照. */
    @Synchronized
    internal fun all(): List<Node> = nodes.toList()

    private fun refresh(now: Long) {
        resolved = true
        resolvedAt = now
        val found = runCatching(resolve).getOrDefault(emptyList())
        for (a in found) if (nodes.none { it.address == a }) nodes += Node(a)
    }

    companion object {
        const val RESOLVE_EVERY_MS = 30_000L
        const val EXPLORE_EVERY = 8
        const val STALE_MS = 60_000L
        const val AVOID_MS = 60_000L
    }
}
