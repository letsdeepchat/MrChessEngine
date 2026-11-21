package com.mrchess.core

import com.mrchess.core.puzzle.PuzzleHistory
import com.mrchess.core.puzzle.PuzzleRating
import com.mrchess.core.puzzle.PuzzleSelector
import com.mrchess.core.puzzle.PuzzleSession
import com.mrchess.core.puzzle.PuzzleSet
import com.mrchess.core.rules.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
        assertTrue(up > 1531 && up < 1532.1)   // solving a much harder puzzle: almost the full K = 32
        assertTrue(down > 1499 && down < 1500) // missing it costs almost nothing
        assertEquals(16.0, PuzzleRating.k(PuzzleRating.PROVISIONAL), 0.0)
        assertTrue(PuzzleSet.next(PuzzleHistory(elo = 2700))!!.rating in 2400..3000)
    }

    @Test fun selectionIsRandomInWindowAndNeverRepeatsSolved() {
        val all = PuzzleSet.all
        val h = PuzzleHistory(elo = 2650)
        val picks = (0 until 200).map { PuzzleSet.next(h, random = Random(it))!! }
        assertTrue(picks.all { it.rating in 2500..2800 })
        assertTrue("picks should vary", picks.map { it.id }.toSet().size > 20)
        assertTrue("never file order", picks.first().id != all.first().id || picks[1].id != all[1].id)
        val solved = all.filter { kotlin.math.abs(it.rating - 2650) <= 300 }.drop(5).map { it.id }.toSet()
        val h2 = h.copy(solved = solved)
        repeat(100) { assertTrue(PuzzleSet.next(h2, random = Random(it))!!.id !in solved) }
    }

    @Test fun lowRatedPlayerStillGetsTheClosestPuzzles() {
        // The bundled set starts at 2500, so a new 1500 player gets the easiest ones, at random.
        val picks = (0 until 50).map { PuzzleSet.next(PuzzleHistory(), random = Random(it))!! }
        val easiest = PuzzleSet.all.sortedBy { it.rating }.take(PuzzleSelector.NEAREST).maxOf { it.rating }
        assertTrue(picks.all { it.rating <= easiest })
        assertTrue(picks.map { it.id }.toSet().size > 5)
    }

    @Test fun missedPuzzleReturnsOnlyAfterFiveOthers() {
        val p = PuzzleSet.all.first()
        var h = PuzzleHistory(elo = p.rating).record(p, solvedCleanly = false)
        assertEquals(p.rating - h.elo in 0..32, true)
        assertTrue(p.id !in PuzzleSelector.eligible(PuzzleSet.all, h, null).map { it.id })
        repeat(4) { i -> h = h.record(PuzzleSet.all[100 + i], solvedCleanly = true) }
        assertTrue(p.id !in PuzzleSelector.eligible(PuzzleSet.all, h, null).map { it.id })
        h = h.record(PuzzleSet.all[200], solvedCleanly = false)
        assertTrue(p.id in PuzzleSelector.eligible(PuzzleSet.all, h, null).map { it.id })
        h = h.record(p, solvedCleanly = true)
        assertTrue(p.id in h.solved && p.id !in h.failed)
        assertEquals(7, h.attempts)
    }
}
