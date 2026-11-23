package com.nexttv
import com.nexttv.core.data.*
import com.nexttv.core.data.Channel
import org.junit.Assert.*
import org.junit.Test
class ChannelRoutesTest {
 private fun c(id:String,name:String="News",tvg:String="news",url:String="https://example.test/$id",source:String="a")=Channel(id,name,tvg,"","新闻",url,source)
 @Test fun identityMergesRoutesInOrderWithoutChangingFirstId(){val a=c("first");val b=c("second");val m=mergeChannelRoutes(listOf(a,b)).single();assertEquals("first",m.id);assertEquals(listOf(a.url,b.url),m.playbackRoutes.map{it.url});assertEquals(m,mergeChannelRoutes(listOf(m)).single())}
 @Test fun distinctIdsNeverMergeBySharedName(){assertEquals(2,mergeChannelRoutes(listOf(c("a",tvg="east"),c("b",tvg="west"))).size)}
 @Test fun absentIdUsesNormalizedNameButPreservesQualityAndPlus(){assertEquals(1,mergeChannelRoutes(listOf(c("a",name=" News ",tvg=""),c("b",name="news",tvg=""))).size);assertEquals(3,mergeChannelRoutes(listOf(c("a",tvg=""),c("b",name="News4K",tvg=""),c("c",name="News+",tvg=""))).size)}
 @Test fun deduplicateAddressButPreserveSourceConfiguration(){val a=c("a");assertEquals(1,mergeChannelRoutes(listOf(a,a)).single().playbackRoutes.size);assertEquals(2,mergeChannelRoutes(listOf(a,c("b",url=a.url,source="other"))).size)}
 @Test fun soleRouteRetriesExactlyOnce(){val r=RouteRecovery();r.reset(0);assertEquals(0,r.next(1,0));assertNull(r.next(1,0));r.reset(0);assertEquals(0,r.next(1,0))}
 @Test fun alternatesTryEachOnceAndManualChangeResets(){val r=RouteRecovery();r.reset(1);assertEquals(2,r.next(3,1));assertEquals(0,r.next(3,2));assertNull(r.next(3,0));r.reset(2);assertEquals(0,r.next(3,2))}
}
