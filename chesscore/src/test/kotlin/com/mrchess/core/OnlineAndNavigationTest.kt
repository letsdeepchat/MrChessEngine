package com.mrchess.core

import com.mrchess.core.online.Elo
import com.mrchess.core.online.MatchTicket
import com.mrchess.core.online.Matchmaking
import com.mrchess.core.online.RatingFilter
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAndNavigationTest {
    private val chessComPgn = """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [White "a"]
        [Black "b"]
        [Result "1-0"]

        1. d4 {[%clk 0:02:59.9]} 1... d5 {[%clk 0:02:59.1]} 2. Nf3 $1 {[%clk 0:02:58]} 2... Nf6 (2... c5 3. c4) 3. c4 e6 4. Nc3 Be7
        5. Bg5 O-O 6. e3 h6 7. Bh4 b6 8. cxd5 Nxd5 9. Bxe7 Qxe7 10. Nxd5 exd5 1-0
    """.trimIndent()

    @Test fun pastedPgnStepsForwardAndBackThroughTheWholeTimeline() {
        val t0 = GameTimeline.fromGame(Pgn.parse(chessComPgn), atEnd = false)
        val n = t0.nodes.size
        assertEquals(20, n)
        assertEquals(n + 1, t0.fenTimeline.size)
        assertEquals(Position.START_FEN, t0.fenTimeline[0])
        var t = t0
        // > all the way, then < all the way, then > again: the stored line never changes.
        for (i in 1..n) { t = t.forward(); assertEquals(i, t.currentPly); assertEquals(t0.fenTimeline[i], t.fenAt(t.currentPly)) }
        assertEquals(t, t.forward()) // clamped at the end
        for (i in n - 1 downTo 0) { t = t.back(); assertEquals(i, t.currentPly); assertEquals(t0.nodes, t.nodes) }
        assertEquals(0, t.back().currentPly)
        t = t.goTo(7).forward().forward()
        assertEquals(9, t.currentPly)
        assertEquals(n, t.toEnd().currentPly)
        assertEquals(0, t.toStart().currentPly)
        // Replaying the stored move just steps forward; a different move branches.
        val back3 = t.goTo(3)
        assertEquals(t0.nodes, back3.play(t0.nodes[3].move).nodes)
        val branched = back3.play(Move.fromUci("a7a6"))
        assertEquals(4, branched.nodes.size)
        assertEquals(4, branched.currentPly)
    }

    @Test fun customFenTagIsPlyZero() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val t = GameTimeline.fromGame(Pgn.parse("[SetUp \"1\"]\n[FEN \"$fen\"]\n\n3. Bb5 a6 4. Ba4 *"), atEnd = false)
        assertEquals(fen, t.fenTimeline[0])
        assertEquals(4, t.fenTimeline.size)
        assertEquals("3. Bb5", t.moveLabel(0))
        assertEquals(3, t.forward().forward().forward().forward().currentPly)
    }

    @Test fun annotatingKeepsThePositionInstance() {
        val t = GameTimeline.fromGame(Pgn.parse(chessComPgn)).goTo(5)
        val p = t.position
        assertSame(p, t.copy(tags = t.tags + ("X" to "y")).position)
    }

    private fun ticket(uid: String, r: Int, f: RatingFilter, tc: String = "3+0", hb: Long = 1_000) =
        f.range(r).let { MatchTicket(uid, uid, r, tc, it.first, it.last, heartbeatMs = hb) }

    @Test fun matchmakingRespectsBothFilters() {
        val me = ticket("me", 1000, RatingFilter.PM_100)
        assertTrue(Matchmaking.compatible(me, ticket("a", 1080, RatingFilter.PM_100), 2_000))
        assertTrue(!Matchmaking.compatible(me, ticket("b", 1150, RatingFilter.ANY), 2_000)) // outside my ±100
        assertTrue(!Matchmaking.compatible(ticket("me", 1000, RatingFilter.ANY), ticket("c", 1300, RatingFilter.PM_200), 2_000)) // I'm outside theirs
        assertTrue(!Matchmaking.compatible(me, ticket("d", 1000, RatingFilter.PM_100, tc = "1+0"), 2_000))
        assertTrue(!Matchmaking.compatible(me, ticket("e", 1000, RatingFilter.PM_100, hb = 0), 1_000 + Matchmaking.STALE_MS + 1))
        assertTrue(!Matchmaking.compatible(me, me, 2_000))
        val best = Matchmaking.bestOpponent(me, listOf(ticket("far", 1090, RatingFilter.ANY), ticket("near", 1010, RatingFilter.ANY), ticket("x", 1500, RatingFilter.ANY)), 2_000)
        assertEquals("near", best?.uid)
        assertNull(Matchmaking.bestOpponent(me, emptyList(), 0))
    }

    @Test fun eloMovesBothWays() {
        assertEquals(1016, Elo.update(1000, 1000, 1.0))
        assertEquals(984, Elo.update(1000, 1000, 0.0))
        assertEquals(1000, Elo.update(1000, 1000, 0.5))
        assertNotEquals(1000, Elo.update(1000, 1400, 0.5))
    }
}
