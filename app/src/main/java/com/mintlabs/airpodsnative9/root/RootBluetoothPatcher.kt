package com.mintlabs.airpodsnative9.root

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

class RootBluetoothPatcher(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var patchedPid: Int? = null

    @Synchronized
    fun ensurePatched(): String? {
        if (!isArm64()) {
            return "Root AAP patch only supports arm64-v8a on this build"
        }

        val helper = ensureHelperExtracted() ?: return "Failed to extract root patch helper"
        val result = runCommand(arrayOf("su", "-c", "${helper.absolutePath} patch-live"))
        val success = result.exitCode == 0 &&
            (result.output.contains("patched pid=") || result.output.contains("already patched"))

        return if (success) {
            patchedPid = parsePid(result.output) ?: patchedPid
            Log.d(TAG, "Root Bluetooth patch ready: ${result.output}")
            null
        } else {
            val message = result.output.ifBlank { "Root patch failed (${result.exitCode})" }
            Log.w(TAG, "Root Bluetooth patch failed: $message")
            message
        }
    }

    @Synchronized
    fun restoreIfNeeded() {
        if (patchedPid == null) return

        val helper = ensureHelperExtracted() ?: return
        val result = runCommand(arrayOf("su", "-c", "${helper.absolutePath} restore-live"))
        if (result.exitCode == 0) {
            patchedPid = null
            Log.d(TAG, "Root Bluetooth patch restored: ${result.output}")
        } else {
            Log.w(TAG, "Root Bluetooth restore failed: ${result.output}")
        }
    }

    private fun ensureHelperExtracted(): File? {
        return try {
            val binDir = File(appContext.filesDir, "bin").apply { mkdirs() }
            val helperFile = File(binDir, "aap-root-helper")
            val markerFile = File(binDir, "aap-root-helper.version")
            val currentVersion = markerFile.takeIf { it.exists() }?.readText()?.trim()

            if (!helperFile.exists() || currentVersion != HELPER_VERSION) {
                appContext.assets.open(HELPER_ASSET_PATH).use { input ->
                    helperFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                helperFile.setExecutable(true, true)
                helperFile.setReadable(true, true)
                markerFile.writeText(HELPER_VERSION)
            }

            helperFile
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to prepare root helper", error)
            null
        }
    }

    private fun runCommand(command: Array<String>): CommandResult {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return CommandResult(-1, "command timed out")
            }

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            CommandResult(process.exitValue(), output)
        } catch (error: Throwable) {
            CommandResult(-1, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun parsePid(output: String): Int? {
        val match = PID_PATTERN.find(output) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    companion object {
        private const val TAG = "AirPodsNative9"
        private const val HELPER_ASSET_PATH = "bin/aap-root-helper.arm64"
        private const val HELPER_VERSION = "20260407-livepatch-v1"
        private const val COMMAND_TIMEOUT_MS = 6_000L
        private val PID_PATTERN = Regex("""pid=(\d+)""")
    }
}
