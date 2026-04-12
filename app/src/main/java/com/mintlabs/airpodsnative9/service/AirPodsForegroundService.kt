package com.mintlabs.airpodsnative9.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mintlabs.airpodsnative9.R
import com.mintlabs.airpodsnative9.aap.AapClient
import com.mintlabs.airpodsnative9.aap.AapCommand
import com.mintlabs.airpodsnative9.aap.AncMode
import com.mintlabs.airpodsnative9.aap.DeviceProfileOverride
import com.mintlabs.airpodsnative9.aap.MicrophoneMode
import com.mintlabs.airpodsnative9.aap.PressHoldDuration
import com.mintlabs.airpodsnative9.aap.PressSpeed
import com.mintlabs.airpodsnative9.aap.VolumeSwipeLength
import com.mintlabs.airpodsnative9.model.AirPodsStatus
import com.mintlabs.airpodsnative9.prefs.AppSettings
import com.mintlabs.airpodsnative9.protocol.AirPodsPacketParser
import com.mintlabs.airpodsnative9.root.RootBluetoothPatcher
import java.util.Locale
import kotlin.math.abs

class AirPodsForegroundService : Service() {
    private val tag = "Airbridge"
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var legacyScanCallback: BluetoothAdapter.LeScanCallback? = null
    private val recentStatuses = linkedMapOf<String, AirPodsStatus>()
    private var currentStatus: AirPodsStatus? = null
    private var pausedByService = false
    private var pendingEarAction: Runnable? = null
    private var totalScanResults = 0
    private var applePacketsSeen = 0
    private var parsedPackets = 0
    private var acceptedPackets = 0
    private var lastCallbackAtMs = 0L
    private var scanModeLabel = "idle"
    private var lastApplePreview = ""
    private var lastRejectReason = ""

    private var aapClient: AapClient? = null
    private var aapStatus: AirPodsStatus? = null
    private var aapSnapshot: AapClient.Snapshot? = null
    private var aapStateLabel = "stopped"
    private var aapDeviceLabel = ""
    private var aapLastError = ""

    private lateinit var rootBluetoothPatcher: RootBluetoothPatcher

    private val staleRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            cleanupStale(now)
            val best = selectPreferredStatus(now)
            if (best != null) {
                publishStatus(best)
            } else if (currentStatus != null && now - currentStatus!!.timestampMs > STALE_TIMEOUT_MS) {
                currentStatus = null
                pausedByService = false
                updateNotification(null)
                broadcastStatus(null)
            } else {
                broadcastStatus(currentStatus)
            }
            handler.postDelayed(this, HOUSEKEEPING_INTERVAL_MS)
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            if (state == BluetoothAdapter.STATE_ON) {
                startScan()
                startAap()
            } else if (state == BluetoothAdapter.STATE_OFF) {
                stopScan()
                stopAap(clearStatus = true)
                currentStatus = null
                updateNotification(null)
                broadcastStatus(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        rootBluetoothPatcher = RootBluetoothPatcher(applicationContext)
        createNotificationChannel()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startForeground(NOTIFICATION_ID, buildNotification(null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScan()
                stopAap(clearStatus = true)
                handler.removeCallbacksAndMessages(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND_COMMAND -> {
                startScan()
                startAap()
                handleControlIntent(intent)
                handler.removeCallbacks(staleRunnable)
                handler.post(staleRunnable)
            }
            ACTION_START, null -> {
                startScan()
                startAap()
                handler.removeCallbacks(staleRunnable)
                handler.post(staleRunnable)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        stopAap(clearStatus = true)
        handler.removeCallbacksAndMessages(null)
        pendingEarAction = null
        unregisterReceiver(btStateReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter() ?: return
        if (!adapter.isEnabled) return

        stopScan()
        Log.d(tag, "Starting BLE scan. locationEnabled=${isLocationEnabled()}")

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            scanModeLabel = "legacy"
            legacyScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
                if (device == null || scanRecord == null) return@LeScanCallback
                processAdvertisement(
                    address = device.address ?: return@LeScanCallback,
                    rssi = rssi,
                    manufacturer = extractAppleManufacturerData(scanRecord)
                )
            }
            adapter.startLeScan(legacyScanCallback)
            return
        }

        scanModeLabel = "scanner"
        scanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processAdvertisement(
                    address = result.device?.address ?: return,
                    rssi = result.rssi,
                    manufacturer = result.scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)
                )
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                for (result in results) {
                    processAdvertisement(
                        address = result.device?.address ?: continue,
                        rssi = result.rssi,
                        manufacturer = result.scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(tag, "BLE scan failed: $errorCode")
            }
        }

        scanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            val adapter = bluetoothAdapter()
            if (adapter != null && legacyScanCallback != null) {
                adapter.stopLeScan(legacyScanCallback)
            }
            if (scanner != null && scanCallback != null) {
                scanner?.stopScan(scanCallback)
            }
        } catch (_: Throwable) {
        } finally {
            legacyScanCallback = null
            scanCallback = null
            scanner = null
            scanModeLabel = "stopped"
        }
    }

    private fun processAdvertisement(address: String, rssi: Int, manufacturer: ByteArray?) {
        totalScanResults++
        lastCallbackAtMs = System.currentTimeMillis()
        if (manufacturer == null) return

        applePacketsSeen++
        lastApplePreview = manufacturer.toPreviewString()
        val parsed = AirPodsPacketParser.parseFromManufacturerData(
            manufacturerId = APPLE_MANUFACTURER_ID,
            data = manufacturer,
            address = address,
            rssi = rssi
        )
        if (parsed == null) {
            lastRejectReason = explainParserReject(manufacturer)
            return
        }
        parsedPackets++

        if (!shouldAccept(parsed)) {
            lastRejectReason = "filtered state=${parsed.connectionState} inEar=${parsed.leftInEar}/${parsed.rightInEar} lid=${parsed.lidOpen} rssi=${parsed.rssi}"
            return
        }
        acceptedPackets++
        lastRejectReason = ""
        recentStatuses[address] = parsed
        selectPreferredStatus(System.currentTimeMillis())?.let { publishStatus(it) }
    }

    private fun extractAppleManufacturerData(scanRecord: ByteArray): ByteArray? {
        var index = 0
        while (index < scanRecord.size) {
            val length = scanRecord[index].toInt() and 0xFF
            if (length == 0) break
            val next = index + length + 1
            if (next > scanRecord.size || index + 1 >= scanRecord.size) break

            val type = scanRecord[index + 1].toInt() and 0xFF
            if (type == 0xFF && length >= 3) {
                val manufacturerId =
                    (scanRecord[index + 2].toInt() and 0xFF) or ((scanRecord[index + 3].toInt() and 0xFF) shl 8)
                if (manufacturerId == APPLE_MANUFACTURER_ID) {
                    return scanRecord.copyOfRange(index + 4, next)
                }
            }
            index = next
        }
        return null
    }

    private fun explainParserReject(manufacturer: ByteArray): String {
        if (manufacturer.size < 27) {
            return "parser len=${manufacturer.size} head=${manufacturer.toPreviewString()}"
        }
        if (manufacturer.size >= 2 && (manufacturer[0].toInt() != 0x07 || manufacturer[1].toInt() != 0x19)) {
            return "parser head=${manufacturer.toPreviewString()}"
        }
        return "parser unknown ${manufacturer.toPreviewString()}"
    }

    private fun ByteArray.toPreviewString(): String {
        return joinToString(" ") { "%02X".format(it) }.let { preview ->
            if (preview.length > 72) preview.take(72) + "..." else preview
        }
    }

    private fun shouldAccept(status: AirPodsStatus): Boolean {
        if (status.rssi < MIN_RSSI) return false

        if (!AppSettings.isRequireConnectedEnabled(this)) return true
        if (status.connectionStateCode != 0x00) return true
        if (status.lidOpen || status.anyInEar) return true

        val previous = currentStatus
        if (previous != null && previous.model == status.model) {
            return abs(previous.rssi - status.rssi) <= 20
        }
        return false
    }

    private fun score(status: AirPodsStatus): Int {
        var score = status.rssi
        if (status.connectionStateCode != 0x00) score += 35
        if (status.anyInEar) score += 20
        if (status.lidOpen) score += 10
        if (currentStatus != null && currentStatus!!.model == status.model) score += 6
        return score
    }

    private fun cleanupStale(now: Long) {
        val iterator = recentStatuses.entries.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (now - next.value.timestampMs > STALE_TIMEOUT_MS) {
                iterator.remove()
            }
        }
    }

    private fun selectBestStatus(now: Long): AirPodsStatus? {
        cleanupStale(now)
        return recentStatuses.values.maxByOrNull { score(it) }
    }

    private fun selectPreferredStatus(now: Long): AirPodsStatus? {
        val bleStatus = selectBestStatus(now)
        val preferredAap = when {
            aapStatus == null -> null
            aapStateLabel == "ready" || aapStateLabel == "handshaking" || aapStateLabel == "connecting" -> aapStatus
            now - aapStatus!!.timestampMs <= 8_000L -> aapStatus
            else -> null
        }

        return when {
            preferredAap != null -> mergeAapWithBle(preferredAap, bleStatus)
            bleStatus != null -> bleStatus
            else -> null
        }
    }

    private fun mergeAapWithBle(aap: AirPodsStatus, ble: AirPodsStatus?): AirPodsStatus {
        if (ble == null) return aap
        return aap.copy(
            model = if (aap.model == "AirPods" && ble.model != "Unknown") ble.model else aap.model,
            leftBattery = aap.leftBattery ?: ble.leftBattery,
            rightBattery = aap.rightBattery ?: ble.rightBattery,
            caseBattery = aap.caseBattery ?: ble.caseBattery,
            leftCharging = aap.leftCharging || ble.leftCharging,
            rightCharging = aap.rightCharging || ble.rightCharging,
            caseCharging = aap.caseCharging || ble.caseCharging,
            lidOpen = ble.lidOpen,
            color = if (ble.color != "Unknown") ble.color else aap.color,
            rssi = if (ble.rssi != 0) ble.rssi else aap.rssi,
        )
    }

    private fun publishStatus(newStatus: AirPodsStatus, forceBroadcast: Boolean = false) {
        val oldStatus = currentStatus
        currentStatus = newStatus

        val meaningfulChange = oldStatus == null || hasMeaningfulChange(oldStatus, newStatus)
        if (meaningfulChange) {
            handleEarTransition(oldStatus, newStatus)
        }
        if (meaningfulChange || forceBroadcast) {
            updateNotification(newStatus)
            broadcastStatus(newStatus)
        }
    }

    private fun hasMeaningfulChange(old: AirPodsStatus, new: AirPodsStatus): Boolean {
        return old.model != new.model ||
            old.connectionStateCode != new.connectionStateCode ||
            old.leftBattery != new.leftBattery ||
            old.rightBattery != new.rightBattery ||
            old.caseBattery != new.caseBattery ||
            old.leftInEar != new.leftInEar ||
            old.rightInEar != new.rightInEar ||
            old.lidOpen != new.lidOpen ||
            abs(old.rssi - new.rssi) >= 8 ||
            old.source != new.source
    }

    private fun handleEarTransition(old: AirPodsStatus?, new: AirPodsStatus) {
        if (old == null) return
        val pauseOnSingle = AppSettings.isPauseOnSingleEnabled(this)
        val oldPauseCondition = if (pauseOnSingle) old.leftInEar != old.rightInEar || old.bothOut else old.bothOut
        val newPauseCondition = if (pauseOnSingle) new.leftInEar != new.rightInEar || new.bothOut else new.bothOut

        if (newPauseCondition && !oldPauseCondition && AppSettings.isAutoPauseEnabled(this)) {
            scheduleEarAction {
                pauseIfNeeded()
            }
            return
        }

        if (!newPauseCondition && oldPauseCondition && AppSettings.isAutoResumeEnabled(this)) {
            scheduleEarAction {
                resumeIfNeeded()
            }
        }
    }

    private fun scheduleEarAction(action: () -> Unit) {
        pendingEarAction?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { action() }
        pendingEarAction = runnable
        handler.postDelayed(runnable, EAR_ACTION_DEBOUNCE_MS)
    }

    private fun pauseIfNeeded() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isMusicActive) {
            sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByService = true
        }
    }

    private fun resumeIfNeeded() {
        if (!pausedByService) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sendMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
        pausedByService = false
    }

    private fun sendMediaKey(audioManager: AudioManager, code: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
    }

    private fun broadcastStatus(status: AirPodsStatus?) {
        val snapshot = aapSnapshot
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_AVAILABLE, status != null)
            putExtra(EXTRA_SCAN_TOTAL, totalScanResults)
            putExtra(EXTRA_APPLE_PACKET_COUNT, applePacketsSeen)
            putExtra(EXTRA_PARSED_PACKET_COUNT, parsedPackets)
            putExtra(EXTRA_ACCEPTED_PACKET_COUNT, acceptedPackets)
            putExtra(EXTRA_LAST_CALLBACK_AGE_MS, if (lastCallbackAtMs == 0L) -1L else System.currentTimeMillis() - lastCallbackAtMs)
            putExtra(EXTRA_SCAN_MODE, scanModeLabel)
            putExtra(EXTRA_LOCATION_ENABLED, isLocationEnabled())
            putExtra(EXTRA_LAST_APPLE_PREVIEW, lastApplePreview)
            putExtra(EXTRA_LAST_REJECT_REASON, lastRejectReason)
            putExtra(EXTRA_AAP_STATE, aapStateLabel)
            putExtra(EXTRA_AAP_DEVICE, aapDeviceLabel)
            putExtra(EXTRA_AAP_ERROR, aapLastError)

            snapshot?.let { aap ->
                putExtra(EXTRA_AAP_READY, aapStateLabel == "ready")
                putExtra(EXTRA_AAP_MODEL_NUMBER, aap.modelNumber)
                putExtra(EXTRA_AAP_FIRMWARE, aap.firmwareVersion)
                putExtra(EXTRA_AAP_SUPPORTED_ANC_MODES, aap.supportedAncModes.map { it.name }.toTypedArray())

                putExtra(EXTRA_FEATURE_ANC, aap.features.hasAncControl)
                putExtra(EXTRA_FEATURE_CONVERSATION_AWARENESS, aap.features.hasConversationAwareness)
                putExtra(EXTRA_FEATURE_PERSONALIZED_VOLUME, aap.features.hasPersonalizedVolume)
                putExtra(EXTRA_FEATURE_ADAPTIVE_AUDIO_NOISE, aap.features.hasAdaptiveAudioNoise)
                putExtra(EXTRA_FEATURE_MICROPHONE_MODE, aap.features.hasMicrophoneMode)
                putExtra(EXTRA_FEATURE_NC_ONE_AIRPOD, aap.features.hasNcOneAirpod)
                putExtra(EXTRA_FEATURE_EAR_DETECTION, aap.features.hasEarDetectionToggle)
                putExtra(EXTRA_FEATURE_VOLUME_SWIPE, aap.features.hasVolumeSwipe)
                putExtra(EXTRA_FEATURE_VOLUME_SWIPE_LENGTH, aap.features.hasVolumeSwipeLength)
                putExtra(EXTRA_FEATURE_TONE_VOLUME, aap.features.hasToneVolume)
                putExtra(EXTRA_FEATURE_IN_CASE_TONE, aap.features.hasInCaseTone)
                putExtra(EXTRA_FEATURE_SLEEP_DETECTION, aap.features.hasSleepDetection)
                putExtra(EXTRA_FEATURE_PRESS_SPEED, aap.features.hasPressSpeed)
                putExtra(EXTRA_FEATURE_PRESS_HOLD_DURATION, aap.features.hasPressHoldDuration)
                putExtra(EXTRA_FEATURE_ALLOW_OFF, aap.features.hasAllowOffOption)
                putExtra(EXTRA_FEATURE_EQ, aap.features.hasEqTelemetry)

                aap.ancMode?.let { putExtra(EXTRA_AAP_ANC_MODE, it.name) }
                aap.conversationAwarenessEnabled?.let { putExtra(EXTRA_AAP_CONVERSATION_AWARENESS, it) }
                aap.conversationAwarenessSpeaking?.let { putExtra(EXTRA_AAP_CONVERSATION_SPEAKING, it) }
                aap.personalizedVolumeEnabled?.let { putExtra(EXTRA_AAP_PERSONALIZED_VOLUME, it) }
                aap.adaptiveAudioNoiseLevel?.let { putExtra(EXTRA_AAP_ADAPTIVE_AUDIO_NOISE, it) }
                aap.microphoneMode?.let { putExtra(EXTRA_AAP_MICROPHONE_MODE, it.name) }
                aap.ncWithOneAirPodEnabled?.let { putExtra(EXTRA_AAP_NC_ONE_AIRPOD, it) }
                aap.earDetectionEnabled?.let { putExtra(EXTRA_AAP_EAR_DETECTION, it) }
                aap.volumeSwipeEnabled?.let { putExtra(EXTRA_AAP_VOLUME_SWIPE, it) }
                aap.volumeSwipeLength?.let { putExtra(EXTRA_AAP_VOLUME_SWIPE_LENGTH, it.name) }
                aap.toneVolume?.let { putExtra(EXTRA_AAP_TONE_VOLUME, it) }
                aap.inCaseToneEnabled?.let { putExtra(EXTRA_AAP_IN_CASE_TONE, it) }
                aap.sleepDetectionEnabled?.let { putExtra(EXTRA_AAP_SLEEP_DETECTION, it) }
                aap.pressSpeed?.let { putExtra(EXTRA_AAP_PRESS_SPEED, it.name) }
                aap.pressHoldDuration?.let { putExtra(EXTRA_AAP_PRESS_HOLD_DURATION, it.name) }
                aap.allowOffOptionEnabled?.let { putExtra(EXTRA_AAP_ALLOW_OFF, it) }
                aap.eqBands?.let { bands -> putExtra(EXTRA_AAP_EQ_BANDS, bands.toFloatArray()) }
            }

            if (status != null) {
                putExtra(EXTRA_MODEL, status.model)
                putExtra(EXTRA_CONNECTION, status.connectionState)
                putExtra(EXTRA_LEFT_BATTERY, status.leftBattery ?: -1)
                putExtra(EXTRA_RIGHT_BATTERY, status.rightBattery ?: -1)
                putExtra(EXTRA_CASE_BATTERY, status.caseBattery ?: -1)
                putExtra(EXTRA_LEFT_IN_EAR, status.leftInEar)
                putExtra(EXTRA_RIGHT_IN_EAR, status.rightInEar)
                putExtra(EXTRA_LID_OPEN, status.lidOpen)
                putExtra(EXTRA_COLOR, status.color)
                putExtra(EXTRA_RSSI, status.rssi)
                putExtra(EXTRA_TIMESTAMP, status.timestampMs)
                putExtra(EXTRA_SOURCE, status.source)
            }
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(status: AirPodsStatus?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: AirPodsStatus?): Notification {
        val ancSummary = aapSnapshot?.ancMode?.name?.lowercase(Locale.US)?.replaceFirstChar { it.titlecase(Locale.US) }
        val content = if (status == null) {
            "${getString(R.string.status_unknown)} • BLE $scanModeLabel • AAP $aapStateLabel"
        } else {
            buildString {
                append(status.model)
                append(" • ")
                append(status.connectionState)
                append(" • ")
                append(status.batterySummary())
                if (!ancSummary.isNullOrBlank()) {
                    append(" • ")
                    append(ancSummary)
                }
                append(" • ")
                append(status.source)
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airpods)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun bluetoothAdapter() =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun isScanning(): Boolean = scanCallback != null || legacyScanCallback != null

    private fun shouldKeepBleScanActive(): Boolean {
        return when (aapStateLabel) {
            "connecting", "handshaking", "ready" -> false
            else -> true
        }
    }

    private fun refreshBleScanState() {
        when {
            shouldKeepBleScanActive() && !isScanning() -> startScan()
            !shouldKeepBleScanActive() && isScanning() -> stopScan()
        }
    }

    private fun startAap() {
        if (aapClient != null) return
        aapClient = AapClient(
            context = applicationContext,
            preferredAddress = AppSettings.getLastClassicAddress(this),
            profileOverride = AppSettings.getDeviceProfileOverride(this),
            callback = object : AapClient.Callback {
                override fun onDiagnostics(state: String, deviceLabel: String?, error: String?) {
                    handler.post {
                        aapStateLabel = state
                        aapDeviceLabel = deviceLabel.orEmpty()
                        aapLastError = error.orEmpty()
                        refreshBleScanState()
                        if (state == "searching" && aapStatus != null && System.currentTimeMillis() - aapStatus!!.timestampMs > STALE_TIMEOUT_MS) {
                            aapStatus = null
                            aapSnapshot = null
                        }
                        updateNotification(currentStatus)
                        broadcastStatus(currentStatus)
                    }
                }

                override fun onSnapshot(snapshot: AapClient.Snapshot) {
                    handler.post {
                        AppSettings.setLastClassicAddress(this@AirPodsForegroundService, snapshot.address)
                        aapSnapshot = snapshot
                        aapStatus = buildAapStatus(snapshot)
                        selectPreferredStatus(System.currentTimeMillis())?.let {
                            publishStatus(it, forceBroadcast = true)
                        } ?: broadcastStatus(currentStatus)
                    }
                }
            },
            prepareSocket = {
                rootBluetoothPatcher.ensurePatched()
            }
        ).also { it.start() }
    }

    private fun stopAap(clearStatus: Boolean) {
        aapClient?.stop()
        aapClient = null
        rootBluetoothPatcher.restoreIfNeeded()
        aapStateLabel = "stopped"
        aapDeviceLabel = ""
        aapLastError = ""
        refreshBleScanState()
        if (clearStatus) {
            aapStatus = null
            aapSnapshot = null
        }
    }

    private fun buildAapStatus(snapshot: AapClient.Snapshot): AirPodsStatus {
        val ble = selectBestStatus(System.currentTimeMillis())
        val previous = aapStatus
        val model = snapshot.modelName
            ?: previous?.model
            ?: ble?.model
            ?: snapshot.deviceName
            ?: "AirPods"

        return AirPodsStatus(
            address = snapshot.address,
            timestampMs = snapshot.lastMessageAtMs,
            model = model,
            connectionState = "Connected",
            connectionStateCode = 0xFF,
            leftBattery = snapshot.leftBattery ?: previous?.leftBattery ?: ble?.leftBattery,
            rightBattery = snapshot.rightBattery ?: previous?.rightBattery ?: ble?.rightBattery,
            caseBattery = snapshot.caseBattery ?: previous?.caseBattery ?: ble?.caseBattery,
            leftInEar = snapshot.leftInEar,
            rightInEar = snapshot.rightInEar,
            leftCharging = previous?.leftCharging ?: ble?.leftCharging ?: false,
            rightCharging = previous?.rightCharging ?: ble?.rightCharging ?: false,
            caseCharging = previous?.caseCharging ?: ble?.caseCharging ?: false,
            lidOpen = ble?.lidOpen ?: previous?.lidOpen ?: false,
            color = ble?.color ?: previous?.color ?: "Unknown",
            rssi = ble?.rssi ?: previous?.rssi ?: 0,
            source = "AAP"
        )
    }

    private fun handleControlIntent(intent: Intent) {
        val command = when (intent.getStringExtra(EXTRA_CONTROL_TYPE)) {
            CONTROL_ANC_MODE -> {
                when (intent.getStringExtra(EXTRA_CONTROL_VALUE)) {
                    AncMode.OFF.name -> AapCommand.SetAncMode(AncMode.OFF)
                    AncMode.ON.name -> AapCommand.SetAncMode(AncMode.ON)
                    AncMode.TRANSPARENCY.name -> AapCommand.SetAncMode(AncMode.TRANSPARENCY)
                    AncMode.ADAPTIVE.name -> AapCommand.SetAncMode(AncMode.ADAPTIVE)
                    else -> null
                }
            }
            CONTROL_CONVERSATION_AWARENESS -> AapCommand.SetConversationalAwareness(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_PERSONALIZED_VOLUME -> AapCommand.SetPersonalizedVolume(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_ADAPTIVE_AUDIO_NOISE -> AapCommand.SetAdaptiveAudioNoise(intent.getIntExtra(EXTRA_CONTROL_INT, 50))
            CONTROL_MICROPHONE_MODE -> {
                when (intent.getStringExtra(EXTRA_CONTROL_VALUE)) {
                    MicrophoneMode.AUTO.name -> AapCommand.SetMicrophoneMode(MicrophoneMode.AUTO)
                    MicrophoneMode.ALWAYS_RIGHT.name -> AapCommand.SetMicrophoneMode(MicrophoneMode.ALWAYS_RIGHT)
                    MicrophoneMode.ALWAYS_LEFT.name -> AapCommand.SetMicrophoneMode(MicrophoneMode.ALWAYS_LEFT)
                    else -> null
                }
            }
            CONTROL_NC_ONE_AIRPOD -> AapCommand.SetNcWithOneAirPod(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_EAR_DETECTION -> AapCommand.SetEarDetectionEnabled(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_VOLUME_SWIPE -> AapCommand.SetVolumeSwipe(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_VOLUME_SWIPE_LENGTH -> {
                when (intent.getStringExtra(EXTRA_CONTROL_VALUE)) {
                    VolumeSwipeLength.DEFAULT.name -> AapCommand.SetVolumeSwipeLength(VolumeSwipeLength.DEFAULT)
                    VolumeSwipeLength.LONGER.name -> AapCommand.SetVolumeSwipeLength(VolumeSwipeLength.LONGER)
                    VolumeSwipeLength.LONGEST.name -> AapCommand.SetVolumeSwipeLength(VolumeSwipeLength.LONGEST)
                    else -> null
                }
            }
            CONTROL_TONE_VOLUME -> AapCommand.SetToneVolume(intent.getIntExtra(EXTRA_CONTROL_INT, 50))
            CONTROL_IN_CASE_TONE -> AapCommand.SetInCaseTone(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_SLEEP_DETECTION -> AapCommand.SetSleepDetection(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            CONTROL_PRESS_SPEED -> {
                when (intent.getStringExtra(EXTRA_CONTROL_VALUE)) {
                    PressSpeed.DEFAULT.name -> AapCommand.SetPressSpeed(PressSpeed.DEFAULT)
                    PressSpeed.SLOWER.name -> AapCommand.SetPressSpeed(PressSpeed.SLOWER)
                    PressSpeed.SLOWEST.name -> AapCommand.SetPressSpeed(PressSpeed.SLOWEST)
                    else -> null
                }
            }
            CONTROL_PRESS_HOLD_DURATION -> {
                when (intent.getStringExtra(EXTRA_CONTROL_VALUE)) {
                    PressHoldDuration.DEFAULT.name -> AapCommand.SetPressHoldDuration(PressHoldDuration.DEFAULT)
                    PressHoldDuration.SHORTER.name -> AapCommand.SetPressHoldDuration(PressHoldDuration.SHORTER)
                    PressHoldDuration.SHORTEST.name -> AapCommand.SetPressHoldDuration(PressHoldDuration.SHORTEST)
                    else -> null
                }
            }
            CONTROL_ALLOW_OFF_OPTION -> AapCommand.SetAllowOffOption(intent.getBooleanExtra(EXTRA_CONTROL_BOOLEAN, false))
            else -> null
        } ?: return

        if (aapClient?.send(command) != true) {
            aapLastError = getString(R.string.controls_waiting_for_connection)
            broadcastStatus(currentStatus)
            updateNotification(currentStatus)
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            } else {
                Settings.Secure.getString(contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED)?.isNotEmpty() == true
            }
        } catch (_: Throwable) {
            Settings.Secure.getString(contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED)?.isNotEmpty() == true
        }
    }

    companion object {
        private const val CHANNEL_ID = "airbridge_status"
        private const val NOTIFICATION_ID = 1904
        private const val APPLE_MANUFACTURER_ID = 76
        private const val MIN_RSSI = -92
        private const val STALE_TIMEOUT_MS = 12_000L
        private const val HOUSEKEEPING_INTERVAL_MS = 3_000L
        private const val EAR_ACTION_DEBOUNCE_MS = 600L

        const val ACTION_START = "com.mintlabs.airbridge.START"
        const val ACTION_STOP = "com.mintlabs.airbridge.STOP"
        const val ACTION_STATUS_UPDATE = "com.mintlabs.airbridge.STATUS_UPDATE"
        const val ACTION_SEND_COMMAND = "com.mintlabs.airbridge.SEND_COMMAND"

        const val EXTRA_AVAILABLE = "available"
        const val EXTRA_MODEL = "model"
        const val EXTRA_CONNECTION = "connection"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LEFT_BATTERY = "left_battery"
        const val EXTRA_RIGHT_BATTERY = "right_battery"
        const val EXTRA_CASE_BATTERY = "case_battery"
        const val EXTRA_LEFT_IN_EAR = "left_in_ear"
        const val EXTRA_RIGHT_IN_EAR = "right_in_ear"
        const val EXTRA_LID_OPEN = "lid_open"
        const val EXTRA_COLOR = "color"
        const val EXTRA_RSSI = "rssi"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_SCAN_TOTAL = "scan_total"
        const val EXTRA_APPLE_PACKET_COUNT = "apple_packet_count"
        const val EXTRA_PARSED_PACKET_COUNT = "parsed_packet_count"
        const val EXTRA_ACCEPTED_PACKET_COUNT = "accepted_packet_count"
        const val EXTRA_LAST_CALLBACK_AGE_MS = "last_callback_age_ms"
        const val EXTRA_SCAN_MODE = "scan_mode"
        const val EXTRA_LOCATION_ENABLED = "location_enabled"
        const val EXTRA_LAST_APPLE_PREVIEW = "last_apple_preview"
        const val EXTRA_LAST_REJECT_REASON = "last_reject_reason"
        const val EXTRA_AAP_STATE = "aap_state"
        const val EXTRA_AAP_DEVICE = "aap_device"
        const val EXTRA_AAP_ERROR = "aap_error"
        const val EXTRA_AAP_READY = "aap_ready"
        const val EXTRA_AAP_MODEL_NUMBER = "aap_model_number"
        const val EXTRA_AAP_FIRMWARE = "aap_firmware"
        const val EXTRA_AAP_SUPPORTED_ANC_MODES = "aap_supported_anc_modes"
        const val EXTRA_AAP_ANC_MODE = "aap_anc_mode"
        const val EXTRA_AAP_CONVERSATION_AWARENESS = "aap_conversation_awareness"
        const val EXTRA_AAP_CONVERSATION_SPEAKING = "aap_conversation_speaking"
        const val EXTRA_AAP_PERSONALIZED_VOLUME = "aap_personalized_volume"
        const val EXTRA_AAP_ADAPTIVE_AUDIO_NOISE = "aap_adaptive_audio_noise"
        const val EXTRA_AAP_MICROPHONE_MODE = "aap_microphone_mode"
        const val EXTRA_AAP_NC_ONE_AIRPOD = "aap_nc_one_airpod"
        const val EXTRA_AAP_EAR_DETECTION = "aap_ear_detection"
        const val EXTRA_AAP_VOLUME_SWIPE = "aap_volume_swipe"
        const val EXTRA_AAP_VOLUME_SWIPE_LENGTH = "aap_volume_swipe_length"
        const val EXTRA_AAP_TONE_VOLUME = "aap_tone_volume"
        const val EXTRA_AAP_IN_CASE_TONE = "aap_in_case_tone"
        const val EXTRA_AAP_SLEEP_DETECTION = "aap_sleep_detection"
        const val EXTRA_AAP_PRESS_SPEED = "aap_press_speed"
        const val EXTRA_AAP_PRESS_HOLD_DURATION = "aap_press_hold_duration"
        const val EXTRA_AAP_ALLOW_OFF = "aap_allow_off"
        const val EXTRA_AAP_EQ_BANDS = "aap_eq_bands"

        const val EXTRA_FEATURE_ANC = "feature_anc"
        const val EXTRA_FEATURE_CONVERSATION_AWARENESS = "feature_conversation_awareness"
        const val EXTRA_FEATURE_PERSONALIZED_VOLUME = "feature_personalized_volume"
        const val EXTRA_FEATURE_ADAPTIVE_AUDIO_NOISE = "feature_adaptive_audio_noise"
        const val EXTRA_FEATURE_MICROPHONE_MODE = "feature_microphone_mode"
        const val EXTRA_FEATURE_NC_ONE_AIRPOD = "feature_nc_one_airpod"
        const val EXTRA_FEATURE_EAR_DETECTION = "feature_ear_detection"
        const val EXTRA_FEATURE_VOLUME_SWIPE = "feature_volume_swipe"
        const val EXTRA_FEATURE_VOLUME_SWIPE_LENGTH = "feature_volume_swipe_length"
        const val EXTRA_FEATURE_TONE_VOLUME = "feature_tone_volume"
        const val EXTRA_FEATURE_IN_CASE_TONE = "feature_in_case_tone"
        const val EXTRA_FEATURE_SLEEP_DETECTION = "feature_sleep_detection"
        const val EXTRA_FEATURE_PRESS_SPEED = "feature_press_speed"
        const val EXTRA_FEATURE_PRESS_HOLD_DURATION = "feature_press_hold_duration"
        const val EXTRA_FEATURE_ALLOW_OFF = "feature_allow_off"
        const val EXTRA_FEATURE_EQ = "feature_eq"

        const val EXTRA_CONTROL_TYPE = "control_type"
        const val EXTRA_CONTROL_VALUE = "control_value"
        const val EXTRA_CONTROL_BOOLEAN = "control_boolean"
        const val EXTRA_CONTROL_INT = "control_int"

        const val CONTROL_ANC_MODE = "anc_mode"
        const val CONTROL_CONVERSATION_AWARENESS = "conversation_awareness"
        const val CONTROL_PERSONALIZED_VOLUME = "personalized_volume"
        const val CONTROL_ADAPTIVE_AUDIO_NOISE = "adaptive_audio_noise"
        const val CONTROL_MICROPHONE_MODE = "microphone_mode"
        const val CONTROL_NC_ONE_AIRPOD = "nc_one_airpod"
        const val CONTROL_EAR_DETECTION = "ear_detection"
        const val CONTROL_VOLUME_SWIPE = "volume_swipe"
        const val CONTROL_VOLUME_SWIPE_LENGTH = "volume_swipe_length"
        const val CONTROL_TONE_VOLUME = "tone_volume"
        const val CONTROL_IN_CASE_TONE = "in_case_tone"
        const val CONTROL_SLEEP_DETECTION = "sleep_detection"
        const val CONTROL_PRESS_SPEED = "press_speed"
        const val CONTROL_PRESS_HOLD_DURATION = "press_hold_duration"
        const val CONTROL_ALLOW_OFF_OPTION = "allow_off_option"

        fun start(context: Context) {
            val intent = Intent(context, AirPodsForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirPodsForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun sendCommand(
            context: Context,
            type: String,
            value: String? = null,
            booleanValue: Boolean? = null,
            intValue: Int? = null,
        ) {
            val intent = Intent(context, AirPodsForegroundService::class.java).apply {
                action = ACTION_SEND_COMMAND
                putExtra(EXTRA_CONTROL_TYPE, type)
                value?.let { putExtra(EXTRA_CONTROL_VALUE, it) }
                booleanValue?.let { putExtra(EXTRA_CONTROL_BOOLEAN, it) }
                intValue?.let { putExtra(EXTRA_CONTROL_INT, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
