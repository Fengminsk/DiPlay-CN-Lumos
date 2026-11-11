package com.shilapi.xcertplay.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two rules the auto-launch path depends on: what counts as an iPhone vs an Android
 * phone on the USB bus, and which engine an attached phone starts.
 */
class PhoneLinkPolicyTest {

    private fun signature(
        vendorId: Int,
        productId: Int = 0,
        vararg interfaces: Triple<Int, Int, Int>,
    ) = UsbPhoneSignature(
        vendorId = vendorId,
        productId = productId,
        interfaces = interfaces.map { (cls, sub, proto) ->
            UsbPhoneSignature.UsbInterfaceSignature(cls, sub, proto)
        },
    )

    @Test
    fun appleVendorIsAnIphoneWhateverItExposes() {
        assertEquals(
            PhoneKind.IPHONE,
            UsbPhoneClassifier.classify(signature(UsbPhoneClassifier.APPLE_VENDOR_ID, 0x12a8)),
        )
    }

    @Test
    fun googleAccessoryModeIsAnAndroidPhone() {
        assertEquals(
            PhoneKind.ANDROID,
            UsbPhoneClassifier.classify(
                signature(UsbPhoneClassifier.GOOGLE_VENDOR_ID, 0x2d01, Triple(0xff, 0xff, 0x00)),
            ),
        )
    }

    @Test
    fun adbInterfaceIsAnAndroidPhone() {
        // A vendor phone in MTP mode with developer options on: vendor-specific ADB interface.
        assertEquals(
            PhoneKind.ANDROID,
            UsbPhoneClassifier.classify(
                signature(0x2717, 0x1234, Triple(0x06, 0x01, 0x01), Triple(0xff, 0x42, 0x01)),
            ),
        )
    }

    @Test
    fun mtpOrPtpInterfaceIsAnAndroidPhone() {
        assertEquals(
            PhoneKind.ANDROID,
            UsbPhoneClassifier.classify(signature(0x04e8, 0x6860, Triple(0x06, 0x01, 0x01))),
        )
    }

    @Test
    fun massStorageStickIsNotAPhone() {
        assertEquals(
            PhoneKind.UNKNOWN,
            UsbPhoneClassifier.classify(signature(0x0781, 0x5567, Triple(0x08, 0x06, 0x50))),
        )
    }

    @Test
    fun deviceWithoutInterfacesIsNotAPhone() {
        assertEquals(PhoneKind.UNKNOWN, UsbPhoneClassifier.classify(signature(0x1234)))
    }

    @Test
    fun soleChargingOnlyVendorInterfaceCanBeProbedAfterAnExplicitWiredTap() {
        assertTrue(UsbPhoneClassifier.possibleChargingOnlyAndroid(
            signature(0x22d9, 0x1234, Triple(0xff, 0, 0))))
        assertFalse(UsbPhoneClassifier.possibleChargingOnlyAndroid(
            signature(0x0781, 0x5567, Triple(0x08, 0x06, 0x50))))
        assertFalse(UsbPhoneClassifier.possibleChargingOnlyAndroid(
            signature(UsbPhoneClassifier.APPLE_VENDOR_ID, 0x12a8, Triple(0xff, 0, 0))))
    }

    @Test
    fun anIphoneAlwaysStartsCarPlay() {
        assertEquals(LinkAction.START_CARPLAY, PhoneLinkPolicy.decide(PhoneKind.IPHONE))
    }

    @Test
    fun anAndroidPhoneAlwaysStartsIccoa() {
        assertEquals(LinkAction.START_ICCOA, PhoneLinkPolicy.decide(PhoneKind.ANDROID))
    }

    @Test
    fun unrecognisedDeviceNeverStartsAnEngine() {
        assertEquals(LinkAction.IGNORE, PhoneLinkPolicy.decide(PhoneKind.UNKNOWN))
    }

    @Test
    fun onlyAndroidPhonesAreAskedForUsbAccess() {
        // An iPhone's cable belongs to CarPlay's own USB flow.
        assertTrue(PhoneLinkPolicy.needsUsbAccess(PhoneKind.ANDROID, ProjectionSource.ICCOA))
        assertFalse(PhoneLinkPolicy.needsUsbAccess(PhoneKind.IPHONE, ProjectionSource.CARPLAY))
        assertFalse(PhoneLinkPolicy.needsUsbAccess(PhoneKind.IPHONE, ProjectionSource.ICCOA))
    }

    @Test
    fun aFailedWiredAttemptCanSwitchToWirelessWithoutRestartingTheApp() {
        assertTrue(PhoneLinkRouter.shouldStartIccoa(
            started = true, connected = false, currentWiredOnly = true,
            requestedWiredOnly = false, explicitRetry = true))
        assertTrue(PhoneLinkRouter.shouldStartIccoa(
            started = true, connected = false, currentWiredOnly = false,
            requestedWiredOnly = true, explicitRetry = true))
        assertTrue(PhoneLinkRouter.shouldStartIccoa(
            started = true, connected = false, currentWiredOnly = false,
            requestedWiredOnly = false, explicitRetry = true))
    }

    @Test
    fun aRepeatedUsbAttachDoesNotRestartAnActiveIccoaSession() {
        assertFalse(PhoneLinkRouter.shouldStartIccoa(
            started = true, connected = true, currentWiredOnly = false,
            requestedWiredOnly = true, explicitRetry = false))
        assertFalse(PhoneLinkRouter.shouldStartIccoa(
            started = true, connected = false, currentWiredOnly = true,
            requestedWiredOnly = true, explicitRetry = false))
    }
}
