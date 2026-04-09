package com.mintlabs.airpodsnative9.aap

import android.util.Log
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method

/**
 * Bypasses Android's hidden API restrictions by calling VMRuntime.setHiddenApiExemptions.
 *
 * Based on LSPosed/AndroidHiddenApiBypass (Apache 2.0).
 */
object HiddenApiBypass {
    private const val TAG = "AirPodsNative9"
    private val exemptedPrefixes = mutableSetOf<String>()

    @androidx.annotation.Keep
    private object InvokeStub {
        @JvmStatic
        fun invoke(vararg args: Any?): Any? {
            throw IllegalStateException("Stub method should never be called directly")
        }
    }

    @Synchronized
    fun setExemptions(vararg prefixes: String) {
        val newPrefixes = prefixes.filter { it !in exemptedPrefixes }
        if (newPrefixes.isEmpty()) return

        exemptedPrefixes.addAll(newPrefixes)
        val allPrefixes = exemptedPrefixes.toTypedArray()

        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredMethod("getUnsafe").invoke(null)
        val unsafeClass = unsafe::class.java
        val objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
        val getLong = unsafeClass.getMethod("getLong", Any::class.java, Long::class.javaPrimitiveType)
        val putLong = unsafeClass.getMethod("putLong", Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        val getInt = unsafeClass.getMethod("getInt", Long::class.javaPrimitiveType)

        val artMethodOff = objectFieldOffset.invoke(
            unsafe,
            ArtMirror.Executable::class.java.getDeclaredField("artMethod")
        ) as Long
        val artFieldOrMethodOff = objectFieldOffset.invoke(
            unsafe,
            ArtMirror.MethodHandle::class.java.getDeclaredField("artFieldOrMethod")
        ) as Long
        val methodsOff = objectFieldOffset.invoke(
            unsafe,
            ArtMirror.Class::class.java.getDeclaredField("methods")
        ) as Long

        val realArtMethodOff = objectFieldOffset.invoke(
            unsafe,
            java.lang.reflect.Executable::class.java.getDeclaredField("artMethod")
        ) as Long
        check(artMethodOff == realArtMethodOff) {
            "ART layout changed: mirror artMethod offset $artMethodOff != $realArtMethodOff"
        }

        val mA = NeverCall::class.java.getDeclaredMethod("a").apply { isAccessible = true }
        val mB = NeverCall::class.java.getDeclaredMethod("b").apply { isAccessible = true }
        val mhA = MethodHandles.lookup().unreflect(mA)
        val mhB = MethodHandles.lookup().unreflect(mB)
        val aAddr = getLong.invoke(unsafe, mhA, artFieldOrMethodOff) as Long
        val bAddr = getLong.invoke(unsafe, mhB, artFieldOrMethodOff) as Long
        val artMethodSize = bAddr - aAddr
        check(artMethodSize in 16..256) {
            "Unexpected artMethod size: $artMethodSize"
        }

        val ncMethods = getLong.invoke(unsafe, NeverCall::class.java, methodsOff) as Long
        val artMethodBias = aAddr - ncMethods - artMethodSize

        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
        val vmMethods = getLong.invoke(unsafe, vmRuntimeClass, methodsOff) as Long
        val numMethods = getInt.invoke(unsafe, vmMethods) as Int
        check(numMethods in 1..10_000) {
            "Unexpected VMRuntime method count: $numMethods"
        }

        val stubMethod: Method = InvokeStub::class.java.getDeclaredMethod("invoke", Array<Any?>::class.java)
        stubMethod.isAccessible = true
        val originalArtMethod = getLong.invoke(unsafe, stubMethod, artMethodOff) as Long

        var runtime: Any? = null
        var exemptionsSet = false
        try {
            for (index in 0 until numMethods) {
                val methodPtr = vmMethods + index * artMethodSize + artMethodBias
                putLong.invoke(unsafe, stubMethod, artMethodOff, methodPtr)

                val name = stubMethod.name
                val params = stubMethod.parameterTypes
                if (name == "getRuntime" && params.isEmpty() && runtime == null) {
                    runtime = stubMethod.invoke(null)
                }
                if (name == "setHiddenApiExemptions" && params.size == 1 && params[0] == Array<String>::class.java) {
                    if (runtime == null) {
                        throw IllegalStateException("VMRuntime.getRuntime not resolved before setHiddenApiExemptions")
                    }
                    stubMethod.invoke(runtime, allPrefixes as Any)
                    exemptionsSet = true
                    Log.d(TAG, "Hidden API exemptions set: ${allPrefixes.contentToString()}")
                }
                if (runtime != null && exemptionsSet) break
            }
        } finally {
            putLong.invoke(unsafe, stubMethod, artMethodOff, originalArtMethod)
        }

        check(runtime != null) { "VMRuntime.getRuntime() not found" }
        check(exemptionsSet) { "VMRuntime.setHiddenApiExemptions() not found" }
    }
}
