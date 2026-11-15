package com.mrchess.core

import com.mrchess.core.engine.Score
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
        fun q(best: Int, played: Int, second: Int? = null, isBest: Boolean = false, sac: Boolean = false, routine: Boolean = false) =
            MoveQualityClassifier.classify(MoveQualityClassifier.Inputs(Score(best), second?.let { Score(it) }, Score(played), isBest, sac, false, routine))
        assertEquals(MoveQuality.BEST, q(50, 50, isBest = true))
        assertEquals(MoveQuality.BEST, q(50, 42))
        assertEquals(MoveQuality.GOOD, q(50, 20))
        assertEquals(MoveQuality.INACCURACY, q(50, -20))
        assertEquals(MoveQuality.MISTAKE, q(50, -150))
        assertEquals(MoveQuality.BLUNDER, q(50, -260))
        assertEquals(MoveQuality.GREAT, q(80, 80, second = -60, isBest = true))
        assertEquals(MoveQuality.BRILLIANT, q(80, 80, second = -150, isBest = true))
        assertEquals(MoveQuality.BEST, q(80, 80, second = -150, isBest = true, routine = true))
        assertEquals(MoveQuality.GREAT, q(600, 600, second = 300, isBest = true)) // others still win: not brilliant
        assertEquals(MoveQuality.BRILLIANT, q(250, 245, sac = true))
        assertEquals(MoveQuality.BEST, q(50, 45, sac = true)) // sacrifice but no real advantage
        assertEquals(MoveQuality.BLUNDER, MoveQualityClassifier.classify(
            MoveQualityClassifier.Inputs(Score(30), null, Score(mate = -4), false, false, false, false)))
    }
}
