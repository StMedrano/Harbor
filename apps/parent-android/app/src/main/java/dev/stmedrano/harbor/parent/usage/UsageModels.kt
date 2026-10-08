package dev.stmedrano.harbor.parent.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class UsagePermissionState { @SerialName("granted") GRANTED, @SerialName("denied") DENIED, @SerialName("unavailable") UNAVAILABLE }
@Serializable enum class InventoryStatus { @SerialName("complete") COMPLETE, @SerialName("truncated") TRUNCATED, @SerialName("unavailable") UNAVAILABLE }
@Serializable enum class UsageQuality { @SerialName("partial") PARTIAL, @SerialName("observed") OBSERVED, @SerialName("unavailable") UNAVAILABLE }
@Serializable data class UsageInventoryApp(val packageName:String,val label:String)
@Serializable data class UsageApp(val packageName:String,val foregroundMs:Long)
@Serializable data class UsageDay(val localDate:String,val startAt:String,val endAt:String,val observedThrough:String,val coverageStart:String?,val quality:UsageQuality,val totalMs:Long?,val apps:List<UsageApp>)
@Serializable data class UsageReportV1(val version:Int,val epochId:String,val sequence:Long,val observedAt:String,val zoneId:String,val usagePermission:UsagePermissionState,val inventoryStatus:InventoryStatus,val inventory:List<UsageInventoryApp>,val days:List<UsageDay>)
@Serializable data class ClearUsageV1(val version:Int,val epochId:String,val sequence:Long)
@Serializable data class UsageWriteReplyV1(val confirmed:Boolean,val sequence:Long,val receivedAt:String)
@Serializable enum class UsageReadState { @SerialName("available") AVAILABLE, @SerialName("none") NONE, @SerialName("expired") EXPIRED }
@Serializable data class UsageReadReplyV1(val state:UsageReadState,val report:UsageReportV1?,val receivedAt:String?)
@Serializable data class UsageCheckpointRequestV1(val version:Int)
@Serializable data class UsageCheckpointReplyV1(val sequence:Long,val epochId:String?)
fun decodeUsageReport(raw:String,nowMs:Long):UsageReportV1 { require(raw.toByteArray(Charsets.UTF_8).size<=MAX_USAGE_BYTES); return validateUsageReport(Json.decodeFromString<UsageReportV1>(raw),nowMs) }
fun validateUsageReport(value:UsageReportV1,nowMs:Long):UsageReportV1 = try { checkReport(value,nowMs) } catch (e:java.time.DateTimeException) { throw IllegalArgumentException("Invalid usage date or zone",e) }
fun decodeUsageCheckpoint(raw:String):UsageCheckpointReplyV1 { val r=Json.decodeFromString<UsageCheckpointReplyV1>(raw); checkedSequence(r.sequence,0); if(r.sequence==0L)require(r.epochId==null) else require(r.epochId!=null&&usageUuid.matches(r.epochId)); return r }

private const val MAX_USAGE_BYTES = 1_048_576
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val usageUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val usagePackage = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val usageInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$")
private fun checkedText(value:String,max:Int) {
    require(value.isNotEmpty() && value.codePointCount(0,value.length)<=max && value.none { it.code<32 || it.code in 127..159 })
}
private fun checkedPackage(value:String) { checkedText(value,255);require(usagePackage.matches(value)) }
private fun checkedSequence(value:Long,min:Long=1) { require(value in min..MAX_SAFE_INTEGER) }
private fun instantMs(value:String):Long {
    require(usageInstant.matches(value)); val i=java.time.Instant.parse(value)
    require(i.toString().replace(".000Z","Z")==value.replace(".000Z","Z"));return i.toEpochMilli()
}
private fun checkReport(value:UsageReportV1,nowMs:Long):UsageReportV1 {
    require(Json.encodeToString(UsageReportV1.serializer(),value).toByteArray(Charsets.UTF_8).size<=MAX_USAGE_BYTES)
    require(value.version==1&&usageUuid.matches(value.epochId)); checkedSequence(value.sequence)
    val observed=instantMs(value.observedAt);require(observed-nowMs<=300000)
    checkedText(value.zoneId,100); val zone=java.time.ZoneId.of(value.zoneId)
    fun dayAt(ms:Long)=java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today=dayAt(observed)
    require(value.inventory.size<=500&&value.inventory.map{it.packageName}.distinct().size==value.inventory.size)
    value.inventory.forEach {checkedPackage(it.packageName);checkedText(it.label,200)}
    if(value.inventoryStatus==InventoryStatus.UNAVAILABLE)require(value.inventory.isEmpty())
    require(value.days.size<=7&&value.days.map{it.localDate}.distinct().size==value.days.size)
    require(value.days.sumOf{it.apps.size}<=3500)
    value.days.forEach {d->
        val day=java.time.LocalDate.parse(d.localDate); require(day.toString()==d.localDate)
        val age=java.time.temporal.ChronoUnit.DAYS.between(day,today);require(age in 0..6)
        val start=instantMs(d.startAt);val end=instantMs(d.endAt);val through=instantMs(d.observedThrough)
        require(start<end&&through in start..end&&through<=observed)
        require(dayAt(start)==day&&dayAt(start-1)!=day&&dayAt(end-1)==day&&dayAt(end)==day.plusDays(1))
        if(d.quality==UsageQuality.UNAVAILABLE||d.coverageStart==null) {
            require(d.coverageStart==null&&d.totalMs==null&&d.apps.isEmpty()&&d.quality!=UsageQuality.OBSERVED)
        } else {
            val coverage=instantMs(d.coverageStart);require(coverage in start..through)
            if(d.quality==UsageQuality.OBSERVED)require(coverage==start)
            val elapsed=through-coverage;require(d.totalMs!=null&&d.totalMs in 0..elapsed)
            require(d.apps.map{it.packageName}.distinct().size==d.apps.size)
            d.apps.forEach {checkedPackage(it.packageName);require(it.foregroundMs in 0..elapsed)}
        }
    }
    return value
}
fun decodeClearUsage(raw:String):ClearUsageV1 { require(raw.toByteArray(Charsets.UTF_8).size<=MAX_USAGE_BYTES); val r=Json.decodeFromString<ClearUsageV1>(raw); require(r.version==1&&usageUuid.matches(r.epochId));checkedSequence(r.sequence);return r }
fun decodeUsageCheckpointRequest(raw:String):UsageCheckpointRequestV1 { val r=Json.decodeFromString<UsageCheckpointRequestV1>(raw);require(r.version==1);return r }
