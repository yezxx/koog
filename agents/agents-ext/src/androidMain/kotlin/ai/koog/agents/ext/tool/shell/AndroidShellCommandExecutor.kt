@file:Suppress("MissingKDocForPublicAPI")

package ai.koog.agents.ext.tool.shell

import ai.koog.agents.ext.tool.shell.ShellCommandExecutor.ExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell command executor for Android, using `/system/bin/sh` through [ProcessBuilder].
 *
 * Output is drained while the command runs (waiting for the process to exit first would fill the pipe buffer and
 * deadlock it). If the command times out, only the direct shell can be killed on Android (there is no
 * `java.lang.ProcessHandle`), so the drain itself is bounded by [OUTPUT_DRAIN_TIMEOUT_MILLIS] to guarantee the
 * executor returns even when a grandchild process keeps stdout open.
 *
 * @property environmentVariablesToDrop environment variables removed from the child shell.
 */
public class AndroidShellCommandExecutor(
    private val environmentVariablesToDrop: Set<String> = emptySet(),
) : ShellCommandExecutor {
    override suspend fun execute(command: String, workingDirectory: String?, timeoutSeconds: Int): ExecutionResult =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .apply {
                        workingDirectory?.let { directory(File(it)) }
                        environmentVariablesToDrop.forEach { environment().remove(it) }
                    }.redirectErrorStream(true)
                    .start()

            // Keep draining while the process runs; only keep the first MAX_OUTPUT_CHARS characters, but keep
            // reading the rest so that a chatty child process cannot block on a full pipe.
            val output = async(Dispatchers.IO) { process.readOutput() }
            val finished = process.waitFor(timeoutSeconds.coerceAtLeast(1).toLong(), TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()

            ExecutionResult(
                output = withTimeoutOrNull(OUTPUT_DRAIN_TIMEOUT_MILLIS) { output.await() } ?: "",
                exitCode = if (finished) process.exitValue() else null,
            )
        }

    private fun Process.readOutput(): String {
        val reader = inputStream.bufferedReader()
        val kept = StringBuilder()
        val buffer = CharArray(OUTPUT_BUFFER_CHARS)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            val remaining = MAX_OUTPUT_CHARS - kept.length
            if (remaining > 0) kept.appendRange(buffer, 0, minOf(read, remaining))
        }
        return kept.toString()
    }
}

private const val MAX_OUTPUT_CHARS = 256 * 1024
private const val OUTPUT_BUFFER_CHARS = 8 * 1024
private const val OUTPUT_DRAIN_TIMEOUT_MILLIS = 5_000L
