package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class CarButtonDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ShadowBuild.setBrand("Lynk")
        ShadowBuild.setManufacturer("Geely")
        shadowOf(context.packageManager).removePackage("com.byd.carsettings")
        shadowOf(context.packageManager).removePackage("com.byd.amapservice")
    }

    @Test fun genericVehicleUsesLocalizedLabelAndHouseIcon() {
        assertFalse(CarButtonDefaults.isByd(context))
        assertEquals("Return to Car", AirPlayPersistence.loadOemLabel(context))
        assertEquals(R.raw.ic_return_vehicle, CarButtonDefaults.iconResource(context))

        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        assertEquals("返回车辆", AirPlayPersistence.loadOemLabel(chinese))

        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("返回车辆", AirPlayPersistence.loadOemLabel(chinese))
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertEquals("My car", AirPlayPersistence.loadOemLabel(chinese))
    }

    @Test fun bydSystemPackageKeepsBydLabelAndIcon() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        assertTrue(CarButtonDefaults.isByd(context))
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
        assertEquals(R.raw.ic_car_home, CarButtonDefaults.iconResource(context))
    }

    @Test fun bydBuildBrandIsRecognizedWithoutABydPackage() {
        ShadowBuild.setBrand("BYD")
        assertTrue(CarButtonDefaults.isByd(context))
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
    }
}
