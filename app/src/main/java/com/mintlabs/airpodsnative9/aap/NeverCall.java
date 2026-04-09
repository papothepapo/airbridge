package com.mintlabs.airpodsnative9.aap;

import androidx.annotation.Keep;

/**
 * Helper class for ART method-size calculation.
 *
 * Based on LSPosed/AndroidHiddenApiBypass (Apache 2.0).
 */
@Keep
class NeverCall {
    private static void a() { throw new RuntimeException(); }
    private static void b() { throw new RuntimeException(); }
}
