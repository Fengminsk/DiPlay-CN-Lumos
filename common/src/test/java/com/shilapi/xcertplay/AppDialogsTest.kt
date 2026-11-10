package com.shilapi.xcertplay

import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppDialogsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AppAppearanceRuntime.resetForTest()
    }

    @Test fun customDialogUsesTheResolvedAppAppearance() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        assertTrue(context.appDialogBuilder().create().nightForTest)

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        assertFalse(context.appDialogBuilder().create().nightForTest)
    }

    @Test fun anExistingDialogKeepsItsAppearanceAfterThePreferenceChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val existing = context.appDialogBuilder().create()

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)

        assertFalse(existing.nightForTest)
        assertTrue(context.appDialogBuilder().create().nightForTest)
    }

    @Test fun dialogUsesTheOwnersCurrentAppearanceInsteadOfResolvingItAgain() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        val lightOwner = object : ContextWrapper(context), AppAppearanceOwner {
            override val currentAppNight = false
        }

        assertFalse(lightOwner.appDialogBuilder().create().nightForTest)
    }
}
