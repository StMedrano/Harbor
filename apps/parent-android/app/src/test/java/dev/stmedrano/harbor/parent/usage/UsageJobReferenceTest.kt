package dev.stmedrano.harbor.parent.usage
import dev.stmedrano.harbor.parent.profile.*
import org.junit.Assert.*
import org.junit.Test
class UsageJobReferenceTest {
 private val lease=ProfileLease(ProfileRole.CHILD,"22222222-2222-4222-8222-222222222222",7)
 @Test fun onlyReferencesRoundTripAndNoJobCollision(){
  val refs=usageJobReferences(lease)
  assertEquals(setOf("role","ownerId","generation"),refs.keys)
  assertEquals(lease,parseUsageJobReferences(refs))
  assertEquals(4103,UsageJobService.JOB_ID)
  assertNotEquals(dev.stmedrano.harbor.parent.notifications.ParentNotificationJob.JOB_ID,UsageJobService.JOB_ID)
 }
 @Test fun foreignRoleUnknownFieldAndInvalidGenerationRejected(){
  for(refs in listOf(mapOf("role" to "PARENT","ownerId" to lease.ownerId,"generation" to "7"),usageJobReferences(lease)+("token" to "forbidden"),usageJobReferences(lease)+("generation" to "-1"),usageJobReferences(lease)+("ownerId" to "invalid")))
   assertThrows(IllegalArgumentException::class.java){parseUsageJobReferences(refs)}
 }
 @Test fun setupOrParentCannotCreateUsageWork(){
  assertThrows(IllegalArgumentException::class.java){usageJobReferences(lease.copy(role=ProfileRole.PARENT))}
  assertThrows(IllegalArgumentException::class.java){usageJobReferences(lease.copy(generation=0))}
 }
}
