package com.mrchess.core

import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.engine.UciEngine
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.explain.Tag
import com.mrchess.core.review.GameSummary
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.Position
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Integration tests against a real Stockfish binary (skipped if none is installed). */
class EngineExplainerTest {
    companion object {
        private var engine: UciEngine? = null

        @BeforeClass @JvmStatic fun setUp() {
            val path = System.getenv("STOCKFISH_PATH") ?: "/usr/games/stockfish"
            if (File(path).canExecute()) engine = runBlocking { UciEngine(listOf(path)).start() }
        }

        @AfterClass @JvmStatic fun tearDown() { engine?.close() }
    }

    private fun eng(): UciEngine { assumeTrue("Stockfish not installed", engine != null); return engine!! }

    private fun explain(fen: String, depth: Int = 18) = runBlocking {
        val e = eng()
        e.configure(EngineStrength.FULL, threads = 2)
        val pos = Position.fromFen(fen)
        val r = e.search(fen, SearchLimit.Depth(depth), multiPv = 2)
        MoveExplainer.explain(pos, r)!!.also {
            println("FEN $fen\n  ${it.headline}  eval ${it.scoreWhite.display()}  tags=${it.tags}\n  line: ${it.lineText(pos)}\n  " +
                it.reasons.joinToString("\n  ") + "\n  alt: ${it.alternative}")
        }
    }

    @Test fun mateInOne() {
        val e = explain("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1")
        assertEquals("Rd8#", e.san)
        assertTrue(Tag.MATE in e.tags)
    }

    @Test fun knightFork() {
        // Ne7+ forks king on g8 and queen on c8.
        val e = explain("2q3k1/8/8/3N4/8/8/8/6K1 w - - 0 1")
        assertEquals("Ne7+", e.san)
        assertTrue(e.tags.toString(), Tag.FORK in e.tags)
    }

    @Test fun freeCapture() {
        val e = explain("rnb1kbnr/pppp1ppp/8/4p1q1/3P4/8/PPP1PPPP/RN1QKBNR w KQkq - 0 1".replace("RN1QKBNR", "RNBQKBNR"))
        assertTrue(e.tags.toString(), Tag.CAPTURE in e.tags || Tag.WINS_MATERIAL in e.tags)
    }

    @Test fun absolutePin() {
        // Bb5 pins the c6 knight to the e8 king.
        val e = explain("r1bqkbnr/ppp2ppp/2np4/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 4", depth = 12)
        println("pin test played ${e.san}")
    }

    @Test fun onlyMoveTagWhenQueenAttacked() {
        // Black queen on d5 attacked by Nc3; most moves lose it.
        val e = explain("rnb1kbnr/ppp1pppp/8/3q4/8/2N5/PPPP1PPP/R1BQKBNR b KQkq - 1 3")
        assertTrue(e.tags.toString(), Tag.SAVES_PIECE in e.tags)
        assertTrue(e.tags.toString(), Tag.ONLY_MOVE !in e.tags || e.alternative != null)
    }

    @Test fun limitedStrengthPlays() = runBlocking {
        val e = eng()
        val coach = Coach(e)
        val mv = coach.opponentMove(Position.start(), EngineStrength.fromElo(1500, 200))
        assertNotNull(mv)
        e.configure(EngineStrength.FULL)
    }

    @Test fun annotatorFlagsBlunderAndSummary() = runBlocking {
        val e = eng()
        val coach = Coach(e)
        val g = Pgn.parse("1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0")
        val t = TimelineAnnotator(coach, SearchLimit.Depth(14)).annotateAll(GameTimeline.fromGame(g))
        t.nodes.forEach { println("${it.san} ${it.moveQualityBadge} d=${it.annotation?.deltaCp} :: ${it.rationaleText}") }
        assertEquals(MoveQuality.BLUNDER, t.nodes[5].moveQualityBadge)
        assertEquals("Qxf7#", t.nodes[5].annotation?.refutation?.san)
        val sum = GameSummary.of(t.nodes.map { it.mover to it.annotation })
        println("acc W=${sum.accuracyWhite} B=${sum.accuracyBlack} ${sum.counts}")
        assertEquals(5, sum.firstIndex[com.mrchess.core.rules.Side.BLACK]?.get(MoveQuality.BLUNDER))
    }
}

class MotifUnitTest {
    @org.junit.Test fun pinDetectedFromSyntheticPv() {
        val pos = Position.fromFen("r1bqkbnr/ppp2ppp/2np4/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 4")
        val info = com.mrchess.core.engine.SearchInfo(10, 1, com.mrchess.core.engine.Score(cp = 30), listOf("f1b5", "a7a6"), null, null)
        val e = MoveExplainer.explain(pos, info)!!
        println(e.reasons)
        org.junit.Assert.assertTrue(e.tags.toString(), Tag.PIN in e.tags)
    }

    @org.junit.Test fun skewerDetected() {
        // Re1+ skewers the king on e5 and the queen on e8.
        val pos = Position.fromFen("4q3/8/8/4k3/8/8/8/R5K1 w - - 0 1")
        val info = com.mrchess.core.engine.SearchInfo(10, 1, com.mrchess.core.engine.Score(cp = 900), listOf("a1e1"), null, null)
        val e = MoveExplainer.explain(pos, info)!!
        println(e.reasons)
        org.junit.Assert.assertTrue(e.tags.toString(), Tag.SKEWER in e.tags)
    }
}

class CancellationTest {
    @org.junit.Test fun cancelledSearchDoesNotLeakIntoNext() = runBlocking {
        val path = System.getenv("STOCKFISH_PATH") ?: "/usr/games/stockfish"
        assumeTrue(File(path).canExecute())
        val e = UciEngine(listOf(path)).start()
        try {
            val job = launch { e.search(Position.START_FEN, SearchLimit.MoveTime(10_000)) }
            kotlinx.coroutines.delay(300)
            job.cancel(); job.join()
            // Mate-in-one position: the answer must be Rd8, not a move from the start position.
            val r = e.search("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", SearchLimit.Depth(8))
            assertEquals("d1d8", r.bestMove)
        } finally { e.close() }
    }
}
