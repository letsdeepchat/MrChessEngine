package com.mrchess.app.data

import android.content.Context
import com.mrchess.core.Coach
import com.mrchess.core.engine.UciEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the single Stockfish process. The binary is packaged as `libstockfish.so` so Android
 * installs it into nativeLibraryDir with execute permission (apps may not exec files they write).
 */
object EngineProvider {
    private val lock = Mutex()
    private var engine: UciEngine? = null
    private var coach: Coach? = null

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")

    suspend fun engine(context: Context): UciEngine = lock.withLock {
        engine?.takeIf { it.isRunning }?.let { return it }
        val bin = binary(context)
        check(bin.exists()) { "Engine binary not found at ${bin.path}" }
        UciEngine(listOf(bin.absolutePath), workingDir = context.filesDir).start().also {
            engine = it
            coach = null
        }
    }

    suspend fun coach(context: Context): Coach {
        val e = engine(context)
        return lock.withLock { coach ?: Coach(e, recommendedThreads()).also { coach = it } }
    }

    /** Threads to use: leave one big core free for the UI. */
    fun recommendedThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

    fun shutdown() {
        engine?.close()
        engine = null
        coach = null
    }
}
