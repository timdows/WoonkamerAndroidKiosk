plugins { alias(libs.plugins.android.application) }
android {
 namespace = "nl.woonkamer.kiosk"
 compileSdk = 37
 defaultConfig {
  applicationId = "nl.woonkamer.kiosk"
  minSdk = 28
  targetSdk = 36
  versionCode = 6
  versionName = "0.6"
  testInstrumentationRunner = providers.gradleProperty("kioskTestRunner").getOrElse("nl.woonkamer.kiosk.SmokeInstrumentation")
  buildConfigField("boolean", "KIOSK_ACTIVATION_ENABLED", "false")
 }
 buildTypes {
  create("kioskTrial") {
   initWith(getByName("debug"))
   signingConfig = signingConfigs.getByName("debug")
   versionNameSuffix = "-trial"
   buildConfigField("boolean", "KIOSK_ACTIVATION_ENABLED", "true")
   resValue("string", "app_name", "Woonkamer")
  }
 }
 buildFeatures { buildConfig = true; resValues = true }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
}
dependencies { testImplementation(libs.junit) }
