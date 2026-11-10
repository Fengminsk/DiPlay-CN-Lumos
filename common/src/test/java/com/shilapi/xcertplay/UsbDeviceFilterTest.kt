package com.shilapi.xcertplay

import android.content.ComponentName
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.link.IccoaUsbAttachActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbDeviceFilterTest {
    @Test
    fun iphoneProductsMatchTheActivityAttachFilter() {
        for (productId in listOf(0x12a8, 0x12a9, 0xffff)) {
            assertTrue(
                "Apple product 0x${productId.toString(16)} must match the attach filter",
                matchesAttachFilter(IphoneUsbMatcher.APPLE_VENDOR_ID, productId),
            )
        }
    }

    @Test
    fun ch341StillMatchesOnlyItsConfiguredProduct() {
        assertTrue(matchesAttachFilter(0x1a86, 0x5512))
        assertFalse(matchesAttachFilter(0x1a86, 0x5513))
    }

    @Test
    fun unrelatedUsbVendorsDoNotMatch() {
        assertFalse(matchesAttachFilter(0x1234, 0x12a8))
        assertFalse(matchesAttachFilter(0x1234, 0x5512))
    }

    @Test
    fun iccoaFilterMatchesThePhoneBeforeAndAfterAoaReenumeration() {
        assertTrue(matchesAttachFilter(0x22d9, 0x2769, IccoaUsbAttachActivity::class.java))
        for (productId in 0x2d00..0x2d05) {
            assertTrue(matchesAttachFilter(0x18d1, productId, IccoaUsbAttachActivity::class.java))
        }
        assertFalse(matchesAttachFilter(0x05ac, 0x12a8, IccoaUsbAttachActivity::class.java))
    }

    private fun matchesAttachFilter(
        vendorId: Int,
        productId: Int,
        activityClass: Class<out Activity> = DiPlayActivity::class.java,
    ): Boolean {
        val context = RuntimeEnvironment.getApplication()
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, activityClass),
            PackageManager.GET_META_DATA,
        )
        val device = Mockito.mock(UsbDevice::class.java)
        Mockito.`when`(device.vendorId).thenReturn(vendorId)
        Mockito.`when`(device.productId).thenReturn(productId)
        val filterClass = Class.forName("android.hardware.usb.DeviceFilter")
        val read = filterClass.getMethod("read", XmlPullParser::class.java)
        val matches = filterClass.getMethod("matches", UsbDevice::class.java)
        activity.loadXmlMetaData(context.packageManager, UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .use { parser ->
                requireNotNull(parser)
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "usb-device") {
                        val filter = read.invoke(null, parser)
                        if (matches.invoke(filter, device) as Boolean) return true
                    }
                    parser.next()
                }
            }
        return false
    }
}
