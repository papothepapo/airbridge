package com.mintlabs.airpodsnative9.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class AapClient(
    context: Context,
    private val preferredAddress: String?,
    private val profileOverride: DeviceProfileOverride,
    private val callback: Callback,
    private val prepareSocket: (() -> String?)? = null,
) {
    interface Callback {
        fun onDiagnostics(state: String, deviceLabel: String?, error: String?)
        fun onSnapshot(snapshot: Snapshot)
    }

    data class Snapshot(
        val address: String,
        val deviceName: String?,
        val modelNumber: String?,
        val modelName: String?,
        val firmwareVersion: String?,
        val features: AirPodsFeatures,
        val leftBattery: Int?,
        val rightBattery: Int?,
        val caseBattery: Int?,
        val leftInEar: Boolean,
        val rightInEar: Boolean,
        val ancMode: AncMode?,
        val conversationAwarenessEnabled: Boolean?,
        val conversationAwarenessSpeaking: Boolean?,
        val personalizedVolumeEnabled: Boolean?,
        val adaptiveAudioNoiseLevel: Int?,
        val microphoneMode: MicrophoneMode?,
        val ncWithOneAirPodEnabled: Boolean?,
        val earDetectionEnabled: Boolean?,
        val volumeSwipeEnabled: Boolean?,
        val volumeSwipeLength: VolumeSwipeLength?,
        val toneVolume: Int?,
        val inCaseToneEnabled: Boolean?,
        val sleepDetectionEnabled: Boolean?,
        val pressSpeed: PressSpeed?,
        val pressHoldDuration: PressHoldDuration?,
        val allowOffOptionEnabled: Boolean?,
        val eqBands: List<Float>?,
        val lastMessageAtMs: Long,
    ) {
        val supportedAncModes: List<AncMode>
            get() = features.supportedAncModes
    }

    private val running = AtomicBoolean(false)
    private val appContext = context.applicationContext
    private val socketFactory = L2capSocketFactory()
    private val framer = AapFramer()
    private val writeLock = Any()

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    @Volatile
    private var activeSession: SessionState? = null

    @Volatile
    private var connectionReady = false

    @Volatile
    private var cachedConnectedAudioAddresses = emptySet<String>()

    @Volatile
    private var cachedConnectedAudioAtMs = 0L

    @Volatile
    private var nextAttemptAtMs = 0L

    @Volatile
    private var consecutiveFailures = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workerThread = Thread(::runLoop, "AirPodsAapClient").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        callback.onDiagnostics("stopped", null, null)
        connectionReady = false
        activeSession = null
        closeQuietly(activeSocket)
        workerThread?.interrupt()
        workerThread = null
    }

    fun send(command: AapCommand): Boolean {
        val socket = activeSocket ?: return false
        val session = activeSession ?: return false
        if (!connectionReady) return false

        val packet = session.encode(command) ?: return false

        return try {
            synchronized(writeLock) {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
            }
            if (session.applyLocalCommand(command)) {
                callback.onSnapshot(session.snapshot())
            }
            true
        } catch (error: Throwable) {
            callback.onDiagnostics(
                "error",
                session.label(),
                error.message ?: error.javaClass.simpleName
            )
            false
        }
    }

    private fun runLoop() {
        while (running.get()) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                callback.onDiagnostics("bluetooth_off", null, null)
                sleepQuietly(2_000L)
                continue
            }

            val retryDelayMs = nextAttemptAtMs - System.currentTimeMillis()
            if (retryDelayMs > 0L) {
                callback.onDiagnostics("backoff", null, "Retrying AAP in ${((retryDelayMs + 999L) / 1000L)}s")
                sleepQuietly(minOf(2_000L, retryDelayMs))
                continue
            }

            val candidates = findCandidates(adapter)
            if (candidates.isEmpty()) {
                clearRetryBackoff()
                callback.onDiagnostics("searching", null, "No connected AirPods-like audio device found")
                sleepQuietly(4_000L)
                continue
            }

            Log.d(TAG, "AAP candidates: ${candidates.joinToString { "${it.device.address}:${it.score}" }}")

            var sessionStarted = false
            for (candidate in candidates) {
                if (!running.get()) break
                try {
                    val device = candidate.device
                    prepareSocket?.invoke()?.let { error ->
                        callback.onDiagnostics("patch_warning", labelFor(device), error)
                    }
                    runSession(device)
                    sessionStarted = true
                } catch (error: Throwable) {
                    scheduleRetryBackoff()
                    callback.onDiagnostics(
                        "error",
                        labelFor(candidate.device),
                        error.message ?: error.javaClass.simpleName
                    )
                    Log.w(TAG, "AAP session failed for ${candidate.device.address}", error)
                } finally {
                    connectionReady = false
                    activeSession = null
                    closeQuietly(activeSocket)
                    activeSocket = null
                    framer.reset()
                }
                if (sessionStarted || !running.get()) break
                sleepQuietly(1_500L)
            }

            if (running.get()) {
                callback.onDiagnostics("searching", null, null)
                sleepQuietly(2_000L)
            }
        }
    }

    private data class Candidate(
        val device: BluetoothDevice,
        val score: Int,
    )

    private fun findCandidates(adapter: BluetoothAdapter): List<Candidate> {
        val connectedAddresses = connectedAudioAddresses(adapter)
        if (connectedAddresses.isEmpty()) return emptyList()
        return adapter.bondedDevices
            .orEmpty()
            .map { device -> Candidate(device, score(device, connectedAddresses)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
    }

    private fun score(device: BluetoothDevice, connectedAddresses: Set<String>): Int {
        var score = 0
        val matchesPreferred = preferredAddress != null && device.address.equals(preferredAddress, ignoreCase = true)
        val name = device.name?.lowercase(Locale.US).orEmpty()
        val appleLike = name.contains("airpods") || name.contains("beats") || name.contains("apple")
        val connectedAudio = connectedAddresses.any { it.equals(device.address, ignoreCase = true) }

        if (!appleLike && !matchesPreferred) return 0

        if (matchesPreferred) score += 2_000
        if (connectedAudio) score += 4_000
        if ("airpods" in name) score += 500
        if ("beats" in name) score += 450
        if ("apple" in name) score += 100
        if (device.type == BluetoothDevice.DEVICE_TYPE_DUAL) score += 80

        when (device.bluetoothClass?.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> score += 100
        }
        if (device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            score += 50
        }

        return score
    }

    private fun connectedAudioAddresses(adapter: BluetoothAdapter): Set<String> {
        val now = System.currentTimeMillis()
        if (now - cachedConnectedAudioAtMs <= CONNECTED_AUDIO_CACHE_MS) {
            return cachedConnectedAudioAddresses
        }

        val addresses = linkedSetOf<String>()
        addresses += connectedProfileAddresses(adapter, BluetoothProfile.A2DP)
        addresses += connectedProfileAddresses(adapter, BluetoothProfile.HEADSET)
        cachedConnectedAudioAddresses = addresses
        cachedConnectedAudioAtMs = now
        return addresses
    }

    private fun connectedProfileAddresses(adapter: BluetoothAdapter, profile: Int): Set<String> {
        val latch = CountDownLatch(1)
        val addresses = linkedSetOf<String>()
        var proxyRef: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                proxyRef = proxy
                try {
                    proxy.connectedDevices.orEmpty().mapTo(addresses) { it.address }
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }

        return try {
            if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
            latch.await(PROFILE_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            addresses
        } catch (_: Throwable) {
            emptySet()
        } finally {
            try {
                proxyRef?.let { adapter.closeProfileProxy(profile, it) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun runSession(device: BluetoothDevice) {
        callback.onDiagnostics("connecting", labelFor(device), null)
        val socket = socketFactory.createSocket(device, AAP_PSM)
        activeSocket = socket
        socket.connect()
        callback.onDiagnostics("handshaking", labelFor(device), null)

        writePacket(socket, HANDSHAKE_PACKET)
        NOTIFICATION_PACKETS.forEach { packet -> writePacket(socket, packet) }

        val session = SessionState(device.address, device.name, profileOverride)
        activeSession = session
        val buffer = ByteArray(2048)
        var firstPacketSeen = false
        val watchdog = Thread {
            try {
                Thread.sleep(FIRST_PACKET_TIMEOUT_MS)
                if (running.get() && !firstPacketSeen) {
                    callback.onDiagnostics("timeout", session.label(), "No AAP response after handshake")
                    closeQuietly(socket)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }

        try {
            while (running.get()) {
                val length = socket.inputStream.read(buffer)
                if (length <= 0) throw IOException("AAP socket closed")
                if (!firstPacketSeen) {
                    firstPacketSeen = true
                    connectionReady = true
                    clearRetryBackoff()
                    watchdog.interrupt()
                    callback.onDiagnostics("ready", session.label(), null)
                    callback.onSnapshot(session.snapshot())
                }

                val messages = framer.consume(buffer, 0, length)
                var changed = false
                messages.forEach { message ->
                    changed = session.consume(message) || changed
                }

                if (session.needsInitExtPacket()) {
                    writePacket(socket, INIT_EXT_PACKET)
                    session.markInitExtSent()
                }

                if (changed) {
                    callback.onSnapshot(session.snapshot())
                }
            }
        } finally {
            watchdog.interrupt()
            connectionReady = false
        }
    }

    private fun writePacket(socket: BluetoothSocket, bytes: ByteArray) {
        synchronized(writeLock) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    }

    private fun labelFor(device: BluetoothDevice): String =
        listOfNotNull(device.name, device.address).joinToString(" • ")

    private fun clearRetryBackoff() {
        consecutiveFailures = 0
        nextAttemptAtMs = 0L
    }

    private fun scheduleRetryBackoff() {
        consecutiveFailures = minOf(consecutiveFailures + 1, MAX_BACKOFF_STEPS)
        val delayMs = minOf(
            MAX_RETRY_BACKOFF_MS,
            INITIAL_RETRY_BACKOFF_MS * (1L shl (consecutiveFailures - 1))
        )
        nextAttemptAtMs = System.currentTimeMillis() + delayMs
    }

    private fun sleepQuietly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
        }
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private class SessionState(
        private val address: String,
        private var deviceName: String?,
        private val profileOverride: DeviceProfileOverride,
    ) {
        private val initialModel = AirPodsCatalog.resolve(
            modelNumber = null,
            fallbackName = deviceName,
            override = profileOverride
        )
        private var modelNumber: String? = null
        private var modelName: String? = initialModel.modelName
        private var firmwareVersion: String? = null
        private var features: AirPodsFeatures = initialModel.features

        private var leftBattery: Int? = null
        private var rightBattery: Int? = null
        private var caseBattery: Int? = null

        private var primaryPodSide: PodSide? = null
        private var primaryPlacement: PodPlacement? = null
        private var secondaryPlacement: PodPlacement? = null
        private var leftInEar = false
        private var rightInEar = false

        private var ancMode: AncMode? = null
        private var conversationAwarenessEnabled: Boolean? = null
        private var conversationAwarenessSpeaking: Boolean? = null
        private var personalizedVolumeEnabled: Boolean? = null
        private var adaptiveAudioNoiseLevel: Int? = null
        private var microphoneMode: MicrophoneMode? = null
        private var ncWithOneAirPodEnabled: Boolean? = null
        private var earDetectionEnabled: Boolean? = null
        private var volumeSwipeEnabled: Boolean? = null
        private var volumeSwipeLength: VolumeSwipeLength? = null
        private var toneVolume: Int? = null
        private var inCaseToneEnabled: Boolean? = null
        private var sleepDetectionEnabled: Boolean? = null
        private var pressSpeed: PressSpeed? = null
        private var pressHoldDuration: PressHoldDuration? = null
        private var allowOffOptionEnabled: Boolean? = null
        private var eqBands: List<Float>? = null

        private var initExtRequested = false
        private var initExtSent = false

        private var lastMessageAtMs = System.currentTimeMillis()

        fun label(): String = listOfNotNull(deviceName, address).joinToString(" • ")

        fun needsInitExtPacket(): Boolean = initExtRequested && !initExtSent

        fun markInitExtSent() {
            initExtSent = true
        }

        fun snapshot(): Snapshot = Snapshot(
            address = address,
            deviceName = deviceName,
            modelNumber = modelNumber,
            modelName = modelName,
            firmwareVersion = firmwareVersion,
            features = features,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            ancMode = ancMode,
            conversationAwarenessEnabled = conversationAwarenessEnabled,
            conversationAwarenessSpeaking = conversationAwarenessSpeaking,
            personalizedVolumeEnabled = personalizedVolumeEnabled,
            adaptiveAudioNoiseLevel = adaptiveAudioNoiseLevel,
            microphoneMode = microphoneMode,
            ncWithOneAirPodEnabled = ncWithOneAirPodEnabled,
            earDetectionEnabled = earDetectionEnabled,
            volumeSwipeEnabled = volumeSwipeEnabled,
            volumeSwipeLength = volumeSwipeLength,
            toneVolume = toneVolume,
            inCaseToneEnabled = inCaseToneEnabled,
            sleepDetectionEnabled = sleepDetectionEnabled,
            pressSpeed = pressSpeed,
            pressHoldDuration = pressHoldDuration,
            allowOffOptionEnabled = allowOffOptionEnabled,
            eqBands = eqBands,
            lastMessageAtMs = lastMessageAtMs,
        )

        fun consume(message: AapMessage): Boolean {
            lastMessageAtMs = System.currentTimeMillis()
            return when (message.commandType) {
                CMD_BATTERY -> decodeBattery(message.payload)
                CMD_EAR_DETECTION -> decodeEarDetection(message.payload)
                CMD_PRIMARY_POD -> decodePrimaryPod(message.payload)
                CMD_DEVICE_INFO -> decodeDeviceInfo(message.payload)
                CMD_SETTINGS -> decodeSetting(message.payload)
                CMD_CONVERSATION_AWARENESS_STATE -> decodeConversationAwarenessState(message.payload)
                CMD_EQ_DATA -> decodeEqData(message.payload)
                else -> false
            }
        }

        fun encode(command: AapCommand): ByteArray? {
            return when (command) {
                is AapCommand.SetAncMode -> {
                    if (command.mode !in features.supportedAncModes) return null
                    buildSettingsMessage(SETTING_ANC_MODE, encodeAncMode(command.mode))
                }
                is AapCommand.SetConversationalAwareness -> {
                    if (!features.hasConversationAwareness) return null
                    buildSettingsMessage(SETTING_CONVERSATIONAL_AWARENESS, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetPersonalizedVolume -> {
                    if (!features.hasPersonalizedVolume) return null
                    buildSettingsMessage(SETTING_PERSONALIZED_VOLUME, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetAdaptiveAudioNoise -> {
                    if (!features.hasAdaptiveAudioNoise) return null
                    buildSettingsMessage(SETTING_ADAPTIVE_AUDIO_NOISE, command.level.coerceIn(0, 100))
                }
                is AapCommand.SetMicrophoneMode -> {
                    if (!features.hasMicrophoneMode) return null
                    buildSettingsMessage(SETTING_MICROPHONE_MODE, encodeMicrophoneMode(command.mode))
                }
                is AapCommand.SetNcWithOneAirPod -> {
                    if (!features.hasNcOneAirpod) return null
                    buildSettingsMessage(SETTING_NC_ONE_AIRPOD, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetEarDetectionEnabled -> {
                    if (!features.hasEarDetectionToggle) return null
                    buildSettingsMessage(SETTING_EAR_DETECTION_ENABLED, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetVolumeSwipe -> {
                    if (!features.hasVolumeSwipe) return null
                    buildSettingsMessage(SETTING_VOLUME_SWIPE, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetVolumeSwipeLength -> {
                    if (!features.hasVolumeSwipeLength) return null
                    buildSettingsMessage(SETTING_VOLUME_SWIPE_LENGTH, encodeVolumeSwipeLength(command.value))
                }
                is AapCommand.SetToneVolume -> {
                    if (!features.hasToneVolume) return null
                    buildSettingsMessage(SETTING_TONE_VOLUME, command.level.coerceIn(15, 100))
                }
                is AapCommand.SetInCaseTone -> {
                    if (!features.hasInCaseTone) return null
                    buildSettingsMessage(SETTING_IN_CASE_TONE, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetSleepDetection -> {
                    if (!features.hasSleepDetection) return null
                    buildSettingsMessage(SETTING_SLEEP_DETECTION, encodeAppleBool(command.enabled))
                }
                is AapCommand.SetPressSpeed -> {
                    if (!features.hasPressSpeed) return null
                    buildSettingsMessage(SETTING_PRESS_SPEED, encodePressSpeed(command.value))
                }
                is AapCommand.SetPressHoldDuration -> {
                    if (!features.hasPressHoldDuration) return null
                    buildSettingsMessage(SETTING_PRESS_HOLD_DURATION, encodePressHoldDuration(command.value))
                }
                is AapCommand.SetAllowOffOption -> {
                    if (!features.hasAllowOffOption) return null
                    buildSettingsMessage(SETTING_ALLOW_OFF_OPTION, encodeAppleBool(command.enabled))
                }
            }
        }

        fun applyLocalCommand(command: AapCommand): Boolean = when (command) {
            is AapCommand.SetAncMode -> updateValue(command.mode, ancMode) { ancMode = it }
            is AapCommand.SetConversationalAwareness -> updateValue(command.enabled, conversationAwarenessEnabled) { conversationAwarenessEnabled = it }
            is AapCommand.SetPersonalizedVolume -> updateValue(command.enabled, personalizedVolumeEnabled) { personalizedVolumeEnabled = it }
            is AapCommand.SetAdaptiveAudioNoise -> {
                val value = command.level.coerceIn(0, 100)
                updateValue(value, adaptiveAudioNoiseLevel) { adaptiveAudioNoiseLevel = it }
            }
            is AapCommand.SetMicrophoneMode -> updateValue(command.mode, microphoneMode) { microphoneMode = it }
            is AapCommand.SetNcWithOneAirPod -> updateValue(command.enabled, ncWithOneAirPodEnabled) { ncWithOneAirPodEnabled = it }
            is AapCommand.SetEarDetectionEnabled -> updateValue(command.enabled, earDetectionEnabled) { earDetectionEnabled = it }
            is AapCommand.SetVolumeSwipe -> updateValue(command.enabled, volumeSwipeEnabled) { volumeSwipeEnabled = it }
            is AapCommand.SetVolumeSwipeLength -> updateValue(command.value, volumeSwipeLength) { volumeSwipeLength = it }
            is AapCommand.SetToneVolume -> {
                val value = command.level.coerceIn(15, 100)
                updateValue(value, toneVolume) { toneVolume = it }
            }
            is AapCommand.SetInCaseTone -> updateValue(command.enabled, inCaseToneEnabled) { inCaseToneEnabled = it }
            is AapCommand.SetSleepDetection -> updateValue(command.enabled, sleepDetectionEnabled) { sleepDetectionEnabled = it }
            is AapCommand.SetPressSpeed -> updateValue(command.value, pressSpeed) { pressSpeed = it }
            is AapCommand.SetPressHoldDuration -> updateValue(command.value, pressHoldDuration) { pressHoldDuration = it }
            is AapCommand.SetAllowOffOption -> updateValue(command.enabled, allowOffOptionEnabled) { allowOffOptionEnabled = it }
        }

        private fun decodeBattery(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            val count = payload[0].toInt() and 0xFF
            if (count == 0) return false

            var changed = false
            var offset = 1
            repeat(count) {
                if (offset + 5 > payload.size) return@repeat
                val component = payload[offset].toInt() and 0xFF
                val percent = payload[offset + 2].toInt() and 0xFF
                if (percent in 0..100) {
                    changed = when (component) {
                        0x04 -> updateValue(percent, leftBattery) { leftBattery = it } || changed
                        0x02 -> updateValue(percent, rightBattery) { rightBattery = it } || changed
                        0x08 -> updateValue(percent, caseBattery) { caseBattery = it } || changed
                        else -> changed
                    }
                }
                offset += 5
            }
            return changed
        }

        private fun decodeEarDetection(payload: ByteArray): Boolean {
            if (payload.size < 2) return false
            primaryPlacement = decodePlacement(payload[0].toInt() and 0xFF)
            secondaryPlacement = decodePlacement(payload[1].toInt() and 0xFF)
            return recomputeEarState()
        }

        private fun decodePrimaryPod(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            val newPrimary = when (payload[0].toInt() and 0xFF) {
                0x01 -> PodSide.LEFT
                0x02 -> PodSide.RIGHT
                else -> return false
            }
            if (newPrimary == primaryPodSide) return false
            primaryPodSide = newPrimary
            return recomputeEarState()
        }

        private fun decodeDeviceInfo(payload: ByteArray): Boolean {
            val strings = parseAsciiStrings(payload)
            if (strings.isEmpty()) return false

            var changed = false
            val newName = strings.getOrNull(0)
            val newModelNumber = strings.getOrNull(1)
            val newFirmwareVersion = strings.getOrNull(4)
            val resolved = AirPodsCatalog.resolve(newModelNumber, newName, profileOverride)

            if (!newName.isNullOrBlank() && newName != deviceName) {
                deviceName = newName
                changed = true
            }
            if (!newModelNumber.isNullOrBlank() && newModelNumber != modelNumber) {
                modelNumber = newModelNumber
                changed = true
            }
            if (!newFirmwareVersion.isNullOrBlank() && newFirmwareVersion != firmwareVersion) {
                firmwareVersion = newFirmwareVersion
                changed = true
            }
            if (resolved.modelName != modelName) {
                modelName = resolved.modelName
                changed = true
            }
            if (resolved.features != features) {
                features = resolved.features
                changed = true
            }
            if (features.needsInitExt && !initExtSent) {
                initExtRequested = true
            }
            return changed
        }

        private fun decodeSetting(payload: ByteArray): Boolean {
            if (payload.size < 2) return false

            val settingId = payload[0].toInt() and 0xFF
            val value = payload[1].toInt() and 0xFF

            return when (settingId) {
                SETTING_ANC_MODE -> decodeAncMode(value)?.let { updateValue(it, ancMode) { ancMode = it } } ?: false
                SETTING_CONVERSATIONAL_AWARENESS -> decodeAppleBool(value)?.let {
                    updateValue(it, conversationAwarenessEnabled) { conversationAwarenessEnabled = it }
                } ?: false
                SETTING_PERSONALIZED_VOLUME -> decodeAppleBool(value)?.let {
                    updateValue(it, personalizedVolumeEnabled) { personalizedVolumeEnabled = it }
                } ?: false
                SETTING_ADAPTIVE_AUDIO_NOISE -> updateValue(value, adaptiveAudioNoiseLevel) { adaptiveAudioNoiseLevel = it }
                SETTING_MICROPHONE_MODE -> decodeMicrophoneMode(value)?.let {
                    updateValue(it, microphoneMode) { microphoneMode = it }
                } ?: false
                SETTING_NC_ONE_AIRPOD -> decodeAppleBool(value)?.let {
                    updateValue(it, ncWithOneAirPodEnabled) { ncWithOneAirPodEnabled = it }
                } ?: false
                SETTING_EAR_DETECTION_ENABLED -> decodeAppleBool(value)?.let {
                    updateValue(it, earDetectionEnabled) { earDetectionEnabled = it }
                } ?: false
                SETTING_VOLUME_SWIPE -> decodeAppleBool(value)?.let {
                    updateValue(it, volumeSwipeEnabled) { volumeSwipeEnabled = it }
                } ?: false
                SETTING_VOLUME_SWIPE_LENGTH -> decodeVolumeSwipeLength(value)?.let {
                    updateValue(it, volumeSwipeLength) { volumeSwipeLength = it }
                } ?: false
                SETTING_TONE_VOLUME -> updateValue(value, toneVolume) { toneVolume = it }
                SETTING_IN_CASE_TONE -> decodeAppleBool(value)?.let {
                    updateValue(it, inCaseToneEnabled) { inCaseToneEnabled = it }
                } ?: false
                SETTING_SLEEP_DETECTION -> decodeAppleBool(value)?.let {
                    updateValue(it, sleepDetectionEnabled) { sleepDetectionEnabled = it }
                } ?: false
                SETTING_PRESS_SPEED -> decodePressSpeed(value)?.let {
                    updateValue(it, pressSpeed) { pressSpeed = it }
                } ?: false
                SETTING_PRESS_HOLD_DURATION -> decodePressHoldDuration(value)?.let {
                    updateValue(it, pressHoldDuration) { pressHoldDuration = it }
                } ?: false
                SETTING_ALLOW_OFF_OPTION -> decodeAppleBool(value)?.let {
                    updateValue(it, allowOffOptionEnabled) { allowOffOptionEnabled = it }
                } ?: false
                else -> false
            }
        }

        private fun decodeConversationAwarenessState(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            val speaking = (payload[0].toInt() and 0xFF) == 0x01
            return updateValue(speaking, conversationAwarenessSpeaking) { conversationAwarenessSpeaking = it }
        }

        private fun decodeEqData(payload: ByteArray): Boolean {
            if (payload.size < 134) return false
            val bands = ArrayList<Float>(8)
            var offset = 6
            repeat(8) {
                if (offset + 4 > payload.size) return@repeat
                val bits = (payload[offset].toInt() and 0xFF) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                    ((payload[offset + 3].toInt() and 0xFF) shl 24)
                bands += Float.fromBits(bits)
                offset += 4
            }
            if (bands.size != 8) return false
            val previous = eqBands
            if (previous != null && previous.size == bands.size && previous.zip(bands).all { (a, b) -> a == b }) {
                return false
            }
            eqBands = bands
            return true
        }

        private fun recomputeEarState(): Boolean {
            val primary = primaryPlacement ?: return false
            val secondary = secondaryPlacement ?: return false
            val resolvedPrimarySide = primaryPodSide ?: PodSide.LEFT
            val newLeft = if (resolvedPrimarySide == PodSide.LEFT) primary == PodPlacement.IN_EAR else secondary == PodPlacement.IN_EAR
            val newRight = if (resolvedPrimarySide == PodSide.LEFT) secondary == PodPlacement.IN_EAR else primary == PodPlacement.IN_EAR
            val changed = newLeft != leftInEar || newRight != rightInEar
            leftInEar = newLeft
            rightInEar = newRight
            return changed
        }

        private fun decodePlacement(raw: Int): PodPlacement = when (raw) {
            0x00 -> PodPlacement.IN_EAR
            0x01 -> PodPlacement.NOT_IN_EAR
            0x02 -> PodPlacement.IN_CASE
            else -> PodPlacement.DISCONNECTED
        }

        private fun parseAsciiStrings(payload: ByteArray): List<String> {
            val strings = mutableListOf<String>()
            var index = 0
            while (index < payload.size) {
                val value = payload[index].toInt() and 0xFF
                if (value in 0x20..0x7E) {
                    val start = index
                    while (index < payload.size && payload[index] != 0.toByte()) {
                        index++
                    }
                    strings += String(payload, start, index - start, Charsets.US_ASCII)
                }
                index++
            }
            return strings
        }

        private fun encodeAncMode(mode: AncMode): Int = when (mode) {
            AncMode.OFF -> 0x01
            AncMode.ON -> 0x02
            AncMode.TRANSPARENCY -> 0x03
            AncMode.ADAPTIVE -> 0x04
        }

        private fun decodeAncMode(value: Int): AncMode? = when (value) {
            0x01 -> AncMode.OFF
            0x02 -> AncMode.ON
            0x03 -> AncMode.TRANSPARENCY
            0x04 -> AncMode.ADAPTIVE
            else -> null
        }

        private fun encodeMicrophoneMode(mode: MicrophoneMode): Int = when (mode) {
            MicrophoneMode.AUTO -> 0x00
            MicrophoneMode.ALWAYS_RIGHT -> 0x01
            MicrophoneMode.ALWAYS_LEFT -> 0x02
        }

        private fun decodeMicrophoneMode(value: Int): MicrophoneMode? = when (value) {
            0x00 -> MicrophoneMode.AUTO
            0x01 -> MicrophoneMode.ALWAYS_RIGHT
            0x02 -> MicrophoneMode.ALWAYS_LEFT
            else -> null
        }

        private fun encodePressSpeed(value: PressSpeed): Int = when (value) {
            PressSpeed.DEFAULT -> 0x00
            PressSpeed.SLOWER -> 0x01
            PressSpeed.SLOWEST -> 0x02
        }

        private fun decodePressSpeed(value: Int): PressSpeed? = when (value) {
            0x00 -> PressSpeed.DEFAULT
            0x01 -> PressSpeed.SLOWER
            0x02 -> PressSpeed.SLOWEST
            else -> null
        }

        private fun encodePressHoldDuration(value: PressHoldDuration): Int = when (value) {
            PressHoldDuration.DEFAULT -> 0x00
            PressHoldDuration.SHORTER -> 0x01
            PressHoldDuration.SHORTEST -> 0x02
        }

        private fun decodePressHoldDuration(value: Int): PressHoldDuration? = when (value) {
            0x00 -> PressHoldDuration.DEFAULT
            0x01 -> PressHoldDuration.SHORTER
            0x02 -> PressHoldDuration.SHORTEST
            else -> null
        }

        private fun encodeVolumeSwipeLength(value: VolumeSwipeLength): Int = when (value) {
            VolumeSwipeLength.DEFAULT -> 0x00
            VolumeSwipeLength.LONGER -> 0x01
            VolumeSwipeLength.LONGEST -> 0x02
        }

        private fun decodeVolumeSwipeLength(value: Int): VolumeSwipeLength? = when (value) {
            0x00 -> VolumeSwipeLength.DEFAULT
            0x01 -> VolumeSwipeLength.LONGER
            0x02 -> VolumeSwipeLength.LONGEST
            else -> null
        }

        private fun encodeAppleBool(enabled: Boolean): Int = if (enabled) 0x01 else 0x02

        private fun decodeAppleBool(value: Int): Boolean? = when (value) {
            0x01 -> true
            0x02 -> false
            else -> null
        }

        private fun buildSettingsMessage(settingId: Int, value: Int): ByteArray = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x09, 0x00,
            settingId.toByte(), value.toByte(),
            0x00, 0x00, 0x00,
        )

        private fun <T> updateValue(value: T, current: T?, setter: (T) -> Unit): Boolean {
            if (current == value) return false
            setter(value)
            return true
        }
    }

    private enum class PodSide { LEFT, RIGHT }
    private enum class PodPlacement { IN_EAR, NOT_IN_EAR, IN_CASE, DISCONNECTED }

    private data class AapMessage(
        val raw: ByteArray,
        val commandType: Int,
        val payload: ByteArray,
    ) {
        companion object {
            fun parse(raw: ByteArray): AapMessage? {
                if (raw.size < 6) return null
                val commandType = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
                val payload = if (raw.size > 6) raw.copyOfRange(6, raw.size) else ByteArray(0)
                return AapMessage(raw.copyOf(), commandType, payload)
            }
        }
    }

    private class AapFramer {
        private val buffer = mutableListOf<Byte>()

        fun consume(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<AapMessage> {
            for (index in offset until offset + length) {
                buffer.add(bytes[index])
            }

            val messages = mutableListOf<AapMessage>()
            while (buffer.size >= 4) {
                val payloadLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
                val totalLength = 4 + payloadLength
                if (buffer.size < totalLength) break

                val messageBytes = ByteArray(totalLength)
                for (index in 0 until totalLength) {
                    messageBytes[index] = buffer[index]
                }
                buffer.subList(0, totalLength).clear()
                AapMessage.parse(messageBytes)?.let { messages += it }
            }
            return messages
        }

        fun reset() {
            buffer.clear()
        }
    }

    companion object {
        private const val TAG = "AirPodsNative9"
        private const val AAP_PSM = 0x1001

        private const val CMD_SETTINGS = 0x0009
        private const val CMD_BATTERY = 0x0004
        private const val CMD_EAR_DETECTION = 0x0006
        private const val CMD_PRIMARY_POD = 0x0008
        private const val CMD_DEVICE_INFO = 0x001D
        private const val CMD_CONVERSATION_AWARENESS_STATE = 0x004B
        private const val CMD_EQ_DATA = 0x0053

        private const val SETTING_ANC_MODE = 0x0D
        private const val SETTING_PRESS_SPEED = 0x17
        private const val SETTING_PRESS_HOLD_DURATION = 0x18
        private const val SETTING_NC_ONE_AIRPOD = 0x1B
        private const val SETTING_TONE_VOLUME = 0x1F
        private const val SETTING_VOLUME_SWIPE_LENGTH = 0x23
        private const val SETTING_VOLUME_SWIPE = 0x25
        private const val SETTING_PERSONALIZED_VOLUME = 0x26
        private const val SETTING_CONVERSATIONAL_AWARENESS = 0x28
        private const val SETTING_ADAPTIVE_AUDIO_NOISE = 0x2E
        private const val SETTING_IN_CASE_TONE = 0x31
        private const val SETTING_ALLOW_OFF_OPTION = 0x34
        private const val SETTING_SLEEP_DETECTION = 0x35
        private const val SETTING_MICROPHONE_MODE = 0x01
        private const val SETTING_EAR_DETECTION_ENABLED = 0x0A

        private const val CONNECTED_AUDIO_CACHE_MS = 4_000L
        private const val PROFILE_QUERY_TIMEOUT_MS = 800L
        private const val FIRST_PACKET_TIMEOUT_MS = 8_000L
        private const val INITIAL_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_STEPS = 4

        private val HANDSHAKE_PACKET = byteArrayOf(
            0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        private val NOTIFICATION_PACKETS = listOf(
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xEF.toByte(), 0xFF.toByte()),
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )

        private val INIT_EXT_PACKET = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0x0E, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}
