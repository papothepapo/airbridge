package com.mintlabs.airpodsnative9.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.lang.reflect.InvocationTargetException

@SuppressLint("MissingPermission")
class L2capSocketFactory {
    fun createSocket(device: BluetoothDevice, psm: Int): BluetoothSocket {
        require(psm > 0) { "Invalid PSM: $psm" }

        try {
            return invokeHidden(device, psm)
        } catch (_: Throwable) {
            HiddenApiBypass.setExemptions("Landroid/bluetooth/")
            return invokeHidden(device, psm)
        }
    }

    private fun invokeHidden(device: BluetoothDevice, psm: Int): BluetoothSocket {
        return try {
            val method = BluetoothDevice::class.java.getDeclaredMethod(
                "createInsecureL2capSocket",
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(device, psm) as BluetoothSocket
        } catch (error: InvocationTargetException) {
            throw error.cause ?: IOException("createInsecureL2capSocket failed", error)
        } catch (error: ReflectiveOperationException) {
            throw IOException("Unable to create L2CAP socket", error)
        }
    }
}
