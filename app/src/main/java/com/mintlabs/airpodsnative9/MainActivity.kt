package com.mintlabs.airpodsnative9

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mintlabs.airpodsnative9.aap.AncMode
import com.mintlabs.airpodsnative9.aap.DeviceProfileOverride
import com.mintlabs.airpodsnative9.aap.MicrophoneMode
import com.mintlabs.airpodsnative9.aap.PressHoldDuration
import com.mintlabs.airpodsnative9.aap.PressSpeed
import com.mintlabs.airpodsnative9.aap.VolumeSwipeLength
import com.mintlabs.airpodsnative9.eq.PowerampEqPreset
import com.mintlabs.airpodsnative9.eq.PowerampEqPresetParser
import com.mintlabs.airpodsnative9.prefs.AppSettings
import com.mintlabs.airpodsnative9.service.AirPodsForegroundService
import java.io.IOException
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var tvModel: TextView
    private lateinit var tvAapState: TextView
    private lateinit var tvConnection: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvEar: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvConversationMeta: TextView
    private lateinit var tvAdaptiveAudioNoiseLabel: TextView
    private lateinit var tvToneVolumeLabel: TextView
    private lateinit var tvEqStatus: TextView
    private lateinit var tvEqTelemetry: TextView

    private lateinit var cardNoiseControl: MaterialCardView
    private lateinit var cardListening: MaterialCardView
    private lateinit var cardAdaptiveEq: MaterialCardView

    private lateinit var chipGroupAncMode: ChipGroup
    private lateinit var chipAncOff: View
    private lateinit var chipAncOn: View
    private lateinit var chipAncTransparency: View
    private lateinit var chipAncAdaptive: View

    private lateinit var chipGroupMicMode: ChipGroup
    private lateinit var chipMicAuto: View
    private lateinit var chipMicRight: View
    private lateinit var chipMicLeft: View

    private lateinit var chipGroupVolumeSwipeLength: ChipGroup
    private lateinit var chipSwipeDefault: View
    private lateinit var chipSwipeLonger: View
    private lateinit var chipSwipeLongest: View

    private lateinit var chipGroupPressSpeed: ChipGroup
    private lateinit var chipPressSpeedDefault: View
    private lateinit var chipPressSpeedSlower: View
    private lateinit var chipPressSpeedSlowest: View

    private lateinit var chipGroupPressHoldDuration: ChipGroup
    private lateinit var chipPressHoldDefault: View
    private lateinit var chipPressHoldShorter: View
    private lateinit var chipPressHoldShortest: View

    private lateinit var switchConversationAwareness: SwitchMaterial
    private lateinit var switchPersonalizedVolume: SwitchMaterial
    private lateinit var switchAllowOffOption: SwitchMaterial
    private lateinit var switchNcOneAirpod: SwitchMaterial
    private lateinit var switchEarDetection: SwitchMaterial
    private lateinit var switchVolumeSwipe: SwitchMaterial
    private lateinit var switchInCaseTone: SwitchMaterial
    private lateinit var switchSleepDetection: SwitchMaterial

    private lateinit var sliderAdaptiveAudioNoise: Slider
    private lateinit var sliderToneVolume: Slider

    private lateinit var rowAdaptiveAudioNoise: View
    private lateinit var rowVolumeSwipeLength: View
    private lateinit var rowToneVolume: View
    private lateinit var rowPressSpeed: View
    private lateinit var rowPressHoldDuration: View

    private lateinit var switchService: SwitchMaterial
    private lateinit var switchAutoPause: SwitchMaterial
    private lateinit var switchPauseOnSingle: SwitchMaterial
    private lateinit var switchAutoResume: SwitchMaterial
    private lateinit var switchRequireConnected: SwitchMaterial
    private lateinit var chipGroupDeviceProfile: ChipGroup

    private lateinit var btnGrantPermissions: Button
    private lateinit var btnBtSettings: Button
    private lateinit var btnLocationSettings: Button
    private lateinit var btnEqImportFile: Button
    private lateinit var btnEqPaste: Button
    private lateinit var btnEqClear: Button

    private lateinit var eqBars: List<View>

    private var suppressListeners = false
    private var importedEqPreset: PowerampEqPreset? = null
    private var latestLiveEqBands: FloatArray? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AirPodsForegroundService.ACTION_STATUS_UPDATE) return
            render(intent)
        }
    }

    private val eqPresetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importEqPresetFromUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        bindSettings()
        bindControls()
        bindEqImportTools()
        importedEqPreset = AppSettings.getImportedEqPreset(this)
        updatePermissionWarning()

        btnGrantPermissions.setOnClickListener { requestNeededPermissions() }
        btnBtSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        btnLocationSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        renderNoStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AirPodsForegroundService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        maybeStartService()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionWarning()
            maybeStartService()
        }
    }

    private fun bindViews() {
        tvModel = findViewById(R.id.tvModel)
        tvAapState = findViewById(R.id.tvAapState)
        tvConnection = findViewById(R.id.tvConnection)
        tvBattery = findViewById(R.id.tvBattery)
        tvEar = findViewById(R.id.tvEar)
        tvMeta = findViewById(R.id.tvMeta)
        tvConversationMeta = findViewById(R.id.tvConversationMeta)
        tvAdaptiveAudioNoiseLabel = findViewById(R.id.tvAdaptiveAudioNoiseLabel)
        tvToneVolumeLabel = findViewById(R.id.tvToneVolumeLabel)
        tvEqStatus = findViewById(R.id.tvEqStatus)
        tvEqTelemetry = findViewById(R.id.tvEqTelemetry)

        cardNoiseControl = findViewById(R.id.cardNoiseControl)
        cardListening = findViewById(R.id.cardListening)
        cardAdaptiveEq = findViewById(R.id.cardAdaptiveEq)

        chipGroupAncMode = findViewById(R.id.chipGroupAncMode)
        chipAncOff = findViewById(R.id.chipAncOff)
        chipAncOn = findViewById(R.id.chipAncOn)
        chipAncTransparency = findViewById(R.id.chipAncTransparency)
        chipAncAdaptive = findViewById(R.id.chipAncAdaptive)

        chipGroupMicMode = findViewById(R.id.chipGroupMicMode)
        chipMicAuto = findViewById(R.id.chipMicAuto)
        chipMicRight = findViewById(R.id.chipMicRight)
        chipMicLeft = findViewById(R.id.chipMicLeft)

        chipGroupVolumeSwipeLength = findViewById(R.id.chipGroupVolumeSwipeLength)
        chipSwipeDefault = findViewById(R.id.chipSwipeDefault)
        chipSwipeLonger = findViewById(R.id.chipSwipeLonger)
        chipSwipeLongest = findViewById(R.id.chipSwipeLongest)

        chipGroupPressSpeed = findViewById(R.id.chipGroupPressSpeed)
        chipPressSpeedDefault = findViewById(R.id.chipPressSpeedDefault)
        chipPressSpeedSlower = findViewById(R.id.chipPressSpeedSlower)
        chipPressSpeedSlowest = findViewById(R.id.chipPressSpeedSlowest)

        chipGroupPressHoldDuration = findViewById(R.id.chipGroupPressHoldDuration)
        chipPressHoldDefault = findViewById(R.id.chipPressHoldDefault)
        chipPressHoldShorter = findViewById(R.id.chipPressHoldShorter)
        chipPressHoldShortest = findViewById(R.id.chipPressHoldShortest)

        switchConversationAwareness = findViewById(R.id.switchConversationAwareness)
        switchPersonalizedVolume = findViewById(R.id.switchPersonalizedVolume)
        switchAllowOffOption = findViewById(R.id.switchAllowOffOption)
        switchNcOneAirpod = findViewById(R.id.switchNcOneAirpod)
        switchEarDetection = findViewById(R.id.switchEarDetection)
        switchVolumeSwipe = findViewById(R.id.switchVolumeSwipe)
        switchInCaseTone = findViewById(R.id.switchInCaseTone)
        switchSleepDetection = findViewById(R.id.switchSleepDetection)

        sliderAdaptiveAudioNoise = findViewById(R.id.sliderAdaptiveAudioNoise)
        sliderToneVolume = findViewById(R.id.sliderToneVolume)

        rowAdaptiveAudioNoise = findViewById(R.id.rowAdaptiveAudioNoise)
        rowVolumeSwipeLength = findViewById(R.id.rowVolumeSwipeLength)
        rowToneVolume = findViewById(R.id.rowToneVolume)
        rowPressSpeed = findViewById(R.id.rowPressSpeed)
        rowPressHoldDuration = findViewById(R.id.rowPressHoldDuration)

        switchService = findViewById(R.id.switchService)
        switchAutoPause = findViewById(R.id.switchAutoPause)
        switchPauseOnSingle = findViewById(R.id.switchPauseOnSingle)
        switchAutoResume = findViewById(R.id.switchAutoResume)
        switchRequireConnected = findViewById(R.id.switchRequireConnected)
        chipGroupDeviceProfile = findViewById(R.id.chipGroupDeviceProfile)

        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnBtSettings = findViewById(R.id.btnBtSettings)
        btnLocationSettings = findViewById(R.id.btnLocationSettings)
        btnEqImportFile = findViewById(R.id.btnEqImportFile)
        btnEqPaste = findViewById(R.id.btnEqPaste)
        btnEqClear = findViewById(R.id.btnEqClear)

        eqBars = listOf(
            findViewById(R.id.eqBar1),
            findViewById(R.id.eqBar2),
            findViewById(R.id.eqBar3),
            findViewById(R.id.eqBar4),
            findViewById(R.id.eqBar5),
            findViewById(R.id.eqBar6),
            findViewById(R.id.eqBar7),
            findViewById(R.id.eqBar8),
        )
    }

    private fun bindSettings() {
        switchService.isChecked = AppSettings.isServiceEnabled(this)
        switchAutoPause.isChecked = AppSettings.isAutoPauseEnabled(this)
        switchPauseOnSingle.isChecked = AppSettings.isPauseOnSingleEnabled(this)
        switchAutoResume.isChecked = AppSettings.isAutoResumeEnabled(this)
        switchRequireConnected.isChecked = AppSettings.isRequireConnectedEnabled(this)
        applyDeviceProfileSelection(AppSettings.getDeviceProfileOverride(this))

        switchService.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setServiceEnabled(this, isChecked)
            if (isChecked) maybeStartService() else AirPodsForegroundService.stop(this)
        }
        switchAutoPause.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoPauseEnabled(this, isChecked)
        }
        switchPauseOnSingle.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setPauseOnSingleEnabled(this, isChecked)
        }
        switchAutoResume.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoResumeEnabled(this, isChecked)
        }
        switchRequireConnected.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setRequireConnectedEnabled(this, isChecked)
        }

        chipGroupDeviceProfile.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val override = when (checkedIds.firstOrNull()) {
                R.id.chipProfileAirPods3 -> DeviceProfileOverride.AIRPODS_3
                R.id.chipProfileAirPodsPro -> DeviceProfileOverride.AIRPODS_PRO
                R.id.chipProfileAirPodsPro23 -> DeviceProfileOverride.AIRPODS_PRO_2_3
                R.id.chipProfileAirPods4Anc -> DeviceProfileOverride.AIRPODS_4_ANC
                else -> DeviceProfileOverride.AUTO
            }
            AppSettings.setDeviceProfileOverride(this, override)
            if (AppSettings.isServiceEnabled(this) && hasAllPermissions()) {
                AirPodsForegroundService.stop(this)
                AirPodsForegroundService.start(this)
            }
        }
    }

    private fun bindControls() {
        chipGroupAncMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chipAncOff -> AncMode.OFF
                R.id.chipAncOn -> AncMode.ON
                R.id.chipAncTransparency -> AncMode.TRANSPARENCY
                R.id.chipAncAdaptive -> AncMode.ADAPTIVE
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            AirPodsForegroundService.sendCommand(
                this,
                AirPodsForegroundService.CONTROL_ANC_MODE,
                value = mode.name
            )
        }

        chipGroupMicMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chipMicAuto -> MicrophoneMode.AUTO
                R.id.chipMicRight -> MicrophoneMode.ALWAYS_RIGHT
                R.id.chipMicLeft -> MicrophoneMode.ALWAYS_LEFT
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            AirPodsForegroundService.sendCommand(
                this,
                AirPodsForegroundService.CONTROL_MICROPHONE_MODE,
                value = mode.name
            )
        }

        chipGroupVolumeSwipeLength.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val value = when (checkedIds.firstOrNull()) {
                R.id.chipSwipeDefault -> VolumeSwipeLength.DEFAULT
                R.id.chipSwipeLonger -> VolumeSwipeLength.LONGER
                R.id.chipSwipeLongest -> VolumeSwipeLength.LONGEST
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            AirPodsForegroundService.sendCommand(
                this,
                AirPodsForegroundService.CONTROL_VOLUME_SWIPE_LENGTH,
                value = value.name
            )
        }

        chipGroupPressSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val value = when (checkedIds.firstOrNull()) {
                R.id.chipPressSpeedDefault -> PressSpeed.DEFAULT
                R.id.chipPressSpeedSlower -> PressSpeed.SLOWER
                R.id.chipPressSpeedSlowest -> PressSpeed.SLOWEST
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            AirPodsForegroundService.sendCommand(
                this,
                AirPodsForegroundService.CONTROL_PRESS_SPEED,
                value = value.name
            )
        }

        chipGroupPressHoldDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressListeners) return@setOnCheckedStateChangeListener
            val value = when (checkedIds.firstOrNull()) {
                R.id.chipPressHoldDefault -> PressHoldDuration.DEFAULT
                R.id.chipPressHoldShorter -> PressHoldDuration.SHORTER
                R.id.chipPressHoldShortest -> PressHoldDuration.SHORTEST
                else -> null
            } ?: return@setOnCheckedStateChangeListener
            AirPodsForegroundService.sendCommand(
                this,
                AirPodsForegroundService.CONTROL_PRESS_HOLD_DURATION,
                value = value.name
            )
        }

        switchConversationAwareness.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_CONVERSATION_AWARENESS,
                    booleanValue = isChecked
                )
            }
        }
        switchPersonalizedVolume.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_PERSONALIZED_VOLUME,
                    booleanValue = isChecked
                )
            }
        }
        switchAllowOffOption.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_ALLOW_OFF_OPTION,
                    booleanValue = isChecked
                )
            }
        }
        switchNcOneAirpod.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_NC_ONE_AIRPOD,
                    booleanValue = isChecked
                )
            }
        }
        switchEarDetection.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_EAR_DETECTION,
                    booleanValue = isChecked
                )
            }
        }
        switchVolumeSwipe.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_VOLUME_SWIPE,
                    booleanValue = isChecked
                )
            }
        }
        switchInCaseTone.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_IN_CASE_TONE,
                    booleanValue = isChecked
                )
            }
        }
        switchSleepDetection.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressListeners) {
                AirPodsForegroundService.sendCommand(
                    this,
                    AirPodsForegroundService.CONTROL_SLEEP_DETECTION,
                    booleanValue = isChecked
                )
            }
        }

        sliderAdaptiveAudioNoise.addOnChangeListener { _, value, _ ->
            tvAdaptiveAudioNoiseLabel.text = getString(R.string.adaptive_audio_noise_level, value.toInt())
        }
        sliderAdaptiveAudioNoise.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (!suppressListeners) {
                    AirPodsForegroundService.sendCommand(
                        this@MainActivity,
                        AirPodsForegroundService.CONTROL_ADAPTIVE_AUDIO_NOISE,
                        intValue = slider.value.toInt()
                    )
                }
            }
        })

        sliderToneVolume.addOnChangeListener { _, value, _ ->
            tvToneVolumeLabel.text = getString(R.string.tone_volume_level, value.toInt())
        }
        sliderToneVolume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (!suppressListeners) {
                    AirPodsForegroundService.sendCommand(
                        this@MainActivity,
                        AirPodsForegroundService.CONTROL_TONE_VOLUME,
                        intValue = slider.value.toInt()
                    )
                }
            }
        })
    }

    private fun bindEqImportTools() {
        btnEqImportFile.setOnClickListener {
            eqPresetPicker.launch(arrayOf("application/json", "text/plain", "text/*", "*/*"))
        }
        btnEqPaste.setOnClickListener {
            showEqPasteDialog()
        }
        btnEqClear.setOnClickListener {
            importedEqPreset = null
            AppSettings.setImportedEqPreset(this, null)
            renderEq(latestLiveEqBands)
            Toast.makeText(this, getString(R.string.eq_import_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeStartService() {
        if (!AppSettings.isServiceEnabled(this)) return
        if (!hasAllPermissions()) return
        AirPodsForegroundService.start(this)
    }

    private fun updatePermissionWarning() {
        val hasPermissions = hasAllPermissions()
        btnGrantPermissions.isEnabled = !hasPermissions
        btnGrantPermissions.text = if (hasPermissions) getString(R.string.permissions_ok) else getString(R.string.grant_permissions)
    }

    private fun requestNeededPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            updatePermissionWarning()
            maybeStartService()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun render(intent: Intent) {
        val aapState = intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_STATE) ?: "unknown"
        val aapError = intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_ERROR).orEmpty()
        val aapDevice = intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_DEVICE).orEmpty()
        val aapReady = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_READY, false)
        val available = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AVAILABLE, false)

        tvAapState.text = formatAapState(aapState, aapReady)

        if (!available) {
            renderNoStatus(
                scanTotal = intent.getIntExtra(AirPodsForegroundService.EXTRA_SCAN_TOTAL, 0),
                applePacketCount = intent.getIntExtra(AirPodsForegroundService.EXTRA_APPLE_PACKET_COUNT, 0),
                parsedPacketCount = intent.getIntExtra(AirPodsForegroundService.EXTRA_PARSED_PACKET_COUNT, 0),
                acceptedPacketCount = intent.getIntExtra(AirPodsForegroundService.EXTRA_ACCEPTED_PACKET_COUNT, 0),
                lastCallbackAgeMs = intent.getLongExtra(AirPodsForegroundService.EXTRA_LAST_CALLBACK_AGE_MS, -1L),
                scanMode = intent.getStringExtra(AirPodsForegroundService.EXTRA_SCAN_MODE) ?: "unknown",
                locationEnabled = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_LOCATION_ENABLED, false),
                lastApplePreview = intent.getStringExtra(AirPodsForegroundService.EXTRA_LAST_APPLE_PREVIEW).orEmpty(),
                lastRejectReason = intent.getStringExtra(AirPodsForegroundService.EXTRA_LAST_REJECT_REASON).orEmpty(),
                aapState = aapState,
                aapDevice = aapDevice,
                aapError = aapError
            )
        } else {
            val model = intent.getStringExtra(AirPodsForegroundService.EXTRA_MODEL) ?: getString(R.string.status_unknown)
            val connection = intent.getStringExtra(AirPodsForegroundService.EXTRA_CONNECTION) ?: "Unknown"
            val source = intent.getStringExtra(AirPodsForegroundService.EXTRA_SOURCE) ?: "Unknown"
            val leftBattery = intent.getIntExtra(AirPodsForegroundService.EXTRA_LEFT_BATTERY, -1)
            val rightBattery = intent.getIntExtra(AirPodsForegroundService.EXTRA_RIGHT_BATTERY, -1)
            val caseBattery = intent.getIntExtra(AirPodsForegroundService.EXTRA_CASE_BATTERY, -1)
            val leftInEar = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_LEFT_IN_EAR, false)
            val rightInEar = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_RIGHT_IN_EAR, false)
            val lidOpen = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_LID_OPEN, false)
            val color = intent.getStringExtra(AirPodsForegroundService.EXTRA_COLOR) ?: "Unknown"
            val rssi = intent.getIntExtra(AirPodsForegroundService.EXTRA_RSSI, 0)
            val timestamp = intent.getLongExtra(AirPodsForegroundService.EXTRA_TIMESTAMP, 0L)
            val modelNumber = intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_MODEL_NUMBER).orEmpty()
            val firmware = intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_FIRMWARE).orEmpty()

            tvModel.text = model
            tvConnection.text = getString(R.string.connection_summary, connection, source, formatAapState(aapState, aapReady))
            tvBattery.text = getString(
                R.string.battery_summary,
                formatBattery(leftBattery),
                formatBattery(rightBattery),
                formatBattery(caseBattery)
            )
            tvEar.text = getString(
                R.string.fit_summary,
                yesNo(leftInEar),
                yesNo(rightInEar),
                if (lidOpen) getString(R.string.lid_open) else getString(R.string.lid_closed)
            )

            val time = if (timestamp > 0L) {
                DateFormat.format("HH:mm:ss", Date(timestamp)).toString()
            } else {
                "--:--:--"
            }
            val details = mutableListOf<String>()
            if (modelNumber.isNotBlank()) details += modelNumber
            if (firmware.isNotBlank()) details += firmware
            details += "$color • ${rssi}dBm"
            details += getString(R.string.updated_at, time)
            if (aapDevice.isNotBlank()) details += aapDevice
            tvMeta.text = details.joinToString(" • ")
        }

        val supportedAncModes = intent.getStringArrayExtra(AirPodsForegroundService.EXTRA_AAP_SUPPORTED_ANC_MODES)
            ?.toSet()
            .orEmpty()

        val supportsAnc = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_ANC, false)
        val supportsConversation = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_CONVERSATION_AWARENESS, false)
        val supportsPersonalizedVolume = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_PERSONALIZED_VOLUME, false)
        val supportsAdaptiveNoise = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_ADAPTIVE_AUDIO_NOISE, false)
        val supportsMicMode = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_MICROPHONE_MODE, false)
        val supportsNcOneAirpod = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_NC_ONE_AIRPOD, false)
        val supportsEarDetection = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_EAR_DETECTION, false)
        val supportsVolumeSwipe = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_VOLUME_SWIPE, false)
        val supportsVolumeSwipeLength = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_VOLUME_SWIPE_LENGTH, false)
        val supportsToneVolume = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_TONE_VOLUME, false)
        val supportsInCaseTone = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_IN_CASE_TONE, false)
        val supportsSleepDetection = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_SLEEP_DETECTION, false)
        val supportsPressSpeed = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_PRESS_SPEED, false)
        val supportsPressHoldDuration = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_PRESS_HOLD_DURATION, false)
        val supportsAllowOff = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_ALLOW_OFF, false)
        val supportsEq = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_FEATURE_EQ, false) || intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_EQ_BANDS)

        cardNoiseControl.isVisible = supportsAnc || supportsConversation || supportsPersonalizedVolume || supportsAdaptiveNoise || supportsAllowOff
        cardListening.isVisible = supportsMicMode || supportsNcOneAirpod || supportsEarDetection || supportsVolumeSwipe || supportsVolumeSwipeLength || supportsToneVolume || supportsInCaseTone || supportsSleepDetection || supportsPressSpeed || supportsPressHoldDuration
        cardAdaptiveEq.isVisible = true

        chipAncOff.isVisible = AncMode.OFF.name in supportedAncModes
        chipAncOn.isVisible = AncMode.ON.name in supportedAncModes
        chipAncTransparency.isVisible = AncMode.TRANSPARENCY.name in supportedAncModes
        chipAncAdaptive.isVisible = AncMode.ADAPTIVE.name in supportedAncModes

        switchConversationAwareness.isVisible = supportsConversation
        tvConversationMeta.isVisible = supportsConversation
        switchPersonalizedVolume.isVisible = supportsPersonalizedVolume
        switchAllowOffOption.isVisible = supportsAllowOff
        rowAdaptiveAudioNoise.isVisible = supportsAdaptiveNoise

        chipGroupMicMode.isVisible = supportsMicMode
        findViewById<TextView>(R.id.tvMicModeLabel).isVisible = supportsMicMode
        switchNcOneAirpod.isVisible = supportsNcOneAirpod
        switchEarDetection.isVisible = supportsEarDetection
        switchVolumeSwipe.isVisible = supportsVolumeSwipe
        rowVolumeSwipeLength.isVisible = supportsVolumeSwipeLength
        rowToneVolume.isVisible = supportsToneVolume
        rowPressSpeed.isVisible = supportsPressSpeed
        rowPressHoldDuration.isVisible = supportsPressHoldDuration
        switchInCaseTone.isVisible = supportsInCaseTone
        switchSleepDetection.isVisible = supportsSleepDetection

        val conversationMeta = when {
            aapError.isNotBlank() -> aapError
            intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_CONVERSATION_SPEAKING) &&
                intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_CONVERSATION_SPEAKING, false) -> getString(R.string.conversation_live_speaking)
            supportsConversation && aapReady -> getString(R.string.conversation_live_ready)
            supportsConversation -> getString(R.string.controls_waiting_for_connection)
            else -> ""
        }
        tvConversationMeta.text = conversationMeta

        withSuppressed {
            applyAncSelection(intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_ANC_MODE))
            applyMicModeSelection(intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_MICROPHONE_MODE))
            applyVolumeSwipeLengthSelection(intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_VOLUME_SWIPE_LENGTH))
            applyPressSpeedSelection(intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_PRESS_SPEED))
            applyPressHoldDurationSelection(intent.getStringExtra(AirPodsForegroundService.EXTRA_AAP_PRESS_HOLD_DURATION))

            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_CONVERSATION_AWARENESS)) {
                switchConversationAwareness.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_CONVERSATION_AWARENESS, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_PERSONALIZED_VOLUME)) {
                switchPersonalizedVolume.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_PERSONALIZED_VOLUME, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_ALLOW_OFF)) {
                switchAllowOffOption.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_ALLOW_OFF, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_NC_ONE_AIRPOD)) {
                switchNcOneAirpod.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_NC_ONE_AIRPOD, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_EAR_DETECTION)) {
                switchEarDetection.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_EAR_DETECTION, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_VOLUME_SWIPE)) {
                switchVolumeSwipe.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_VOLUME_SWIPE, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_IN_CASE_TONE)) {
                switchInCaseTone.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_IN_CASE_TONE, false)
            }
            if (intent.hasExtra(AirPodsForegroundService.EXTRA_AAP_SLEEP_DETECTION)) {
                switchSleepDetection.isChecked = intent.getBooleanExtra(AirPodsForegroundService.EXTRA_AAP_SLEEP_DETECTION, false)
            }
            sliderAdaptiveAudioNoise.value = intent.getIntExtra(AirPodsForegroundService.EXTRA_AAP_ADAPTIVE_AUDIO_NOISE, 50).toFloat()
            sliderToneVolume.value = intent.getIntExtra(AirPodsForegroundService.EXTRA_AAP_TONE_VOLUME, 50).toFloat()
        }

        tvAdaptiveAudioNoiseLabel.text = getString(
            R.string.adaptive_audio_noise_level,
            intent.getIntExtra(AirPodsForegroundService.EXTRA_AAP_ADAPTIVE_AUDIO_NOISE, 50)
        )
        tvToneVolumeLabel.text = getString(
            R.string.tone_volume_level,
            intent.getIntExtra(AirPodsForegroundService.EXTRA_AAP_TONE_VOLUME, 50)
        )

        val controlsEnabled = aapReady
        chipGroupAncMode.isEnabled = controlsEnabled && supportsAnc
        switchConversationAwareness.isEnabled = controlsEnabled && supportsConversation
        switchPersonalizedVolume.isEnabled = controlsEnabled && supportsPersonalizedVolume
        switchAllowOffOption.isEnabled = controlsEnabled && supportsAllowOff
        sliderAdaptiveAudioNoise.isEnabled = controlsEnabled && supportsAdaptiveNoise
        chipGroupMicMode.isEnabled = controlsEnabled && supportsMicMode
        switchNcOneAirpod.isEnabled = controlsEnabled && supportsNcOneAirpod
        switchEarDetection.isEnabled = controlsEnabled && supportsEarDetection
        switchVolumeSwipe.isEnabled = controlsEnabled && supportsVolumeSwipe
        chipGroupVolumeSwipeLength.isEnabled = controlsEnabled && supportsVolumeSwipeLength
        sliderToneVolume.isEnabled = controlsEnabled && supportsToneVolume
        chipGroupPressSpeed.isEnabled = controlsEnabled && supportsPressSpeed
        chipGroupPressHoldDuration.isEnabled = controlsEnabled && supportsPressHoldDuration
        switchInCaseTone.isEnabled = controlsEnabled && supportsInCaseTone
        switchSleepDetection.isEnabled = controlsEnabled && supportsSleepDetection

        val eqBands = intent.getFloatArrayExtra(AirPodsForegroundService.EXTRA_AAP_EQ_BANDS)
        renderEq(eqBands)
    }

    private fun renderNoStatus(
        scanTotal: Int = 0,
        applePacketCount: Int = 0,
        parsedPacketCount: Int = 0,
        acceptedPacketCount: Int = 0,
        lastCallbackAgeMs: Long = -1L,
        scanMode: String = "unknown",
        locationEnabled: Boolean = false,
        lastApplePreview: String = "",
        lastRejectReason: String = "",
        aapState: String = "unknown",
        aapDevice: String = "",
        aapError: String = ""
    ) {
        tvModel.text = getString(R.string.status_unknown)
        tvConnection.text = getString(
            R.string.waiting_connection_summary,
            scanMode,
            formatAapState(aapState, false),
            if (locationEnabled) getString(R.string.location_on) else getString(R.string.location_off)
        )
        tvBattery.text = if (lastApplePreview.isNotEmpty()) {
            getString(R.string.last_packet, lastApplePreview)
        } else {
            getString(R.string.open_case_hint)
        }
        tvEar.text = when {
            lastRejectReason.isNotEmpty() -> getString(R.string.reject_reason, lastRejectReason)
            aapError.isNotEmpty() -> getString(R.string.aap_error, aapError)
            else -> getString(R.string.waiting_ear_status)
        }
        val lastSeen = if (lastCallbackAgeMs >= 0) "${lastCallbackAgeMs / 1000}s ago" else getString(R.string.never)
        val details = mutableListOf<String>()
        details += getString(R.string.scan_counts, scanTotal, applePacketCount, parsedPacketCount, acceptedPacketCount)
        details += getString(R.string.last_callback, lastSeen)
        if (aapDevice.isNotEmpty()) details += aapDevice
        tvMeta.text = details.joinToString(" • ")
        renderEq(null)
    }

    private fun renderEq(bands: FloatArray?) {
        latestLiveEqBands = bands?.copyOf()
        val liveText = if (bands == null || bands.isEmpty()) {
            getString(R.string.eq_waiting)
        } else {
            getString(
                R.string.eq_live,
                bands.take(eqBars.size).joinToString("  ") { String.format(Locale.US, "%.1f", it) }
            )
        }

        val imported = importedEqPreset
        if (imported == null) {
            tvEqStatus.text = getString(R.string.eq_import_empty)
            tvEqTelemetry.text = liveText
            renderEqBars(bands?.take(eqBars.size))
            btnEqClear.isVisible = false
            return
        }

        tvEqStatus.text = getString(
            R.string.eq_imported_summary,
            imported.name,
            formatSignedDb(imported.preampDb),
            imported.enabledBands().size
        )
        tvEqTelemetry.text = buildString {
            append(getString(R.string.eq_preview, imported.previewSummary()))
            append('\n')
            append(liveText)
        }
        renderEqBars(imported.previewBands(eqBars.size).map { it.gainDb })
        btnEqClear.isVisible = true
    }

    private fun renderEqBars(values: List<Float>?) {
        if (values.isNullOrEmpty()) {
            eqBars.forEach { it.scaleY = 0.2f }
            return
        }

        val usable = values.take(eqBars.size)
        val maxMagnitude = usable.maxOfOrNull { abs(it) }?.coerceAtLeast(0.3f) ?: 1f
        usable.forEachIndexed { index, value ->
            val normalized = (abs(value) / maxMagnitude).coerceIn(0f, 1f)
            eqBars[index].pivotY = eqBars[index].height.toFloat()
            eqBars[index].scaleY = 0.2f + normalized * 0.8f
        }
        if (usable.size < eqBars.size) {
            for (index in usable.size until eqBars.size) {
                eqBars[index].scaleY = 0.2f
            }
        }
    }

    private fun applyAncSelection(value: String?) {
        when (value) {
            AncMode.OFF.name -> chipGroupAncMode.check(R.id.chipAncOff)
            AncMode.ON.name -> chipGroupAncMode.check(R.id.chipAncOn)
            AncMode.TRANSPARENCY.name -> chipGroupAncMode.check(R.id.chipAncTransparency)
            AncMode.ADAPTIVE.name -> chipGroupAncMode.check(R.id.chipAncAdaptive)
            else -> chipGroupAncMode.clearCheck()
        }
    }

    private fun applyMicModeSelection(value: String?) {
        when (value) {
            MicrophoneMode.AUTO.name -> chipGroupMicMode.check(R.id.chipMicAuto)
            MicrophoneMode.ALWAYS_RIGHT.name -> chipGroupMicMode.check(R.id.chipMicRight)
            MicrophoneMode.ALWAYS_LEFT.name -> chipGroupMicMode.check(R.id.chipMicLeft)
            else -> chipGroupMicMode.clearCheck()
        }
    }

    private fun applyVolumeSwipeLengthSelection(value: String?) {
        when (value) {
            VolumeSwipeLength.DEFAULT.name -> chipGroupVolumeSwipeLength.check(R.id.chipSwipeDefault)
            VolumeSwipeLength.LONGER.name -> chipGroupVolumeSwipeLength.check(R.id.chipSwipeLonger)
            VolumeSwipeLength.LONGEST.name -> chipGroupVolumeSwipeLength.check(R.id.chipSwipeLongest)
            else -> chipGroupVolumeSwipeLength.clearCheck()
        }
    }

    private fun applyPressSpeedSelection(value: String?) {
        when (value) {
            PressSpeed.DEFAULT.name -> chipGroupPressSpeed.check(R.id.chipPressSpeedDefault)
            PressSpeed.SLOWER.name -> chipGroupPressSpeed.check(R.id.chipPressSpeedSlower)
            PressSpeed.SLOWEST.name -> chipGroupPressSpeed.check(R.id.chipPressSpeedSlowest)
            else -> chipGroupPressSpeed.clearCheck()
        }
    }

    private fun applyPressHoldDurationSelection(value: String?) {
        when (value) {
            PressHoldDuration.DEFAULT.name -> chipGroupPressHoldDuration.check(R.id.chipPressHoldDefault)
            PressHoldDuration.SHORTER.name -> chipGroupPressHoldDuration.check(R.id.chipPressHoldShorter)
            PressHoldDuration.SHORTEST.name -> chipGroupPressHoldDuration.check(R.id.chipPressHoldShortest)
            else -> chipGroupPressHoldDuration.clearCheck()
        }
    }

    private fun applyDeviceProfileSelection(value: DeviceProfileOverride) {
        withSuppressed {
            when (value) {
                DeviceProfileOverride.AIRPODS_3 -> chipGroupDeviceProfile.check(R.id.chipProfileAirPods3)
                DeviceProfileOverride.AIRPODS_PRO -> chipGroupDeviceProfile.check(R.id.chipProfileAirPodsPro)
                DeviceProfileOverride.AIRPODS_PRO_2_3 -> chipGroupDeviceProfile.check(R.id.chipProfileAirPodsPro23)
                DeviceProfileOverride.AIRPODS_4_ANC -> chipGroupDeviceProfile.check(R.id.chipProfileAirPods4Anc)
                DeviceProfileOverride.AUTO -> chipGroupDeviceProfile.check(R.id.chipProfileAuto)
            }
        }
    }

    private fun formatAapState(state: String, ready: Boolean): String {
        return when {
            ready -> getString(R.string.aap_live)
            state == "handshaking" -> getString(R.string.aap_handshaking)
            state == "connecting" -> getString(R.string.aap_connecting)
            state == "searching" -> getString(R.string.aap_searching)
            state == "backoff" -> getString(R.string.aap_backoff)
            state == "timeout" -> getString(R.string.aap_timeout)
            state == "error" -> getString(R.string.aap_error_short)
            else -> getString(R.string.aap_idle)
        }
    }

    private fun showEqPasteDialog() {
        val input = EditText(this).apply {
            minLines = 8
            hint = getString(R.string.eq_import_paste_hint)
            setText("")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.eq_import_paste_title)
            .setMessage(R.string.eq_import_paste_message)
            .setView(input)
            .setPositiveButton(R.string.eq_import_paste) { _, _ ->
                importEqPresetAsync(
                    raw = input.text?.toString().orEmpty(),
                    fallbackName = "Pasted preset",
                    sourceLabel = "Pasted text"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importEqPresetFromUri(uri: Uri) {
        val displayName = queryDisplayName(uri)
        Thread {
            val result = runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IOException("Could not read the selected preset file.")
                PowerampEqPresetParser.parse(
                    raw = raw,
                    fallbackName = displayName?.substringBeforeLast('.'),
                    sourceLabel = displayName ?: uri.lastPathSegment
                )
            }
            runOnUiThread {
                handleImportedEqResult(result)
            }
        }.start()
    }

    private fun importEqPresetAsync(
        raw: String,
        fallbackName: String?,
        sourceLabel: String?,
    ) {
        Thread {
            val result = runCatching {
                PowerampEqPresetParser.parse(
                    raw = raw,
                    fallbackName = fallbackName,
                    sourceLabel = sourceLabel
                )
            }
            runOnUiThread {
                handleImportedEqResult(result)
            }
        }.start()
    }

    private fun handleImportedEqResult(result: Result<PowerampEqPreset>) {
        result.onSuccess { preset ->
            importedEqPreset = preset
            AppSettings.setImportedEqPreset(this, preset)
            renderEq(latestLiveEqBands)
            Toast.makeText(this, getString(R.string.eq_import_success, preset.name), Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.eq_import_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return cursor.getString(index)
        }
        return null
    }

    private fun withSuppressed(block: () -> Unit) {
        suppressListeners = true
        try {
            block()
        } finally {
            suppressListeners = false
        }
    }

    private fun yesNo(value: Boolean): String = if (value) getString(R.string.in_ear) else getString(R.string.out_ear)

    private fun formatBattery(value: Int): String = if (value >= 0) "$value%" else "--"
    private fun formatSignedDb(value: Float): String = String.format(Locale.US, "%+.1f dB", value)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 74
    }
}
