@file:Suppress("unused")

package com.mintlabs.airpodsnative9.aap

import androidx.annotation.Keep
import java.lang.invoke.MethodType

/**
 * Mirror classes matching ART's internal field layout for offset calculation.
 *
 * Based on LSPosed/AndroidHiddenApiBypass (Apache 2.0).
 */
@Keep
object ArtMirror {

    @Keep
    open class AccessibleObject {
        private val override: Boolean = false
    }

    @Keep
    class Executable : AccessibleObject() {
        private val declaringClass: Any? = null
        private val declaringClassOfOverriddenMethod: Any? = null
        private val parameters: Array<Any>? = null
        @JvmField val artMethod: Long = 0
        private val accessFlags: Int = 0
    }

    @Keep
    class MethodHandle {
        private val type: MethodType? = null
        private val nominalType: MethodType? = null
        private val cachedSpreadInvoker: MethodHandle? = null
        private val handleKind: Int = 0
        @JvmField val artFieldOrMethod: Long = 0
    }

    @Keep
    class Class {
        private val classLoader: ClassLoader? = null
        private val componentType: Any? = null
        private val dexCache: Any? = null
        private val extData: Any? = null
        private val ifTable: Array<Any>? = null
        private val name: String? = null
        private val superClass: Any? = null
        private val vtable: Any? = null
        @JvmField val iFields: Long = 0
        @JvmField val methods: Long = 0
        @JvmField val sFields: Long = 0
        private val accessFlags: Int = 0
        private val classFlags: Int = 0
        private val classSize: Int = 0
        private val clinitThreadId: Int = 0
        private val dexClassDefIndex: Int = 0
        @Volatile private var dexTypeIndex: Int = 0
        private val numReferenceInstanceFields: Int = 0
        private val numReferenceStaticFields: Int = 0
        private val objectSize: Int = 0
        private val objectSizeAllocFastPath: Int = 0
        private val primitiveType: Int = 0
        private val referenceInstanceOffsets: Int = 0
        private val status: Int = 0
        private val copiedMethodsOffset: Short = 0
        private val virtualMethodsOffset: Short = 0
    }
}
