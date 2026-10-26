package nl.woonkamer.kiosk
import org.junit.Assert.*
import org.junit.Test
class KioskPlanTest {
 @Test fun preparationAlwaysBlocks() {
  for(owner in listOf(false,true)) for(secure in listOf(false,true)) for(calls in listOf(false,true))
   assertNotNull(KioskPlan.blockReason(false,owner,secure,calls))
  assertEquals(BuildConfig.BUILD_TYPE == "kioskTrial", BuildConfig.KIOSK_ACTIVATION_ENABLED)
 }
 @Test fun futureActivationRequiresAllPrerequisites() {
  assertNotNull(KioskPlan.blockReason(true,false,true,true))
  assertNotNull(KioskPlan.blockReason(true,true,false,true))
  assertNotNull(KioskPlan.blockReason(true,true,true,false))
  assertNull(KioskPlan.blockReason(true,true,true,true))
 }
}
