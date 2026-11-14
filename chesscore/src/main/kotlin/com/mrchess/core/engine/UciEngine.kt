package com.mrchess.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File

/**
 * Talks UCI to a Stockfish process over stdin/stdout. Pure JVM so it runs on Android
 * (binary shipped as libstockfish.so in nativeLibraryDir) and on desktop for tests.
 *
 * All searches are serialised by a mutex; [stop] interrupts the running one.
 */
class UciEngine(
    private val command: List<String>,
    private val workingDir: File? = null,
) : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val searchMutex = Mutex()

    private val _info = MutableSharedFlow<SearchInfo>(extraBufferCapacity = 64)
    /** Live "info" lines of the running search, for progressive UI updates. */
    val info: SharedFlow<SearchInfo> = _info

    var engineName: String = "engine"
        private set
    val options = mutableMapOf<String, String>()

    val isRunning: Boolean get() = process?.isAlive == true

    suspend fun start(): UciEngine = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext this@UciEngine
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        workingDir?.let { pb.directory(it) }
        val p = pb.start()
        process = p
        writer = p.outputStream.bufferedWriter()
        readerJob = scope.launch {
            p.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.send(it) } }
        }
        send("uci")
        withTimeout(10_000) {
            while (true) {
                val l = lines.receive()
                when {
                    l.startsWith("id name ") -> engineName = l.removePrefix("id name ")
                    l.startsWith("option name ") -> parseOption(l)
                    l == "uciok" -> break
                }
            }
        }
        isReady()
        this@UciEngine
    }

    private fun parseOption(l: String) {
        val name = l.substringAfter("option name ").substringBefore(" type ")
        val default = l.substringAfter(" default ", "").substringBefore(" min ")
        options[name] = default
    }

    fun send(cmd: String) {
        val w = writer ?: error("Engine not started")
        synchronized(w) {
            w.write(cmd); w.newLine(); w.flush()
        }
    }

    suspend fun isReady() {
        send("isready")
        withTimeout(30_000) { while (lines.receive() != "readyok") Unit }
    }

    suspend fun setOption(name: String, value: Any) {
        if (options.isNotEmpty() && name !in options) return // engine does not support it
        send("setoption name $name value $value")
    }

    /** Applies a strength preset. Must not be called during a search. */
    suspend fun configure(strength: EngineStrength, threads: Int = 2, hashMb: Int = 64) = searchMutex.withLock {
        setOption("Threads", threads)
        setOption("Hash", hashMb)
        if (strength.limitElo != null) {
            setOption("Skill Level", 20)
            setOption("UCI_LimitStrength", true)
            setOption("UCI_Elo", strength.limitElo)
        } else {
            setOption("UCI_LimitStrength", false)
            setOption("Skill Level", strength.skillLevel)
        }
        isReady()
    }

    suspend fun newGame() = searchMutex.withLock {
        send("ucinewgame")
        isReady()
    }

    /**
     * Runs one search and returns the final result. [multiPv] > 1 returns alternative lines.
     * Scores in the result are from the side-to-move's perspective (UCI convention).
     */
    suspend fun search(
        fen: String,
        limit: SearchLimit,
        multiPv: Int = 1,
        moves: List<String> = emptyList(),
    ): SearchResult = searchMutex.withLock {
        withContext(Dispatchers.IO) {
            drain()
            setOption("MultiPV", multiPv)
            send(if (moves.isEmpty()) "position fen $fen" else "position fen $fen moves ${moves.joinToString(" ")}")
            send("go ${limit.toUci()}")
            val lastByPv = sortedMapOf<Int, SearchInfo>()
            var best: String? = null
            var ponder: String? = null
            try {
              withTimeout(limit.timeoutMs()) {
                while (true) {
                    val l = lines.receive()
                    if (l.startsWith("info ") && " pv " in l) {
                        val info = SearchInfo.parse(l) ?: continue
                        // A new depth with multipv 1 starts a fresh set of lines.
                        lastByPv[info.multiPv] = info
                        _info.tryEmit(info)
                    } else if (l.startsWith("bestmove")) {
                        val parts = l.split(' ')
                        best = parts.getOrNull(1)
                        ponder = parts.getOrNull(3)
                        break
                    }
                }
              }
            } catch (c: CancellationException) {
                // Caller gave up (or timeout): stop the engine and swallow its bestmove so the
                // next search does not read a stale result.
                withContext(NonCancellable) {
                    stop()
                    withTimeoutOrNull(5_000) { while (!lines.receive().startsWith("bestmove")) Unit }
                }
                throw c
            }
            val pvLines = lastByPv.values.toList()
            SearchResult(
                bestMove = best?.takeIf { it != "(none)" },
                ponder = ponder,
                lines = pvLines,
            )
        }
    }

    /** Stops the running search; the pending [search] call returns with what it has. */
    fun stop() { runCatching { send("stop") } }

    private fun drain() { while (lines.tryReceive().isSuccess) Unit }

    override fun close() {
        runCatching { send("quit") }
        runCatching { process?.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        process?.destroy()
        readerJob?.cancel()
        scope.cancel()
        process = null
    }
}

sealed interface SearchLimit {
    fun toUci(): String
    fun timeoutMs(): Long

    data class Depth(val depth: Int) : SearchLimit {
        override fun toUci() = "depth $depth"
        override fun timeoutMs() = 120_000L
    }

    data class MoveTime(val ms: Long) : SearchLimit {
        override fun toUci() = "movetime $ms"
        override fun timeoutMs() = ms + 15_000L
    }

    data class Nodes(val nodes: Long) : SearchLimit {
        override fun toUci() = "nodes $nodes"
        override fun timeoutMs() = 120_000L
    }

    /** Clock-based search; times in ms. */
    data class Clock(val wtime: Long, val btime: Long, val winc: Long = 0, val binc: Long = 0) : SearchLimit {
        override fun toUci() = "wtime $wtime btime $btime winc $winc binc $binc"
        override fun timeoutMs() = maxOf(wtime, btime) + 15_000L
    }
}

/** Engine score from the side-to-move's point of view. */
data class Score(val cp: Int? = null, val mate: Int? = null) {
    /** Centipawn-like value suitable for comparisons; mates map to ±(100000 - distance). */
    val comparable: Int
        get() = when {
            mate != null && mate > 0 -> 100_000 - mate
            mate != null -> -100_000 - mate
            else -> cp ?: 0
        }

    fun negate() = Score(cp?.let { -it }, mate?.let { -it })

    /** Winning chances in [-1, 1] using the logistic model Lichess uses for accuracy. */
    val winningChances: Double
        get() = when {
            mate != null -> if (mate > 0) 1.0 else -1.0
            else -> 2.0 / (1.0 + kotlin.math.exp(-0.00368208 * (cp ?: 0).coerceIn(-1000, 1000))) - 1.0
        }

    fun display(): String = when {
        mate != null -> if (mate > 0) "M$mate" else "-M${-mate}"
        else -> {
            val v = (cp ?: 0) / 100.0
            (if (v > 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", v)
        }
    }
}

data class SearchInfo(
    val depth: Int,
    val multiPv: Int,
    val score: Score,
    val pv: List<String>,
    val nodes: Long?,
    val nps: Long?,
) {
    companion object {
        fun parse(line: String): SearchInfo? {
            val t = line.split(' ')
            var depth = 0
            var mpv = 1
            var cp: Int? = null
            var mate: Int? = null
            var nodes: Long? = null
            var nps: Long? = null
            var pv: List<String> = emptyList()
            var i = 1
            while (i < t.size) {
                when (t[i]) {
                    "depth" -> depth = t.getOrNull(++i)?.toIntOrNull() ?: 0
                    "multipv" -> mpv = t.getOrNull(++i)?.toIntOrNull() ?: 1
                    "score" -> {
                        when (t.getOrNull(++i)) {
                            "cp" -> cp = t.getOrNull(++i)?.toIntOrNull()
                            "mate" -> mate = t.getOrNull(++i)?.toIntOrNull()
                        }
                        if (t.getOrNull(i + 1) == "lowerbound" || t.getOrNull(i + 1) == "upperbound") return null
                    }
                    "nodes" -> nodes = t.getOrNull(++i)?.toLongOrNull()
                    "nps" -> nps = t.getOrNull(++i)?.toLongOrNull()
                    "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
                    else -> {}
                }
                i++
            }
            if (pv.isEmpty() || (cp == null && mate == null)) return null
            return SearchInfo(depth, mpv, Score(cp, mate), pv, nodes, nps)
        }
    }
}

data class SearchResult(
    val bestMove: String?,
    val ponder: String?,
    /** Lines ordered by MultiPV rank (index 0 = best). */
    val lines: List<SearchInfo>,
) {
    val best: SearchInfo? get() = lines.firstOrNull()
    val second: SearchInfo? get() = lines.getOrNull(1)
}
