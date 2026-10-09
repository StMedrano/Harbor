package dev.stmedrano.harbor.parent.usage
import dev.stmedrano.harbor.parent.child.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class UsageHttpTransportTest {
 private fun request(url:String="https://parent-ci.invalid/functions/v1/report-device-usage",body:ByteArray="{}".toByteArray())=ChildRequest(url,"POST",mapOf("apikey" to "sb_publishable_fixture","Content-Type" to "application/json","Authorization" to "Bearer child","X-Harbor-Device-Id" to "device","X-Harbor-Timestamp" to "1000","X-Harbor-Nonce" to "nonce","X-Harbor-Signature" to "signature"),body)
 @Test fun exactRequestAndBoundedResponse()=runTest {
  var calls=0
  val client=HttpClient(MockEngine {req->calls++;assertEquals("Bearer child",req.headers[HttpHeaders.Authorization]);assertArrayEquals("{}".toByteArray(),(req.body as ByteArrayContent).bytes());respond("{\"confirmed\":true}",HttpStatusCode.OK)})
  try {val response=UsageHttpTransport(client).execute(request());assertEquals(200,response.status);assertEquals("{\"confirmed\":true}",response.body);assertEquals(1,calls)}finally{client.close()}
 }
 @Test fun foreignHostOperationOversizeOrAdditionalHeadersNeverReachHttp()=runTest {
  var calls=0;val client=HttpClient(MockEngine {calls++;error("unexpected request")})
  try {
   val transport=UsageHttpTransport(client)
   for(bad in listOf(request("https://unassigned.invalid/functions/v1/report-device-usage"),request("https://parent-ci.invalid/functions/v1/revoke-device"),request(body=ByteArray(1048577)),request().copy(headers=request().headers+("unrecognized" to "no")))) {
    try{transport.execute(bad);fail("invalid transport allowed")}catch(_:IllegalArgumentException){}
   }
   assertEquals(0,calls)
  }finally{client.close()}
 }
 @Test fun cancelledHttpWorkCannotProduceAReply()=runTest {
  val entered=CompletableDeferred<Unit>();var cancelled=false
  val client=HttpClient(MockEngine {entered.complete(Unit);try{awaitCancellation()}finally{cancelled=true}})
  try {
   val work=async{UsageHttpTransport(client).execute(request())};entered.await();work.cancelAndJoin()
   assertTrue(work.isCancelled);assertTrue(cancelled)
  }finally{client.close()}
 }
}
