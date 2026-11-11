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
 * CarPlay needs Apple's vendor id. ICCOA's wired path uses AOA, so the classifier accepts
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

    /** Only for an explicit wired ICCOA tap when this is the sole attached USB device. */
    fun possibleChargingOnlyAndroid(signature: UsbPhoneSignature): Boolean =
        signature.vendorId != APPLE_VENDOR_ID &&
            classify(signature) == PhoneKind.UNKNOWN &&
            signature.interfaces.any { it.interfaceClass == CLASS_VENDOR_SPECIFIC }

    private fun UsbPhoneSignature.UsbInterfaceSignature.isAdb(): Boolean =
        interfaceClass == CLASS_VENDOR_SPECIFIC && subClass == ADB_SUBCLASS && protocol == ADB_PROTOCOL
}

/**
 * Turns "this phone is attached" into one action.
 *
 * Routing follows the phone, not the saved preference: an iPhone always starts CarPlay and an
 * Android phone always starts ICCOA. The two-way setting decides what the home screen's buttons
 * drive, it is not a gate that makes a plugged phone do nothing.
 */
object PhoneLinkPolicy {
    fun decide(kind: PhoneKind): LinkAction = when (kind) {
        PhoneKind.IPHONE -> LinkAction.START_CARPLAY
        PhoneKind.ANDROID -> LinkAction.START_ICCOA
        PhoneKind.UNKNOWN -> LinkAction.IGNORE
    }

    /** Whether an attached device should prompt for USB permission. */
    fun needsUsbAccess(kind: PhoneKind, source: ProjectionSource): Boolean =
        kind == PhoneKind.ANDROID
}
