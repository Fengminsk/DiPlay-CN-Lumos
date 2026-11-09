plugins {
    id("com.android.library")
}

// ICCOA CarLink (Xiaomi CarWith) car-side engine, ported from MiAuto 0.2.0.
// The sources keep their original packages (com.miauto.app, com.andrerinas.openheadunit.*) so the
// ported files stay diffable against upstream; only the seams are adapted.
android {
    namespace = "com.miauto.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Compile against signature stubs; the real SDK arrives as an injected classesN.dex at package
    // time (see the iccoaDex task in :mobile). AGP cannot consume .dex dependencies directly.
    compileOnly(files("stubs/icar-sdk-stubs.jar"))
    // The SDK keeps its trust-device table in Room, and defines no androidx.room classes itself.
    implementation("androidx.room:room-runtime:2.4.3")
    implementation(libs.androidx.core.ktx)
}
