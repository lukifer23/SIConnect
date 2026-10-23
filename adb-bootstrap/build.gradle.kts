plugins { id("com.android.library") }
android {
    namespace = "dev.siconnect.adbbootstrap"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 31 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")
    testImplementation("junit:junit:4.13.2")
}
