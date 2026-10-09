package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.child.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readAvailable
import java.net.URI
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Usage-only transport; it does not replace existing child wire transports. */
class UsageHttpTransport(private val client:HttpClient) {
 suspend fun execute(request:ChildRequest):ChildReply {
  val uri=URI(request.url);val assigned=URI(BuildConfig.SUPABASE_URL)
  require(uri.scheme=="https"&&uri.host==assigned.host&&uri.port==assigned.port&&uri.userInfo==null&&uri.query==null&&uri.fragment==null)
  require(uri.path in setOf("/functions/v1/report-device-usage","/functions/v1/clear-device-usage","/functions/v1/get-device-usage-checkpoint"))
  require(request.method=="POST"&&request.body.size<=1048576)
  require(request.headers.keys==setOf("apikey","Content-Type","Authorization","X-Harbor-Device-Id","X-Harbor-Timestamp","X-Harbor-Nonce","X-Harbor-Signature"))
  return client.prepareRequest(request.url) {
   method=HttpMethod.Post
   request.headers.forEach{(key,value)->headers.append(key,value)}
   setBody(ByteArrayContent(request.body))
  }.execute {response->
   require((response.headers["Content-Length"]?.toLongOrNull()?:0)<=1048576)
   val input=response.bodyAsChannel();val bytes=ByteArrayOutputStream();val buffer=ByteArray(4096)
   while(true){val count=input.readAvailable(buffer,0,buffer.size);if(count<0)break
    if(bytes.size()+count>1048576)throw java.io.IOException("Usage response too large")
    bytes.write(buffer,0,count)
   }
   ChildReply(response.status.value,bytes.toByteArray().toString(Charsets.UTF_8))
  }
 }
 fun close(){client.close()}
 companion object {
  fun create():UsageHttpTransport {
   check(!BuildConfig.CI_FIXTURE){"Offline fixture cannot open a live usage transport"}
   return UsageHttpTransport(HttpClient(OkHttp) {
    followRedirects=false
    install(HttpTimeout){requestTimeoutMillis=15000;connectTimeoutMillis=5000;socketTimeoutMillis=5000}
    engine{config{followRedirects(false);followSslRedirects(false);callTimeout(15,TimeUnit.SECONDS);connectTimeout(5,TimeUnit.SECONDS);readTimeout(5,TimeUnit.SECONDS);writeTimeout(5,TimeUnit.SECONDS)}}
   })
  }
 }
}

