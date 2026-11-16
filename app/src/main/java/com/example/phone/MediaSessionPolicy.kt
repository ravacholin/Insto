package com.example.phone

data class MediaSessionOption(val packageName:String,val operations:Set<MediaOp>)
sealed interface MediaResolution {
 data object Missing:MediaResolution
 data object Choose:MediaResolution
 data object Ambiguous:MediaResolution
 data object Unsupported:MediaResolution
 data class Ready(val packageName:String):MediaResolution
}
object MediaSessionPolicy {
 fun resolve(sessions:List<MediaSessionOption>,operation:MediaOp,packageName:String?):MediaResolution {
  if(sessions.isEmpty())return MediaResolution.Missing
  if(sessions.groupBy {it.packageName}.any {it.value.size>1})return MediaResolution.Ambiguous
  if(packageName==null && sessions.size>1)return MediaResolution.Choose
  val target=if(packageName!=null)sessions.singleOrNull {it.packageName==packageName} else sessions.single()
  if(target==null)return MediaResolution.Missing
  if(operation!=MediaOp.STATUS && operation !in target.operations)return MediaResolution.Unsupported
  return MediaResolution.Ready(target.packageName)
 }
}
