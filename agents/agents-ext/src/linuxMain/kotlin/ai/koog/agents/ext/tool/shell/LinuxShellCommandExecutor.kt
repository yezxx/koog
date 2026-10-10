@file:Suppress("MissingKDocForPublicAPI")
@file:OptIn(ExperimentalForeignApi::class)

package ai.koog.agents.ext.tool.shell

import ai.koog.agents.ext.tool.shell.ShellCommandExecutor.ExecutionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.system
import kotlin.random.Random

/**
 * Shell command executor for Linux (Kotlin/Native), implemented on top of posix `system()`.
 *
 * The command runs through `sh -c` under coreutils `timeout`; its output is redirected into a temporary file and read
 * back afterwards, so there is no pipe to drain (a child process keeping stdout open cannot hang the executor).
 *
 * @property environmentVariablesToDrop environment variables removed from the child shell. Useful when the agent is
 * launched from a development shell that leaks variables such as `LD_LIBRARY_PATH` or `TMPDIR` into child processes.
 */
public class LinuxShellCommandExecutor(
    private val environmentVariablesToDrop: Set<String> = emptySet(),
) : ShellCommandExecutor {
    override suspend fun execute(command: String, workingDirectory: String?, timeoutSeconds: Int): ExecutionResult =
        withContext(Dispatchers.IO) {
            val outputPath = tempOutputPath()
            val script =
                buildString {
                    workingDirectory?.let { append("cd ${it.shellQuoted()} && ") }
                    if (environmentVariablesToDrop.isNotEmpty()) {
                        append("env")
                        environmentVariablesToDrop.forEach { append(" -u ${it.shellQuoted()}") }
                        append(' ')
                    }
                    append("timeout ${timeoutSeconds.coerceAtLeast(1)}s sh -c ${command.shellQuoted()}")
                    append(" > ${outputPath.toString().shellQuoted()} 2>&1")
                }

            val status = system(script)
            ExecutionResult(
                output = readAndDelete(outputPath),
                exitCode = if (status >= 0) status shr EXIT_CODE_SHIFT else null,
            )
        }

    /**
     * Command output goes into a file under /tmp: the environment's TMPDIR may be empty or point to a missing
     * directory, and a file avoids the pipe-buffer draining problem entirely.
     */
    private fun tempOutputPath(): Path = Path("$OUTPUT_DIRECTORY/agent-shell-${Random.nextLong().toString(HEX_RADIX)}.log")

    /** Reads the captured output and deletes the temporary file; oversized outputs are reported instead of read. */
    private fun readAndDelete(path: Path): String {
        val metadata =
            SystemFileSystem.metadataOrNull(path)
                ?: return "(no output captured; the shell could not write to $path)"
        val size = metadata.size
        if (size > MAX_OUTPUT_BYTES) {
            SystemFileSystem.delete(path)
            return "(command printed $size bytes, more than the $MAX_OUTPUT_BYTES limit; narrow it down with head/tail/grep)"
        }

        return SystemFileSystem
            .source(path)
            .buffered()
            .use { it.readString() }
            .also { SystemFileSystem.delete(path) }
    }
}

private const val EXIT_CODE_SHIFT = 8
private const val MAX_OUTPUT_BYTES = 256 * 1024L
private const val HEX_RADIX = 16
private const val OUTPUT_DIRECTORY = "/tmp"

private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
