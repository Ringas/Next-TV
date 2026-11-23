// SPDX-License-Identifier: GPL-3.0-only
package com.nexttv.core.data

import java.util.Locale

/** Conservative identity: explicit tvg-id, otherwise exact normalized name, never strip 4K/+ or regional names. */
fun mergeChannelRoutes(channels:List<Channel>):List<Channel>{
 val merged=linkedMapOf<String,Channel>()
 channels.forEach { c ->
  val key=c.sourceId+"|"+if(c.tvgId.isNotBlank())"id:"+c.tvgId.trim().lowercase(Locale.ROOT)
   else "name:"+c.name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
  val prior=merged[key]
  merged[key]=if(prior==null)c.copy(routes=c.playbackRoutes.distinctBy{it.url to it.sourceId})
   else prior.copy(logo=prior.logo.ifBlank{c.logo},routes=(prior.playbackRoutes+c.playbackRoutes).distinctBy{it.url to it.sourceId})
 }
 return merged.values.toList()
}

/** One recovery pass per channel: each alternate once; a sole route gets one retry. */
class RouteRecovery {
 private val attempted=mutableSetOf<Int>()
 private var retriedSingle=false
 fun reset(index:Int){attempted.clear();attempted+=index;retriedSingle=false}
 fun next(count:Int,current:Int):Int? {
  if(count==1){if(retriedSingle)return null;retriedSingle=true;return 0}
  for(step in 1 until count){val i=(current+step)%count;if(attempted.add(i))return i}
  return null
 }
}
