package arisa.app.player

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadNodesTest {
    private fun ip(last: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))

    private var now = 1_000L
    private var answer = listOf(ip(1), ip(2), ip(3))
    private var lookups = 0
    private val nodes = DownloadNodes({ lookups++; answer }, clock = { now }, minSample = 1)

    @Test fun firstPicksSpreadOverDifferentNodes() {
        val picked = List(3) { nodes.pick()!!.address }
        assertEquals(3, picked.toSet().size)
    }

    @Test fun prefersFasterNodeAndSpreadsLoad() {
        val (a, b, c) = List(3) { nodes.pick()!! }
        nodes.release(a, 9_000_000, 1000, bad = false)
        nodes.release(b, 4_000_000, 1000, bad = false)
        nodes.release(c, 1_000_000, 1000, bad = false)
        // 最快的 a 先给; a 有两个连接在用时 9/3 = 3, 不如 b 的 4/1, 不会全挤到 a 上
        val picks = List(3) { nodes.pick()!! }
        assertEquals(a, picks[0])
        assertTrue(picks.count { it === a } <= 2)
        assertTrue(c !in picks)
    }

    @Test fun slowOrFailedNodeIsAvoidedForAWhile() {
        val (a, b) = List(2) { nodes.pick()!! }
        nodes.release(a, 9_000_000, 1000, bad = true)
        nodes.release(b, 3_000_000, 1000, bad = false)
        repeat(5) { val n = nodes.pick()!!; assertNotEquals(a, n); nodes.release(n, 0, 0, false) }
        assertTrue(nodes.hasOther(b))
        // 过了一分钟 a 又能挑了 (它量到的最快, 除了轮到试没量过的那次, 都会挑它)
        now += DownloadNodes.AVOID_MS
        assertTrue(a in List(3) { nodes.pick() })
    }

    @Test fun allNodesAvoidedStillPicksOne() {
        val all = List(3) { nodes.pick()!! }
        all.forEach { nodes.release(it, 0, 0, bad = true) }
        assertTrue(nodes.pick() != null)
    }

    @Test fun reResolvesAndKeepsOldNodes() {
        nodes.pick()
        assertEquals(1, lookups)
        answer = listOf(ip(4))
        now += DownloadNodes.RESOLVE_EVERY_MS - 1
        nodes.pick()
        assertEquals(1, lookups)
        now += 1
        nodes.pick()
        assertEquals(2, lookups)
        assertEquals(listOf(1, 2, 3, 4), nodes.all().map { it.address.address.last().toInt() })
    }

    @Test fun unresolvableHostGivesNull() {
        val none = DownloadNodes({ throw java.net.UnknownHostException() }, clock = { now })
        assertNull(none.pick())
    }
}
