package com.mrchess.core

import com.mrchess.core.puzzle.PuzzleRating
import com.mrchess.core.puzzle.PuzzleSession
import com.mrchess.core.puzzle.PuzzleSet
import com.mrchess.core.rules.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleTest {
    @Test fun everyBundledPuzzleIsPlayable() {
        val all = PuzzleSet.all
        assertTrue("expected the GM set, got ${all.size}", all.size > 400)
        assertTrue(all.all { it.rating >= 2500 })
        for (p in all) {
            var s = PuzzleSession(p).opponentMove()
            while (!s.finished) {
                val (n, outcome) = s.userMove(s.expected!!)
                assertTrue("${p.id}: $outcome at ply ${s.ply}", outcome == PuzzleSession.Outcome.CORRECT || outcome == PuzzleSession.Outcome.SOLVED)
                s = if (n.finished) n else n.opponentMove()
            }
            assertEquals(PuzzleSession.State.SOLVED, s.state)
        }
    }

    @Test fun wrongMoveFails() {
        val p = PuzzleSet.all.first()
        val s = PuzzleSession(p).opponentMove()
        val wrong = s.position.legalMoves().first { it.uci != s.expected!!.uci && !s.position.play(it).isCheckmate }
        val (n, o) = s.userMove(wrong)
        assertEquals(PuzzleSession.Outcome.WRONG, o)
        assertEquals(PuzzleSession.State.FAILED, n.state)
        assertEquals(PuzzleSession.Outcome.IGNORED, n.userMove(Move.fromUci("a1a2")).second)
    }

    @Test fun ratingMoves() {
        val up = PuzzleRating.update(1500.0, 2600, solved = true, played = 0)
        val down = PuzzleRating.update(1500.0, 2600, solved = false, played = 0)
        assertTrue(up > 1560 && up < 1565)   // solving a much harder puzzle: almost the full K
        assertTrue(down > 1499 && down < 1500) // missing it costs almost nothing
        assertTrue(PuzzleSet.next(2700.0, emptySet())!!.rating in 2600..2800)
    }
}
