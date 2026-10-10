package dev.stmedrano.harbor.parent.usage
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.profile.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UsageReporterHttpTest {
 private val binding=ChildBinding("22222222-2222-4222-8222-222222222222","33333333-3333-4333-8333-333333333333","44444444-4444-4444-8444-444444444444")
 @Test fun capturedBearerExactPersistedBytesAndIndependentP256ProofOverHttp()=runTest {
  val keys=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
  val signer=object:ChildSigner {
   override fun exists()=true
   override fun publicKeySpki()=Base64.getEncoder().encodeToString(keys.public.encoded)
   override fun sign(bytes:ByteArray)=childDerToP1363(Signature.getInstance("SHA256withECDSA").run{initSign(keys.private);update(bytes);sign()})
   override fun delete(){}
  }
  val raw=checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText()+" "
  val report=Json.decodeFromString<UsageReportV1>(raw)
  var mutableToken="captured-child"
  val captured=ChildAuthSession(ChildCredentials(mutableToken,"refresh",5000),"11111111-1111-4111-8111-111111111111",true)
  var calls=0
  val http=HttpClient(MockEngine { req->
   calls++
   assertEquals("Bearer captured-child",req.headers[HttpHeaders.Authorization])
   val body=(req.body as ByteArrayContent).bytes()
   assertArrayEquals(raw.toByteArray(),body)
   val operation=req.url.encodedPath.substringAfterLast('/')
   assertEquals("report-device-usage",operation)
   val proof=canonicalChildProof("POST",operation,binding.deviceId,body,1000L,"fresh-$calls")
   val signature=Base64.getDecoder().decode(req.headers["X-Harbor-Signature"])
   assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run{initVerify(keys.public);update(proof);verify(signature)})
   mutableToken="different-account"
   respond("{\"confirmed\":true,\"sequence\":${report.sequence},\"receivedAt\":\"2026-10-08T12:00:00Z\"}",HttpStatusCode.OK)
  })
  try {
   var nonce=0
   val api=ChildApi("https://parent-ci.invalid","sb_publishable_fixture",{request->
    val reply=http.post(request.url){request.headers.forEach{(k,v)->headers.append(k,v)};setBody(ByteArrayContent(request.body))}
    ChildReply(reply.status.value,reply.bodyAsText())
   },{1000},{"fresh-${++nonce}"},signer)
   val lease=ProfileLease(ProfileRole.CHILD,binding.deviceId,1)
   val reporter=UsageReporter({it==lease},{_,op,body->api.signedUsage(op,body,binding,captured)})
   val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
   val pending=PendingReport(report,raw,hash)
   reporter.upload(lease,pending);reporter.upload(lease,pending)
   assertEquals(2,calls);assertEquals("different-account",mutableToken)
  } finally {http.close()}
 }
}
