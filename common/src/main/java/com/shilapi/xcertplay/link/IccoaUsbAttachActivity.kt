package com.shilapi.xcertplay.link

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.shilapi.xcertplay.DiPlayActivity

/** Receives Android's persisted USB default grant without replacing an active projection page. */
class IccoaUsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PhoneLinkUsb.isWatching()) {
            startActivity(
                Intent(this, DiPlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}
