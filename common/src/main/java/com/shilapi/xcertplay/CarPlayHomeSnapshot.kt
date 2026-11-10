// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.graphics.Bitmap

/**
 * Carries the home snapshot from [DiPlayActivity] to [CarPlayHostActivity] in the same process.
 * It cannot be an Intent extra: a 2000x1200 home at quarter size is ~600 KB and Binder rejects it
 * with TransactionTooLargeException, which kills the process before the host can show the popup.
 */
internal object CarPlayHomeSnapshot {
    var bitmap: Bitmap? = null
}
