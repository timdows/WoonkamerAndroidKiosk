plugins { alias(libs.plugins.android.application) }
android {
 namespace = "nl.woonkamer.kiosk"
 compileSdk = 37
 defaultConfig {
  applicationId = "nl.woonkamer.kiosk"
  minSdk = 28
  targetSdk = 36
  versionCode = 2
  versionName = "0.2-preparation"
  testInstrumentationRunner = providers.gradleProperty("kioskTestRunner").getOrElse("nl.woonkamer.kiosk.SmokeInstrumentation")
  buildConfigField("boolean", "KIOSK_ACTIVATION_ENABLED", "false")
 }
 buildTypes {
  create("kioskTrial") {
   initWith(getByName("debug"))
   signingConfig = signingConfigs.getByName("debug")
   versionNameSuffix = "-trial"
   buildConfigField("boolean", "KIOSK_ACTIVATION_ENABLED", "true")
   resValue("string", "app_name", "WoonkamerKiosk proef")
  }
 }
 buildFeatures { buildConfig = true; resValues = true }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
}
dependencies { testImplementation(libs.junit) }
