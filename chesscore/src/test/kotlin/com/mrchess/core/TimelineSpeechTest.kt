package com.mrchess.core

import com.mrchess.core.engine.Score
import com.mrchess.core.review.GameSummary
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.review.MoveQualityClassifier
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Move
import com.mrchess.core.speech.SpeechFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSpeechTest {
    private fun GameTimeline.uci(u: String) = play(Move.fromUci(u))

    @Test fun backForwardAndBranching() {
        var t = GameTimeline().uci("e2e4").uci("e7e5").uci("g1f3")
        assertEquals(3, t.currentPly)
        t = t.back().back()
        assertEquals(1, t.currentPly); assertTrue(t.canForward)
        // Same move as stored continuation: just steps forward, keeps the rest.
        t = t.uci("e7e5")
        assertEquals(2, t.currentPly); assertEquals(3, t.nodes.size)
        // Different move: truncates the redo branch.
        t = t.uci("b1c3")
        assertEquals(3, t.nodes.size); assertEquals("Nc3", t.nodes.last().san); assertFalse(t.canForward)
        t = t.toStart()
        assertEquals(0, t.currentPly); assertFalse(t.canBack)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", t.position.toFen())
        assertEquals(listOf("1. e4", "e5", "2. Nc3"), t.nodes.indices.map { t.moveLabel(it) })
        assertEquals(3, t.toEnd().currentPly)
    }

    @Test fun sanToSpeech() {
        assertEquals("Knight to c3", SpeechFormatter.san("Nc3"))
        assertEquals("Bishop takes f7, check", SpeechFormatter.san("Bxf7+"))
        assertEquals("Castles kingside", SpeechFormatter.san("O-O"))
        assertEquals("Castles queenside", SpeechFormatter.san("O-O-O"))
        assertEquals("Queen takes h7, checkmate", SpeechFormatter.san("Qxh7#"))
        assertEquals("e pawn takes d5", SpeechFormatter.san("exd5"))
        assertEquals("Pawn to e8, promotes to queen, check", SpeechFormatter.san("e8=Q+"))
        assertEquals("Rook from a to d1", SpeechFormatter.san("Rad1"))
        assertEquals("plus zero point four five", SpeechFormatter.eval("+0.45"))
        assertEquals("minus one point two zero", SpeechFormatter.eval("-1.20"))
        assertEquals("mate in three", SpeechFormatter.eval("M3"))
        assertEquals("Stops White's threat: Queen takes f7, checkmate would be checkmate.",
            SpeechFormatter.text("Stops White's threat: Qxf7# would be checkmate."))
        assertEquals("Takes control of the centre square d4.", SpeechFormatter.text("Takes control of the centre square d4."))
        assertEquals("Expected continuation: Knight to c3 Knight to f6.", SpeechFormatter.text("Expected continuation: 3.Nc3 Nf6."))
    }

    @Test fun classifierThresholds() {
        fun q(best: Int, played: Int, second: Int? = null, isBest: Boolean = false, sac: Boolean = false, routine: Boolean = false, book: Boolean = false) =
            MoveQualityClassifier.classify(MoveQualityClassifier.Inputs(Score(best), second?.let { Score(it) }, Score(played), isBest, sac, false, routine, book))
        assertEquals(MoveQuality.BEST, q(50, 50, isBest = true))
        assertEquals(MoveQuality.BEST, q(50, 42))                 // 0.7 % win-probability loss
        assertEquals(MoveQuality.GOOD, q(50, 20))                 // 2.8 %
        assertEquals(MoveQuality.GOOD, q(50, -20))                // 6.4 %: no longer an inaccuracy
        assertEquals(MoveQuality.INACCURACY, q(50, -80))          // 11.9 %, 130 cp
        assertEquals(MoveQuality.MISTAKE, q(50, -200))            // 22.2 %, 250 cp
        assertEquals(MoveQuality.BLUNDER, q(50, -400))            // 35.9 %, 450 cp
        assertEquals(MoveQuality.INACCURACY, MoveQualityClassifier.classify(  // missed mate but still +6.5
            MoveQualityClassifier.Inputs(Score(mate = 3), null, Score(650), false, false, false, false)))
        assertEquals(MoveQuality.GREAT, q(80, 80, second = -60, isBest = true))   // 2nd best is 12.8 % worse
        assertEquals(MoveQuality.BEST, q(80, 80, second = 40, isBest = true))
        assertEquals(MoveQuality.BEST, q(80, 80, second = -150, isBest = true, routine = true))
        assertEquals(MoveQuality.GREAT, q(600, 600, second = 300, isBest = true))
        assertEquals(MoveQuality.BRILLIANT, q(250, 245, sac = true))
        assertEquals(MoveQuality.BRILLIANT, q(50, 45, sac = true))  // sound sacrifice, position still balanced
        assertEquals(MoveQuality.BEST, q(-100, -110, sac = true))   // win% after below 45: not brilliant
        assertEquals(MoveQuality.BOOK, q(30, 10, book = true))
        assertEquals(MoveQuality.MISTAKE, q(30, -200, book = true)) // an opening trap is still a mistake
        assertEquals(MoveQuality.BLUNDER, MoveQualityClassifier.classify(
            MoveQualityClassifier.Inputs(Score(30), null, Score(mate = -4), false, false, false, false)))
    }

    @Test fun openingBook() {
        val g = com.mrchess.core.rules.Pgn.parse("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6")
        val t = GameTimeline.fromGame(g)
        assertTrue(com.mrchess.core.review.OpeningBook.size > 3000)
        val o = com.mrchess.core.review.OpeningBook.lookup(t.positionAt(5))
        assertTrue(o?.name?.contains("Ruy Lopez") == true)
        assertTrue(o!!.eco.startsWith("C6"))
        assertTrue(com.mrchess.core.review.OpeningBook.inBook(t.positionAt(0)))
        // Transposition: 1. Nf3 Nc6 2. e4 e5 reaches the same position as 1. e4 e5 2. Nf3 Nc6.
        val tr = GameTimeline.fromGame(com.mrchess.core.rules.Pgn.parse("1. Nf3 Nc6 2. e4 e5"))
        assertEquals(com.mrchess.core.review.OpeningBook.lookup(t.positionAt(4)), com.mrchess.core.review.OpeningBook.lookup(tr.positionAt(4)))
        val gs = GameSummary.of(listOf(com.mrchess.core.rules.Side.WHITE to bookAnnotation()))
        assertEquals(100.0, gs.accuracyWhite!!, 0.001)
    }

    private fun bookAnnotation() = com.mrchess.core.review.MoveAnnotation(
        MoveQuality.BOOK, 20, Score(30), Score(10), 1.8, null, null, null, null, "e4 is a book move", "King's Pawn Game",
    )
}
