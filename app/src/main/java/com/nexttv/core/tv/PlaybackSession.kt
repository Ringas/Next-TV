// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nexttv.core.tv

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nexttv.resolveStream
import com.nexttv.core.App
import com.nexttv.core.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Each attempt owns its player/listener/job. Late callbacks cannot fail or retune a newer attempt. */
class PlaybackSession(private val context:Context,initial:Channel,private val scope:CoroutineScope){
 companion object { internal var active:PlaybackSession?=null;private set;internal var stallTimeoutMillis=25000L }
 val app=App.get()
 var current by mutableStateOf(initial);private set
 private val preloadPool=DataPreloadPool(context)
 var beforeRetune:(suspend ()->Unit)?=null
 private var retuneJob:Job?=null
 internal var surfaceHasFrame=false;private set
 private var requestedAutoplay=true
 private val decoderHistory=DecoderHistory(context)
 private val recoveryBudget=RecoveryBudget{android.os.SystemClock.elapsedRealtime()}
 private val videoFailedRoutes=mutableSetOf<Int>()
 private var manualDecoderMode by mutableStateOf("auto")
 private var manualDecoderMime:String?=null
 private var lastVideoFormat:Format?=null
 private var decoderPreferences=DecoderPreferences()
 private var decoderContext=""
 private val audioRecoveryAttempts=mutableSetOf<String>()
 private var activePlayer by mutableStateOf<ExoPlayer?>(null)
 val player:ExoPlayer get()=checkNotNull(activePlayer)
 var routeIndex by mutableIntStateOf(0);private set
 val routeCount get()=current.playbackRoutes.size
 var loading by mutableStateOf(false);private set
 var error by mutableStateOf("");private set
 var ready by mutableStateOf(false);private set
 var rendered by mutableStateOf(false);private set
 var tuneSerial by mutableIntStateOf(0);private set
 var aspect by mutableIntStateOf(0)
 var replay by mutableStateOf<Program?>(null);private set
 private val transferWindow=TransferRateWindow()
 private var rateSampleAt=android.os.SystemClock.elapsedRealtime()
 var bytesPerSecond by mutableLongStateOf(0);private set
 private var byteCount=0L;private var rateJob:Job?=null
 private val transfer=object:androidx.media3.datasource.TransferListener{override fun onTransferInitializing(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){};override fun onTransferStart(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){};override fun onBytesTransferred(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean,b:Int){if(n)synchronized(this@PlaybackSession){byteCount+=b}};override fun onTransferEnd(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){}}
 private val resources=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
 private val warmSlots get()=if(app.prefs.preloadMode==3)2 else if(app.prefs.preloadMode==0)0 else 1
 private val lastGoodRoutes=mutableMapOf<String,String>()
 private fun routeKey(channel:Channel,index:Int)=channel.playbackRoutes.getOrNull(index)?.let{"${it.sourceId}\n${it.url}\n${it.headers.toSortedMap()}"}.orEmpty()
 private val softwareAttempts=mutableSetOf<String>()
 private var tuneStarted=0L
 private var firstFrameMs=0L
 private var decoderName=""
 private var softwareDecoder=false
 private val recentFailures=java.util.ArrayDeque<String>()
 private fun recordFailure(value:String){if(recentFailures.size>=16)recentFailures.removeFirst();recentFailures.addLast("${current.name}[${routeIndex+1}] "+value.take(160))}
 private var audioDecoderName=""
 internal val videoDecoderLabel get()=decoderName
 internal val audioDecoderLabel get()=audioDecoderName
 private var audioSoftwareDecoder=false
 private var warmHits=0
 private var warmMisses=0
 private val unavailableWarmChannels=mutableMapOf<String,WarmFailure>()
 private data class WarmFailure(val routes:List<ChannelRoute>,val expiresAt:Long)
 private fun confirmedUnavailable(channel:Channel):Boolean {
  if(app.prefs.preloadMode==0)return false
  val failure=unavailableWarmChannels[channel.id]?:return false
  return failure.expiresAt>android.os.SystemClock.elapsedRealtime()&&failure.routes==channel.playbackRoutes
 }
 fun diagnostics()="warmSlots=$warmSlots prepared=${preloadPool.size()} readyIds=${preloadPool.readyIds()} confirmedWarmFailures=${unavailableWarmChannels.size} hits=$warmHits misses=$warmMisses firstFrameMs=$firstFrameMs softwareAttempts=${softwareAttempts.size} decoder=$decoderName software=$softwareDecoder audioDecoder=$audioDecoderName audioSoftware=$audioSoftwareDecoder pcm=${decoderPreferences.forcePcm} audioRetries=${audioRecoveryAttempts.size} failures=${recentFailures.joinToString(" | ")}"
 fun trimMemory(){warmJob?.cancel();clearWarm();unavailableWarmChannels.clear()}
 private fun clearWarm(){preloadPool.clear()}
 private var prepareJob:Job?=null;private var warmJob:Job?=null;private var retryJob:Job?=null;private var loadingJob:Job?=null;private var timeoutJob:Job?=null
 private var stableJob:Job?=null
 private var networkLost=false
 private val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
 private val networkCallback=object:android.net.ConnectivityManager.NetworkCallback(){
  override fun onLost(network:android.net.Network){networkLost=true}
  override fun onAvailable(network:android.net.Network){if(networkLost){networkLost=false;scope.launch{if(!closed&&foreground&&error.isNotBlank()){exhausted.clear();recoveryBudget.reset();videoFailedRoutes.clear();recovery.reset(routeIndex);replayRecovery.reset(replayRoutes().indexOf(routeIndex));pendingPosition=if(replay!=null)resumePosition else null;attempt(current,routeIndex)}}}}
 }
 private var direction=1;private var closed=false;private var recovering=false
 private var foreground=true;private var resumeMain=false;private var resumePosition=0L;private var pendingPosition:Long?=null
 private val recovery=RouteRecovery()
 private val replayRecovery=RouteRecovery()
 private fun replayRoutes()=current.playbackRoutes.indices.filter{replay?.let{p->replayAddress(current.playbackRoutes[it],p,System.currentTimeMillis())!=null}==true}
 private val exhausted=mutableSetOf<String>()
 private fun decoderKey(channel:Channel=current,index:Int=routeIndex)=channel.id+":"+routeKey(channel,index)+":"+(replay?.start?:0)
 private fun makePlayer():ExoPlayer {
  val historyRoute=if(decoderContext.isBlank())null else routeKey(current,routeIndex)
  val selector=androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
  val softwareMime=decoderPreferences.videoSoftwareMime
  if(softwareMime!=null)selector.setParameters(selector.buildUponParameters()
   .setMaxVideoSize(1920,1080).setMaxVideoFrameRate(if(softwareMime in setOf("video/hevc","video/av01","video/x-vnd.on2.vp9"))30 else 60)
   .setExceedVideoConstraintsIfNecessary(false))
  return ExoPlayer.Builder(context,DecoderCompatibility.renderers(context,decoderPreferences.copy(discouragedVideoNames=historyRoute?.let{decoderHistory.discouraged(it)}?:emptySet())){mime->historyRoute?.let{decoderHistory.preferred(it,mime)}}).setTrackSelector(selector)
   .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
   .setHandleAudioBecomingNoisy(true).setPlaybackLooper(preloadPool.looper).setLoadControl(preloadPool.loadControl).build()
 }
 private fun retryDecoder(preferences:DecoderPreferences){
  val autoplay=player.playWhenReady
  decoderPreferences=preferences;pendingPosition=if(replay!=null)player.currentPosition.coerceAtLeast(0)else null
  attempt(current,routeIndex,autoplay);if(!autoplay){timeoutJob?.cancel();recoveryBudget.pause()}
 }
 private fun trySoftwareVideo(format:Format):Boolean {
  val key=decoderKey()
  if(recoveryBudget.remaining(stallTimeoutMillis)<=0||manualDecoderMode!="auto"||softwareDecoder||decoderPreferences.videoSoftwareMime!=null||key in softwareAttempts)return false
  if(!softwareVideoBudget(format.sampleMimeType,format.width,format.height,format.frameRate,resources.memoryClass,Runtime.getRuntime().availableProcessors(),format.drmInitData!=null||format.cryptoType!=C.CRYPTO_TYPE_NONE)||!DecoderCompatibility.softwareSupports(context,format))return false
  softwareAttempts.add(key);retryDecoder(decoderPreferences.copy(videoSoftwareMime=format.sampleMimeType));return true
 }
 private fun tryDecoderRecovery(error:PlaybackException):Boolean {
  if(closed||!foreground||recovering)return false
  recoveryBudget.enter(current.id)
  val format=(error as? ExoPlaybackException)?.rendererFormat?:return false
  val mime=format.sampleMimeType?:return false
  val decode=error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
  if(mime.startsWith("video/")&&decode){
   decoderHistory.failure(routeKey(current,routeIndex),decoderName);videoFailedRoutes+=routeIndex
   val eligible=if(replay!=null)replayRoutes()else current.playbackRoutes.indices.toList()
   if(eligible.any{it !in videoFailedRoutes})return false
   return trySoftwareVideo(format)
  }
  if(!mime.startsWith("audio/"))return false
  val output=error.errorCode==PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED||error.errorCode==PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
  if(!decode&&!output)return false
  return tryAudioDecoder(format,output)
 }
 private fun tryAudioDecoder(format:Format,output:Boolean):Boolean {
  if(recoveryBudget.remaining(stallTimeoutMillis)<=0||format.drmInitData!=null||format.cryptoType!=C.CRYPTO_TYPE_NONE)return false
  val mime=format.sampleMimeType?:return false
  val key=decoderKey()
  if(output&&!decoderPreferences.forcePcm&&audioRecoveryAttempts.add(key+":pcm")){retryDecoder(decoderPreferences.copy(forcePcm=true));return true}
  if(mime in setOf("audio/mpeg-L1","audio/mpeg-L2")&&!decoderPreferences.preferMp2Software&&audioRecoveryAttempts.add(key+":mp2")){retryDecoder(decoderPreferences.copy(preferMp2Software=true));return true}
  if(!audioSoftwareDecoder&&decoderPreferences.audioSoftwareMime==null&&DecoderCompatibility.softwareSupports(context,format)&&audioRecoveryAttempts.add(key+":software")){retryDecoder(decoderPreferences.copy(audioSoftwareMime=mime));return true}
  return false
 }

 private fun listen(target:ExoPlayer,serial:Int)=object:Player.Listener {
  private fun valid()=target===player&&!closed&&serial==tuneSerial
  override fun onPlaybackStateChanged(state:Int){if(!valid())return;ready=state==Player.STATE_READY
   if(ready){if(rendered){timeoutJob?.cancel();recoveryBudget.pause()};loadingJob?.cancel();loading=false;if(!recovering&&target.playWhenReady)error=""}
   if(state==Player.STATE_BUFFERING){stableJob?.cancel();if(foreground&&target.playWhenReady)watch(serial)}
   if(ready&&rendered){stableJob?.cancel();stableJob=scope.launch{delay(30000);if(valid()&&target.isPlaying){exhausted.clear();recoveryBudget.reset();videoFailedRoutes.clear();recovery.reset(routeIndex);replayRecovery.reset(replayRoutes().indexOf(routeIndex))}}}
   if(state==Player.STATE_ENDED){if(replay!=null)returnLive()else failed("直播流已结束",serial)}
  }
  override fun onRenderedFirstFrame(){if(!valid())return;rendered=true;surfaceHasFrame=true;recoveryBudget.pause();lastVideoFormat=target.videoFormat;target.videoFormat?.let{decoderHistory.success(routeKey(current,routeIndex),it.sampleMimeType.orEmpty(),decoderName)};lastGoodRoutes[current.id]=routeKey(current,routeIndex);preloadPool.releaseBorrowed();firstFrameMs=android.os.SystemClock.elapsedRealtime()-tuneStarted;timeoutJob?.cancel();loadingJob?.cancel();loading=false;stableJob?.cancel();stableJob=scope.launch{delay(30000);if(valid()&&target.isPlaying){exhausted.clear();recoveryBudget.reset();videoFailedRoutes.clear();recovery.reset(routeIndex);replayRecovery.reset(replayRoutes().indexOf(routeIndex))}};warmJob?.cancel();warmJob=scope.launch{delay(500);warm()}}
  override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){if(!valid())return
   if(!playWhenReady){timeoutJob?.cancel();recoveryBudget.pause()}
   else if(foreground&&(!ready||!rendered)){recoveryBudget.enter(current.id);watch(serial)}
  }
  override fun onTracksChanged(tracks:Tracks){if(valid()){
   if(foreground&&!recovering){
    val video=unsupportedTrackFormat(tracks,C.TRACK_TYPE_VIDEO)
    if(video!=null){videoFailedRoutes+=routeIndex;if(!((if(replay!=null)replayRoutes()else current.playbackRoutes.indices.toList()).all{it in videoFailedRoutes}&&trySoftwareVideo(video))){recordFailure("UNSUPPORTED_VIDEO "+video.sampleMimeType);failed("当前设备暂无可用视频解码器",serial)};return}
    unsupportedAudioFormat(tracks)?.let{format->if(!tryAudioDecoder(format,false)){recordFailure("UNSUPPORTED_AUDIO "+format.sampleMimeType);failed("当前设备暂无可用音轨解码器",serial)}}
   }
  }}
  override fun onPlayerError(e:PlaybackException){if(valid()){
   val http=generateSequence<Throwable>(e){it.cause}.take(8).filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
   recordFailure(e.errorCodeName+(http?.let{" HTTP $it"}?:""))
   if(!tryDecoderRecovery(e))failed(e.message?:e.errorCodeName,serial)
  }}
 }
 init{runCatching{connectivity.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)};active=this;app.channels.onChannelsChanged={scope.launch{if(!closed){val route=current.playbackRoutes.getOrNull(routeIndex);val valid=app.channels.channels.firstOrNull{it.id==current.id||(it.sourceId==current.sourceId&&if(current.tvgId.isBlank())it.tvgId.isBlank()&&it.name==current.name else it.tvgId==current.tvgId)};if(route!=null&&app.channels.sources.none{it.id==route.sourceId&&it.enabled}){clearWarm();val next=valid?:app.channels.channels.firstOrNull();if(next!=null)tune(next)else{retryJob?.cancel();prepareJob?.cancel();timeoutJob?.cancel();player.stop();error="没有已启用的频道"}}else if(valid!=null){val index=valid.playbackRoutes.indexOfFirst{it.url==route?.url&&it.sourceId==route.sourceId};if(index>=0){current=valid;routeIndex=index}else tune(valid)}}}};tune(initial);rateJob=scope.launch{while(isActive){delay(1000);bytesPerSecond=synchronized(this@PlaybackSession){val now=android.os.SystemClock.elapsedRealtime();val b=byteCount;byteCount=0;val rate=transferWindow.add(b,now-rateSampleAt);rateSampleAt=now;rate}}}}
 private fun watch(serial:Int){
  timeoutJob?.cancel()
  if(rendered)recoveryBudget.reset()
  recoveryBudget.enter(current.id)
  val wait=minOf((stallTimeoutMillis/2).coerceAtLeast(1),12000L,recoveryBudget.remaining(stallTimeoutMillis))
  if(foreground)timeoutJob=scope.launch{delay(wait);if(serial==tuneSerial&&foreground&&(!ready||(!rendered&&player.currentTracks.groups.any{it.type==C.TRACK_TYPE_VIDEO}))&&player.playWhenReady){
   recordFailure("FIRST_FRAME_OR_BUFFER_TIMEOUT");failed("缓冲超时",serial)
  }}
 }
 fun applyPreloadSetting(){warmJob?.cancel();clearWarm();unavailableWarmChannels.clear();warmJob=scope.launch{warm()}}
 fun playReplay(channel:Channel,program:Program):Boolean{recoveryBudget.reset();videoFailedRoutes.clear();val i=channel.playbackRoutes.indexOfFirst{replayAddress(it,program,System.currentTimeMillis())!=null};if(i<0)return false;resumePosition=0;pendingPosition=null;replay=program;current=channel;replayRecovery.reset(replayRoutes().indexOf(i));exhausted.clear();recovery.reset(i);attempt(channel,i);return true}
 fun returnLive(){replay=null;tune(current)}
 private suspend fun source(channel:Channel,index:Int=0,measured:Boolean=true,onPreloadHttpFailure:((Exception)->Unit)?=null):androidx.media3.exoplayer.source.MediaSource{
  val route=channel.playbackRoutes[index]
  val src=app.channels.sources.firstOrNull{it.id==route.sourceId}
  val headers=playbackHeaders(src,app.prefs.defaultUserAgent,app.prefs.defaultReferer,route.headers)
  val ua=playbackUserAgent(headers,app.prefs.defaultUserAgent)
  require(com.nexttv.supportsHttpPlayback(route.url)){"当前播放引擎仅支持 HTTP/HTTPS，暂不支持该线路协议"}
  val resolved=resolveStream(replay?.let{replayAddress(route,it,System.currentTimeMillis())}?:route.url,headers)
  val http=DefaultHttpDataSource.Factory().setUserAgent(ua).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers).setConnectTimeoutMs(8000).setReadTimeoutMs(10000)
  val expectedReplay=replay?.start
  http.setTransferListener(object:androidx.media3.datasource.TransferListener {
   override fun onTransferInitializing(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){}
   override fun onTransferStart(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){}
   override fun onTransferEnd(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean){}
   override fun onBytesTransferred(s:androidx.media3.datasource.DataSource,d:androidx.media3.datasource.DataSpec,n:Boolean,bytes:Int){if(current.id==channel.id&&routeIndex==index&&replay?.start==expectedReplay)transfer.onBytesTransferred(s,d,n,bytes)}
  })
  val factory=DefaultMediaSourceFactory(http)
  if(!measured)factory.setLoadErrorHandlingPolicy(object:androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(){
   override fun getRetryDelayMsFor(info:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo):Long {
    if(!isConfirmedPreloadFailure(info.exception))return super.getRetryDelayMsFor(info)
    onPreloadHttpFailure?.invoke(info.exception)
    return C.TIME_UNSET
   }
  })
  return factory.createMediaSource(MediaItem.Builder().setMediaId("${channel.id}:$index:${System.nanoTime()}").setUri(resolved.url).setMimeType(resolved.mime).build())
 }
 fun tune(channel:Channel){direction=1;tuneChannel(channel)}
 private fun tuneChannel(channel:Channel){if(closed)return;recoveryBudget.reset();videoFailedRoutes.clear();if(channel.id!=current.id){manualDecoderMode="auto";manualDecoderMime=null;lastVideoFormat=null};recentFailures.clear();if(channel.id!=current.id){softwareAttempts.clear();audioRecoveryAttempts.clear()};replay=null;pendingPosition=null;resumePosition=0;exhausted.clear()
  val index=preferredRoute(channel)
  recovery.reset(index);attempt(channel,index)
 }
 private fun preferredRoute(channel:Channel):Int =
  preloadPool.routeIndex(channel.id)?.takeIf{it in channel.playbackRoutes.indices}
   ?:channel.playbackRoutes.indices.firstOrNull{routeKey(channel,it)==lastGoodRoutes[channel.id]}
   ?:channel.playbackRoutes.indices.firstOrNull{decoderHistory.hasSuccess(routeKey(channel,it))}
   ?:0
 fun chooseRoute(index:Int):Boolean{
  if(index !in current.playbackRoutes.indices||index==routeIndex)return false
  recoveryBudget.reset();videoFailedRoutes.clear()
  if(replay!=null&&replayAddress(current.playbackRoutes[index],replay!!,System.currentTimeMillis())==null)return false
  if(replay!=null){pendingPosition=player.currentPosition.coerceAtLeast(0);replayRecovery.reset(replayRoutes().indexOf(index))};exhausted.clear();recovery.reset(index);attempt(current,index);return true
 }
 fun switchRoute(delta:Int):Boolean{
  if(routeCount<2)return false
  val next=(1 until routeCount).map{(routeIndex+delta*it+routeCount*it)%routeCount}.firstOrNull{replay==null||replayAddress(current.playbackRoutes[it],replay!!,System.currentTimeMillis())!=null}?:return false
  return chooseRoute(next)
 }
 private fun attempt(channel:Channel,index:Int,autoplay:Boolean=true){
  if(closed)return
  retuneJob?.cancel();requestedAutoplay=autoplay
  timeoutJob?.cancel();retryJob?.cancel();prepareJob?.cancel();stableJob?.cancel()
  current=channel;routeIndex=index;ready=false;rendered=false;tuneSerial++
  if(beforeRetune!=null&&foreground){
   rendered=false
   retuneJob=scope.launch(start=CoroutineStart.UNDISPATCHED){beforeRetune?.invoke();ensureActive();if(!closed&&foreground)attemptPrepared(channel,index,autoplay)}
  }else attemptPrepared(channel,index,autoplay)
 }
 private fun attemptPrepared(channel:Channel,index:Int,autoplay:Boolean=true){
  if(closed)return
  if(!foreground){current=channel;routeIndex=index;return}
  recoveryBudget.enter(channel.id)
  val nextDecoderContext=decoderKey(channel,index)
  if(nextDecoderContext!=decoderContext){decoderContext=nextDecoderContext;decoderPreferences=modePreferences()}
  stableJob?.cancel();retryJob?.cancel();prepareJob?.cancel();warmJob?.cancel();loadingJob?.cancel();timeoutJob?.cancel();recovering=false
  synchronized(this){byteCount=0;transferWindow.clear();rateSampleAt=android.os.SystemClock.elapsedRealtime()};bytesPerSecond=0
  current=channel;routeIndex=index;app.prefs.lastChannelId=channel.id;error="";ready=false;rendered=false;loading=false
  val serial=tuneSerial
  tuneStarted=android.os.SystemClock.elapsedRealtime();firstFrameMs=0
  val old=activePlayer
  val cached=if(replay==null&&decoderPreferences.defaultSelection)preloadPool.take(channel.id,index)else null
  val target=makePlayer()
  activePlayer=target;surfaceHasFrame=false
  if(cached?.ready==true)warmHits++ else warmMisses++
  clearWarm()
  old?.release();preloadPool.releaseBorrowed();decoderName="";softwareDecoder=false;audioDecoderName="";audioSoftwareDecoder=false
  target.addAnalyticsListener(object:androidx.media3.exoplayer.analytics.AnalyticsListener {
   override fun onAudioDecoderInitialized(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long){
    if(target!==player||serial!=tuneSerial)return
    audioDecoderName=name
    audioSoftwareDecoder=name=="Mp2Decoder"||knownSoftwareDecoder(name,false)||target.audioFormat?.sampleMimeType?.let{mime->runCatching{androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime,false,false).firstOrNull{it.name==name}?.softwareOnly}.getOrNull()}==true
   }
   override fun onVideoDecoderInitialized(eventTime:androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long){
    if(target!==player||serial!=tuneSerial)return
    decoderName=name
    softwareDecoder=knownSoftwareDecoder(name,false)||target.videoFormat?.sampleMimeType?.let{mime->runCatching{androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime,false,false).firstOrNull{it.name==name}?.softwareOnly}.getOrNull()}?:name.startsWith("c2.android.")||name.startsWith("OMX.google.")
   }
  })
  target.addListener(listen(target,serial));target.volume=1f;target.playWhenReady=foreground&&autoplay
  loadingJob=scope.launch{delay(500);if(serial==tuneSerial&&!ready)loading=true}
  watch(serial)
  if(cached!=null){preloadPool.adopt(cached);target.setMediaSource(cached.source);target.prepare();target.playWhenReady=foreground&&autoplay;return}
  prepareJob=scope.launch{
   try{val media=source(channel,index);ensureActive();if(serial==tuneSerial&&!closed){val position=pendingPosition;pendingPosition=null;if(position!=null)target.setMediaSource(media,position)else target.setMediaSource(media);target.prepare();target.playWhenReady=foreground&&autoplay}}
   catch(e:CancellationException){throw e}catch(e:Exception){if(serial==tuneSerial&&!closed)failed(e.message?:"连接失败",serial)}
  }
 }
 private fun failed(message:String,serial:Int){
  if(closed||serial!=tuneSerial||recovering||!foreground)return
  stableJob?.cancel();recoveryBudget.enter(current.id)
  if(replay!=null){
   recovering=true;loadingJob?.cancel();timeoutJob?.cancel();loading=false
   resumePosition=player.currentPosition.coerceAtLeast(resumePosition);pendingPosition=resumePosition
   val eligible=replayRoutes();val next=if(recoveryBudget.remaining(stallTimeoutMillis)<=0)null else replayRecovery.next(eligible.size,eligible.indexOf(routeIndex))?.let{eligible[it]}
   if(next==null){error="回放暂不可用，可在快捷菜单中返回直播";return}
   val retry=next;retryJob=scope.launch{delay(1000);if(!closed&&serial==tuneSerial)attempt(current,retry)};return
  }
  recovering=true;ready=false;loadingJob?.cancel();timeoutJob?.cancel();loading=false
  val nextRoute=if(recoveryBudget.remaining(stallTimeoutMillis)<=0)null else recovery.next(routeCount,routeIndex)
  val channel=current
  val nextChannel=if(nextRoute==null){exhausted+=channel.id;val all=app.channels.channels;val at=all.indexOfFirst{it.id==channel.id};val remaining=PreloadPolicy.indices(all.size,at,direction).map(all::get).filterNot{it.id in exhausted};remaining.firstOrNull{!confirmedUnavailable(it)}?:remaining.firstOrNull()}else null
  if(nextRoute==null&&nextChannel==null){player.pause();error="暂无可用频道\n$message";return}
  retryJob=scope.launch{
   delay(if(nextRoute!=null&&routeCount==1)1000 else 300)
   if(closed||serial!=tuneSerial)return@launch
   if(nextRoute!=null)attempt(channel,nextRoute)
   else {recoveryBudget.reset();videoFailedRoutes.clear();manualDecoderMode="auto";manualDecoderMime=null;lastVideoFormat=null;val target=nextChannel!!;val index=preferredRoute(target);recovery.reset(index);attempt(target,index)}
  }
 }
 fun seekReplay(position:Long){if(replay==null)return;val duration=player.duration.takeIf{it>0}?:((replay!!.stop-replay!!.start).coerceAtLeast(1));player.seekTo(position.coerceIn(0,(duration-1000).coerceAtLeast(0)));resumePosition=player.currentPosition}
 fun zap(delta:Int) {
  val channels=app.channels.channels
  if(channels.isEmpty()||delta==0)return
  direction=if(delta<0)-1 else 1
  val index=channels.indexOfFirst{it.id==current.id}
  val candidates=PreloadPolicy.indices(channels.size,index,direction,PreloadPolicy.MAX_CHANNELS).map(channels::get)
  val target=candidates.firstOrNull{!confirmedUnavailable(it)}?:candidates.firstOrNull()
   ?:channels.getOrNull(index)?:channels.first()
  tuneChannel(target)
 }
 private suspend fun warm(){
  val mode=app.prefs.preloadMode
  if(mode==0||closed||!foreground||replay!=null)return
  val serial=tuneSerial
  while(currentCoroutineContext().isActive&&serial==tuneSerial&&!closed&&foreground&&replay==null&&app.prefs.preloadMode==mode){
   val channels=app.channels.channels
   val index=channels.indexOfFirst{it.id==current.id}
   if(channels.size<2||index<0)return
   val now=android.os.SystemClock.elapsedRealtime()
   unavailableWarmChannels.entries.removeAll{it.value.expiresAt<=now}
   clearWarm()
   coroutineScope {
    PreloadPolicy.directions(mode).forEachIndexed { slot, offset ->
     launch {
      withTimeoutOrNull(PreloadPolicy.DIRECTION_TIMEOUT_MS) {
       for(candidate in PreloadPolicy.indices(channels.size,index,offset,PreloadPolicy.MAX_CHANNELS)){
        if(warmChannel(channels[candidate],slot))break
       }
      }
     }
    }
   }
   delay(PreloadPolicy.refreshIntervalMillis(resources.memoryClass,resources.isLowRamDevice))
  }
 }
 private suspend fun warmChannel(channel:Channel,slot:Int):Boolean {
  val routes=channel.playbackRoutes
  val attempts=PreloadPolicy.routeAttempts(routes.size,preferredRoute(channel))
  var definitiveFailures=0
  for(index in attempts){
   var entry:DataPreloadPool.Entry?=null
   var ready=false
   try {
    withTimeoutOrNull(PreloadPolicy.ATTEMPT_TIMEOUT_MS) {
     if(!com.nexttv.supportsHttpPlayback(routes[index].url)){
      definitiveFailures++
      return@withTimeoutOrNull
     }
     val httpFailure=CompletableDeferred<Exception>()
     val media=source(channel,index,measured=false,onPreloadHttpFailure={httpFailure.complete(it)})
     ensureActive()
     val owned=preloadPool.add(channel.id,slot,media,index)
     val pending=owned?:preloadPool.existing(channel.id)?:return@withTimeoutOrNull
     entry=owned
     val error=select<Throwable?> {
      if(owned!=null)httpFailure.onAwait{it}
      pending.result.onAwait{it}
     }
     if(error==null)ready=true
     else if(pending.routeIndex==index&&isConfirmedPreloadFailure(error))definitiveFailures++
    }
    if(ready){
     unavailableWarmChannels.remove(channel.id)
     return true
    }
   } catch(e:CancellationException){
    throw e
   } catch(e:Exception){
    if(isConfirmedPreloadFailure(e))definitiveFailures++
   } finally {
    if(!ready)entry?.let(preloadPool::discard)
   }
  }
  if(attempts.isNotEmpty()&&attempts.distinct().size==routes.size&&definitiveFailures==attempts.size){
   unavailableWarmChannels[channel.id]=WarmFailure(routes,android.os.SystemClock.elapsedRealtime()+PreloadPolicy.FAILURE_TTL_MS)
  }
  return false
 }
 private fun isConfirmedPreloadFailure(error:Throwable):Boolean =
  generateSequence(error){it.cause}.take(12).any {
   val status=when(it){
    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException->it.responseCode
    is com.nexttv.StreamHttpStatusException->it.status
    else->null
   }
   status==404||status==410
  }
 data class DecoderChoice(val id:String,val label:String,val detail:String,val enabled:Boolean=true)
 val selectedDecoderId get()=manualDecoderMode
 private fun modePreferences()=DecoderPreferences(videoHardwareOnly=manualDecoderMode=="hardware",videoSoftwareMime=manualDecoderMime.takeIf{manualDecoderMode=="software"})
 fun decoderChoices():List<DecoderChoice>{
  val format=player.videoFormat?:lastVideoFormat
  val clear=format!=null&&format.drmInitData==null&&format.cryptoType==C.CRYPTO_TYPE_NONE
  val mime=format?.sampleMimeType
  val codecs=if(clear&&mime!=null)runCatching{androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime,false,false).filter{it.isFormatSupported(context,format)}}.getOrDefault(emptyList())else emptyList()
  val hardware=codecs.any{it.hardwareAccelerated&&!softwareCodec(it)}
  val software=clear&&format!=null&&softwareVideoBudget(mime,format.width,format.height,format.frameRate,resources.memoryClass,Runtime.getRuntime().availableProcessors(),false)&&codecs.any{softwareCodec(it)}
  return listOf(
   DecoderChoice("hardware","硬件解码",if(hardware)"使用硬件，降低处理器占用"else if(format==null)"加载视频后可选"else "当前视频暂不可用",hardware),
   DecoderChoice("software","软件解码",if(software)"硬解异常时可尝试"else if(format==null)"加载视频后可选"else "当前视频暂不可用",software)
  )
 }
 val decoderAutomaticDescription get()="优先硬解，按视频自动匹配"
 fun selectDecoder(id:String):Boolean{
  if(id!="auto"&&decoderChoices().none{it.id==id&&it.enabled})return false
  if(id==selectedDecoderId)return false
  val format=player.videoFormat?:lastVideoFormat
  manualDecoderMode=id;manualDecoderMime=if(id=="software")format?.sampleMimeType else null
  recoveryBudget.reset();videoFailedRoutes.clear();softwareAttempts.clear();audioRecoveryAttempts.clear();recovery.reset(routeIndex);replayRecovery.reset(replayRoutes().indexOf(routeIndex))
  retryDecoder(modePreferences());return true
 }
 data class AudioChoice(val id:String,val label:String,val detail:String,val selected:Boolean,val supported:Boolean,val group:Tracks.Group,val index:Int)
 fun audioChoices():List<AudioChoice> {
 val choices=buildList {
  player.currentTracks.groups.forEachIndexed{groupIndex,g->if(g.type==C.TRACK_TYPE_AUDIO)for(i in 0 until g.length){
   val f=g.getTrackFormat(i)
   val language=f.language?.takeIf{it.isNotBlank()&&it!="und"}?.let{java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE)}
   val label=com.nexttv.audioCodecLabel(f.sampleMimeType)?:"音轨 ${size+1}"
   val role=when{(f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO)!=0->"无障碍解说";(f.roleFlags and C.ROLE_FLAG_COMMENTARY)!=0->"解说";else->null}
   val detail=listOfNotNull(language?.takeIf{it.isNotBlank()&&it!=f.language},f.channelCount.takeIf{it>0}?.let{com.nexttv.audioChannelLabel(it)},role,if(!g.isTrackSupported(i))"当前设备暂不支持"else null).joinToString(" · ")
   add(AudioChoice("$groupIndex:$i",label,detail,g.isTrackSelected(i),g.isTrackSupported(i),g,i))
  }}
 }
 val counts=choices.groupingBy{it.label}.eachCount()
 val seen=mutableMapOf<String,Int>()
 return choices.map{choice->if((counts[choice.label]?:0)>1){val n=(seen[choice.label]?:0)+1;seen[choice.label]=n;choice.copy(label="${choice.label} $n")}else choice}
 }
 val automaticAudio:Boolean get()=player.trackSelectionParameters.overrides.values.none{it.type==C.TRACK_TYPE_AUDIO}
 fun selectAutomaticAudio(){player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()}
 fun selectAudio(group:Tracks.Group,index:Int){player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,listOf(index))).build()}
 fun setForeground(value:Boolean){if(foreground==value||closed)return;foreground=value
  if(!value){val pendingRetune=retuneJob?.isActive==true;retuneJob?.cancel();stableJob?.cancel();resumeMain=if(pendingRetune)requestedAutoplay else player.playWhenReady;if(!pendingRetune&&replay!=null&&player.currentPosition>0)resumePosition=player.currentPosition;if(LaunchPolicy.resetOnReturn(context))player.stop()else player.pause();prepareJob?.cancel();retryJob?.cancel();recovering=false;clearWarm();unavailableWarmChannels.clear();warmJob?.cancel();timeoutJob?.cancel()}
  else{recoveryBudget.reset();if(player.playbackState==Player.STATE_IDLE||player.playerError!=null||(!ready&&!rendered)){pendingPosition=if(replay!=null)resumePosition else null;attempt(current,routeIndex,resumeMain)}else if(resumeMain){player.play();if(!ready)watch(tuneSerial);warmJob=scope.launch{delay(500);warm()}}}
 }
 fun release(){retuneJob?.cancel();runCatching{connectivity.unregisterNetworkCallback(networkCallback)};stableJob?.cancel();if(active===this){active=null;app.channels.onChannelsChanged=null};closed=true;rateJob?.cancel();loadingJob?.cancel();timeoutJob?.cancel();prepareJob?.cancel();warmJob?.cancel();retryJob?.cancel();player.release();clearWarm();preloadPool.release()}
}
