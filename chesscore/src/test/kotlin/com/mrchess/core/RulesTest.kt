package com.mrchess.core

import com.mrchess.core.rules.Game
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun perft(p: Position, d: Int): Long =
        if (d == 0) 1 else p.legalMoves().sumOf { perft(p.play(it, validate = false), d - 1) }

    // Reference counts from the Chess Programming Wiki perft results page.
    @Test fun perftStart() = assertEquals(197_281L, perft(Position.start(), 4))
    @Test fun perftKiwipete() = assertEquals(97_862L, perft(Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"), 3))
    @Test fun perftPos3() = assertEquals(43_238L, perft(Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"), 4))
    @Test fun perftPos4() = assertEquals(9_467L, perft(Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"), 3))
    @Test fun perftPos5() = assertEquals(62_379L, perft(Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8"), 3))

    @Test fun fenRoundTrip() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        assertEquals(fen, Position.fromFen(fen).toFen())
    }

    @Test fun sanAndPgnRoundTrip() {
        val pgn = """
            [Event "Test"]
            [White "A"]
            [Black "B"]
            1. e4 e5 2. Nf3 Nc6 3. Bb5 {Ruy Lopez} a6 (3... Nf6 4. O-O) 4. Ba4 Nf6 5. O-O Be7 6. Re1 b5 7. Bb3 d6 8. c3 O-O 9. h3 Nb8 10. d4 Nbd7 1-0
        """.trimIndent()
        val g = Pgn.parse(pgn)
        assertEquals(20, g.ply)
        assertEquals("Nbd7", g.sanMoves.last())
        val again = Pgn.parse(g.toPgn())
        assertEquals(g.current.toFen(), again.current.toFen())
    }

    @Test fun mateDetection() {
        val g = Game()
        listOf("f3", "e5", "g4", "Qh4#").forEach { g.playSan(it) }
        assertTrue(g.current.isCheckmate)
        assertEquals("0-1", g.result.pgn)
        assertEquals("Qh4#", g.sanMoves.last())
    }

    @Test fun enPassantAndPromotionSan() {
        val p = Position.fromFen("8/P7/8/3pP3/8/8/8/k6K w - d6 0 1")
        assertEquals("exd6", p.toSan(Move.fromUci("e5d6")))
        assertEquals("a8=Q+", p.toSan(Move.fromUci("a7a8q")))
    }

    @Test fun validateCatchesBadBoards() {
        assertTrue(Position.validate("8/8/8/8/8/8/8/K7 w - - 0 1").isNotEmpty())
        assertTrue(Position.validate(Position.START_FEN).isEmpty())
    }
}
