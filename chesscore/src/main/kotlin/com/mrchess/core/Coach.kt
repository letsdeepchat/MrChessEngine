package com.mrchess.core

import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.engine.SearchLimit
import com.mrchess.core.engine.UciEngine
import com.mrchess.core.explain.Explanation
import com.mrchess.core.explain.MoveExplainer
import com.mrchess.core.engine.SearchResult
import com.mrchess.core.review.MoveAnnotation
import com.mrchess.core.review.MoveQualityClassifier
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level facade used by the app: the opponent plays at the chosen strength, while hints and
 * analysis always use full strength so that advice is the best the engine can find.
 */
class Coach(
    private val engine: UciEngine,
    private val threads: Int = 2,
    private val hashMb: Int = 64,
) {

    private var currentStrength: EngineStrength? = null
    /** Strength switch + search happen as one step, so a concurrent caller can't swap the strength in between. */
    private val lock = Mutex()

    /** Call after changing engine options elsewhere (e.g. game review) so the next search reconfigures. */
    fun invalidate() { currentStrength = null }

    private suspend fun ensure(strength: EngineStrength) {
        if (currentStrength != strength) {
            engine.configure(strength, threads, hashMb)
            currentStrength = strength
        }
    }

    /** Applies threads and hash ahead of the first move, so the first reply isn't delayed. */
    suspend fun warmUp() = lock.withLock { if (currentStrength == null) ensure(EngineStrength.FULL) }

    suspend fun opponentMove(pos: Position, strength: EngineStrength): Move? {
        val r = lock.withLock {
            ensure(strength)
            engine.search(pos.toFen(), SearchLimit.MoveTime(strength.moveTimeMs))
        }
        return r.bestMove?.let { Move.fromUci(it) }?.takeIf { pos.isLegal(it) }
    }

    /** Full-strength best move with explanation, comparing against the second-best line. */
    suspend fun hint(pos: Position, limit: SearchLimit = SearchLimit.MoveTime(1500)): Explanation? {
        if (pos.legalMoves().isEmpty()) return null
        val r = lock.withLock {
            ensure(EngineStrength.FULL)
            engine.search(pos.toFen(), limit, multiPv = 2)
        }
        return MoveExplainer.explain(pos, r)
    }

    /** Full-strength analysis (used for move-quality annotation). */
    suspend fun analyse(pos: Position, limit: SearchLimit, multiPv: Int = 2): SearchResult {
        if (pos.legalMoves().isEmpty()) return SearchResult(null, null, emptyList())
        return lock.withLock {
            ensure(EngineStrength.FULL)
            engine.search(pos.toFen(), limit, multiPv)
        }
    }

    fun stop() = engine.stop()
}

/** Annotates timeline moves with quality badges, caching engine results per position. */
class TimelineAnnotator(
    private val analyse: suspend (Position) -> SearchResult,
) {
    constructor(coach: Coach, limit: SearchLimit = SearchLimit.Depth(12)) : this({ p -> coach.analyse(p, limit, 2) })

    private val cache = HashMap<String, SearchResult>()

    suspend fun analysis(pos: Position): SearchResult {
        val key = pos.repetitionKey
        cache[key]?.let { return it }
        val r = if (pos.legalMoves().isEmpty()) SearchResult(null, null, emptyList()) else analyse(pos)
        cache[key] = r
        return r
    }

    suspend fun annotate(t: GameTimeline, index: Int): MoveAnnotation {
        val before = t.positionAt(index)
        val after = t.positionAt(index + 1)
        return MoveQualityClassifier.annotate(
            before, t.nodes[index].move, analysis(before), analysis(after), t.nodes.getOrNull(index - 1)?.move,
            t.nodes.getOrNull(index - 1)?.annotation?.winPercentLoss,
        )
    }

    /** Annotates every missing move; returns the fully annotated timeline. */
    suspend fun annotateAll(t: GameTimeline, onProgress: (Int, Int) -> Unit = { _, _ -> }): GameTimeline {
        var cur = t
        for (i in t.nodes.indices) {
            if (cur.nodes[i].annotation == null) cur = cur.annotate(i, annotate(cur, i))
            onProgress(i + 1, t.nodes.size)
        }
        return cur
    }
}
