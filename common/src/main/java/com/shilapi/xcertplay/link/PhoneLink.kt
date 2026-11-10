package com.shilapi.xcertplay.link

/**
 * Which projection engine a connected phone should drive.
 *
 * The value is persisted, so the constants are part of the on-disk format.
 */
enum class ProjectionSource {
    /** Apple CarPlay over USB or the car hotspot. */
    CARPLAY,

    /** ICCOA CarLink (Xiaomi CarWith) over BLE plus Wi-Fi. */
    ICCOA,
}

/** What an attached USB device looks like, reduced to the fields the classifier reads. */
data class UsbPhoneSignature(
    val vendorId: Int,
    val productId: Int,
    val interfaces: List<UsbInterfaceSignature>,
) {
    data class UsbInterfaceSignature(
        val interfaceClass: Int,
        val subClass: Int,
        val protocol: Int,
    )
}

enum class PhoneKind {
    IPHONE,
    ANDROID,
    UNKNOWN,
}

enum class LinkAction {
    START_CARPLAY,
    START_ICCOA,
    IGNORE,
}

/**
 * Classifies an attached USB device as an iPhone, an Android phone, or something else.
 *
 * CarPlay needs Apple's vendor id. ICCOA cannot open a USB link on the car side, so an Android
 * phone is only recognised here to *trigger* the wireless flow — the classifier therefore accepts
 * Android's two usual USB personalities (AOA accessory mode, and the plain MTP/PTP device it
 * presents before switching), plus an ADB interface when developer options are on.
 */
object UsbPhoneClassifier {
    const val APPLE_VENDOR_ID = 0x05AC
    const val GOOGLE_VENDOR_ID = 0x18D1

    private const val CLASS_STILL_IMAGE = 0x06
    private const val CLASS_VENDOR_SPECIFIC = 0xFF
    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01

    /** AOA accessory ids Google hands out after the accessory handshake. */
    private val AOA_PRODUCTS = setOf(0x2D00, 0x2D01, 0x2D02, 0x2D03, 0x2D04, 0x2D05)

    fun classify(signature: UsbPhoneSignature): PhoneKind {
        if (signature.vendorId == APPLE_VENDOR_ID) return PhoneKind.IPHONE
        if (signature.vendorId == GOOGLE_VENDOR_ID && signature.productId in AOA_PRODUCTS) {
            return PhoneKind.ANDROID
        }
        if (signature.interfaces.any { it.isAdb() }) return PhoneKind.ANDROID
        // MTP/PTP (still-image class, PTP protocol) is what an Android phone exposes before AOA.
        if (signature.interfaces.any {
                it.interfaceClass == CLASS_STILL_IMAGE && it.protocol == 1
            }) {
            return PhoneKind.ANDROID
        }
        return PhoneKind.UNKNOWN
    }

    private fun UsbPhoneSignature.UsbInterfaceSignature.isAdb(): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC && subClass == ADB_SUBCLASS && protocol == ADB_PROTOCOL
}

/**
 * Turns "this phone is attached" plus "the driver chose this projection source" into one action.
 *
 * The rule is deliberately conservative: a phone only launches the engine the driver picked. An
 * iPhone attached while ICCOA is selected does nothing rather than starting CarPlay behind the
 * driver's back — the UI is what tells them the choice and the phone disagree.
 */
object PhoneLinkPolicy {
    fun decide(kind: PhoneKind, source: ProjectionSource): LinkAction = when {
        kind == PhoneKind.IPHONE && source == ProjectionSource.CARPLAY -> LinkAction.START_CARPLAY
        kind == PhoneKind.ANDROID && source == ProjectionSource.ICCOA -> LinkAction.START_ICCOA
        else -> LinkAction.IGNORE
    }

    /**
     * Whether an attached device should prompt for USB permission.
     *
     * With ICCOA selected, any non-Apple device is a candidate: the SDK's AOA handshake switches a
     * phone out of whatever mode it started in, so a device we cannot classify yet may still work
     * once it may be opened. CarPlay has its own permission flow and is left alone.
     */
    fun needsUsbAccess(kind: PhoneKind, source: ProjectionSource): Boolean =
        source == ProjectionSource.ICCOA && kind != PhoneKind.IPHONE
}
