// SPDX-License-Identifier: GPL-3.0-only
// Next TV — Compose for TV implementation.
package com.nexttv.core.data

import com.nexttv.core.io.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 播放源仓库：负责订阅源的增删改、拉取、解析、本地缓存与合并后的频道列表。
 * 拉取失败时保留上一次的缓存，不会出现列表被清空的情况。
 */
class ChannelRepository(private val context: Context, private val prefs: Prefs) {
    var revision by androidx.compose.runtime.mutableIntStateOf(0);private set
    internal var onChannelsChanged:(()->Unit)?=null
    private val refreshMutex = Mutex()
    private val transport = SubscriptionTransport(context, prefs)
    @Volatile private var configVersion = 0L

    /** 合并后的频道（已过滤） */
    var channels: List<Channel> = emptyList()
        private set

    /** 分组顺序（按首次出现顺序，4K 专区固定排在最后） */
    var groups: List<String> = emptyList()
        private set

    var sources: List<Source> = emptyList()
        private set

    /** 是否存在被过滤设置隐藏的频道，用于给出准确的空列表提示 */
    val hasLoadedChannels: Boolean get() = rawChannels.isNotEmpty()

    private val channelsFile: File get() = File(context.filesDir, "channels.json")

    /** 合并与编号之前的原始频道列表 */
    private var rawChannels: List<Channel> = emptyList()

    // ---------------- 源管理 ----------------

    fun loadSources() {
        val json = prefs.sourcesJson
        if (json.isBlank()) {
            sources = emptyList()
            prefs.migrateLegacyEpg(emptySet())
            return
        }
        val parsed = runCatching { SubscriptionSettingsCodec.decode(json) }.getOrDefault(emptyList())
        // 老版本自带的内置演示源已经移除，这里顺手清掉遗留项，避免它一直报错
        val cleaned = parsed.filterNot { it.id == LEGACY_BUILTIN_ID || it.url.startsWith("asset://") }
        val enriched=cleaned.map{source->if(source.epgUrl.isNotBlank())source else {
            val value=runCatching{File(context.filesDir,"pl_${source.id}.m3u").takeIf{it.exists()&&it.length()<=ImportPolicy.PLAYLIST_BYTES}?.useLines{lines->M3uParser.parse(lines.take(16).firstOrNull{it.trimStart('\uFEFF',' ').startsWith("#EXTM3U")}.orEmpty(),source.id).epgUrl}.orEmpty()}.getOrDefault("")
            source.copy(epgUrl=if(value.isBlank())""else runCatching{java.net.URI(source.url).resolve(value).toString()}.getOrDefault(value))
        }}
        sources = enriched
        if(prefs.needsEpgMigration)prefs.migrateLegacyEpg(enriched.map{it.epgUrl}.filter{it.isNotBlank()}.toSet())
        if(enriched!=cleaned)saveSources()
        if (cleaned.size != parsed.size) saveSources()
    }

    fun saveSources() {
        prefs.sourcesJson = SubscriptionSettingsCodec.encode(sources)
    }

    @Synchronized fun upsertSource(source: Source) {
        configVersion++
        // A new library starts at 1, including libraries emptied by older app versions.
        if (sources.isEmpty()) prefs.channelNumbersJson = "{}"
        val idx = sources.indexOfFirst { it.id == source.id }
        val activationChanged = idx >= 0 && sources[idx].enabled != source.enabled
        sources = if (idx >= 0) {
            sources.toMutableList().also { it[idx] = source }
        } else {
            sources + source
        }
        saveSources()
        if (activationChanged) applyChannels(rawChannels, persist = false)
    }

    @Synchronized fun removeSource(id: String) {
        configVersion++
        sources = sources.filterNot { it.id == id }
        if (sources.isEmpty()) prefs.channelNumbersJson = "{}"
        saveSources()
        prefs.removeFavorites(rawChannels.filter{it.sourceId==id}.map{it.id}.toSet())
        applyChannels(rawChannels.filterNot{it.sourceId==id})
        File(context.filesDir, "pl_$id.m3u").delete()
    }

    // ---------------- 缓存 ----------------

    fun loadCache() {
        loadSources()
        val f = channelsFile
        if (!f.exists()&&!File(f.path+".bak").exists()) return
        runCatching {
            val list=ArrayList<Channel>()
            var legacy=false
            ImportBudget("频道缓存",10000).use{budget->
                android.util.JsonReader(BoundedJsonReader(budget.input(android.util.AtomicFile(f).openRead(),ImportPolicy.CHANNEL_CACHE_BYTES).reader(),ImportPolicy.CHANNEL_CACHE_RECORDS*3)).use{reader->
                    reader.beginArray()
                    while(reader.hasNext()){
                        budget.check();if(list.size>=ImportPolicy.CHANNEL_CACHE_RECORDS)throw ImportLimitException("频道缓存数量超过上限")
                        val fields=hashMapOf<String,String>();val headers=hashMapOf<String,String>();var number=0;var days:Int?=null;var hasCatchup=false
                        reader.beginObject();while(reader.hasNext()){
                            val key=reader.nextName()
                            when(key){
                                "number"->number=reader.nextInt()
                                "catchupDays"->if(reader.peek()==android.util.JsonToken.NULL)reader.nextNull()else days=reader.nextInt()
                                "headers"->{reader.beginObject();while(reader.hasNext()){val k=reader.nextName();headers[k]=reader.nextString();if(headers.size>100)throw ImportLimitException("频道请求头过多")};reader.endObject()}
                                "id","name","tvgId","logo","group","url","sourceId","catchup","catchupSource"->{if(key=="catchup")hasCatchup=true;fields[key]=if(reader.peek()==android.util.JsonToken.NULL){reader.nextNull();""}else reader.nextString()}
                                else->reader.skipValue()
                            }
                        };reader.endObject()
                        if(list.isEmpty())legacy=!hasCatchup
                        fun text(key:String)=fields[key].orEmpty()
                        require(text("id").isNotEmpty()&&text("url").isNotEmpty())
                        list.add(Channel(id=text("id"),name=text("name"),tvgId=text("tvgId"),logo=text("logo"),group=text("group"),url=text("url"),sourceId=text("sourceId"),headers=headers,number=number,catchup=text("catchup"),catchupSource=text("catchupSource"),catchupDays=days))
                    };reader.endArray()
                }
            }
            val migrated = if(legacy)sources.flatMap{source->readSourceCache(source.id).orEmpty()}else emptyList()
            applyChannels(migrated.ifEmpty{list}, persist = legacy)
        }
    }

    fun importLegacyCache(raw:String,url:String,favoriteUrls:Set<String>,lastUrl:String) {
        if(sources.isNotEmpty()||raw.isBlank()||url.isBlank())return
        val source=Source("nexttv-upgrade","家庭订阅源",url)
        val parsed=runCatching{M3uParser.parse(raw,source.id)}.getOrElse{return}
        upsertSource(source)
        raw.byteInputStream().use { writeSourceCache(source.id, it) }
        applyChannels(parsed.channels)
        parsed.epgUrl?.let{upsertSource(source.copy(epgUrl=it))}
        parsed.channels.filter{it.url in favoriteUrls}.forEach{if(!prefs.isFavorite(it.id))prefs.toggleFavorite(it.id)}
        parsed.channels.firstOrNull{it.url==lastUrl}?.let{prefs.lastChannelId=it.id}
    }

    @Synchronized
    internal fun rebuildChannels() = applyChannels(rawChannels, persist = false)

    private fun applyChannels(list: List<Channel>, persist: Boolean = true) {
        val canonical=list.map { c ->
            val id=M3uParser.stableChannelId(c.sourceId,c.name,c.tvgId)
            if(c.id!=id&&prefs.isFavorite(c.id)){if(!prefs.isFavorite(id))prefs.toggleFavorite(id);prefs.toggleFavorite(c.id)}
            if(prefs.lastChannelId==c.id)prefs.lastChannelId=id
            c.copy(id=id)
        }
        // Upgrade old IDs while old cached URL/name evidence is still available.
        rawChannels.forEach { old ->
            val id=M3uParser.stableChannelId(old.sourceId,old.name,old.tvgId)
            if(old.id!=id&&prefs.isFavorite(old.id)){if(!prefs.isFavorite(id))prefs.toggleFavorite(id);prefs.toggleFavorite(old.id)}
            if(prefs.lastChannelId==old.id)prefs.lastChannelId=id
        }
        rawChannels = canonical
        val knownSources=sources.associateBy{it.id}
        val merged = mergeChannelRoutes(canonical.filter { c -> knownSources[c.sourceId]?.enabled == true })
        val numbering=runCatching{org.json.JSONObject(prefs.channelNumbersJson.ifBlank{"{}"})}.getOrElse{org.json.JSONObject()}
        var nextNumber=(numbering.keys().asSequence().map{numbering.optInt(it)}.maxOrNull()?:0)+1
        merged.forEach{if(numbering.optInt(it.id)<=0)numbering.put(it.id,nextNumber++)}
        val numberingJson=numbering.toString();if(numberingJson!=prefs.channelNumbersJson)prefs.channelNumbersJson=numberingJson
        // Preserve favorites and resume when a formerly separate route becomes part of a channel.
        merged.forEach { channel ->
            if (channel.playbackRoutes.any { prefs.isFavorite(it.originalId) } && !prefs.isFavorite(channel.id)) prefs.toggleFavorite(channel.id)
            if (channel.playbackRoutes.any { it.originalId == prefs.lastChannelId }) prefs.lastChannelId = channel.id
        }
        val filtered = merged.map { c -> c.copy(number = numbering.getInt(c.id)) }

        channels = filtered
        val ordered = LinkedHashSet<String>()
        filtered.forEach { ordered.add(it.groupName) }
        val head = ordered.filter { it != M3uParser.GROUP_4K }
        groups = if (ordered.contains(M3uParser.GROUP_4K)) head + M3uParser.GROUP_4K else head
        if (persist) runCatching { saveCache(rawChannels) }
        revision++
        onChannelsChanged?.invoke()
    }

    private fun saveCache(records: List<Channel>) {
        ImportBudget("频道缓存",10000).use{budget->
            if(records.size>ImportPolicy.CHANNEL_CACHE_RECORDS)throw ImportLimitException("频道缓存数量超过上限")
            val atomic=android.util.AtomicFile(channelsFile);val output=atomic.startWrite()
            try{
                val writer=android.util.JsonWriter(java.io.OutputStreamWriter(budget.output(output,ImportPolicy.CHANNEL_CACHE_BYTES),Charsets.UTF_8));writer.beginArray()
                records.forEach{c->budget.check();writer.beginObject().name("id").value(c.id).name("name").value(c.name).name("tvgId").value(c.tvgId).name("logo").value(c.logo).name("group").value(c.group).name("url").value(c.url).name("sourceId").value(c.sourceId).name("number").value(c.number.toLong()).name("catchup").value(c.catchup).name("catchupSource").value(c.catchupSource)
                    if(c.catchupDays!=null)writer.name("catchupDays").value(c.catchupDays.toLong())
                    writer.name("headers").beginObject();c.headers.forEach{(k,v)->writer.name(k).value(v)};writer.endObject().endObject()}
                writer.endArray();writer.flush();budget.check();output.fd.sync();atomic.finishWrite(output)
                check(!File(channelsFile.path + ".new").exists()) { "频道缓存未提交" }
            }catch(e:Exception){atomic.failWrite(output);throw e}
        }
    }

    // ---------------- 刷新 ----------------

    data class RefreshResult(val ok: Int, val failed: Int, val updated: List<Source>, val epgUrl: String, val storageWarning: String = "")

    suspend fun refreshAll(onProgress: (String) -> Unit = {}): RefreshResult = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val snapshot = synchronized(this@ChannelRepository) {
                loadSources()
                RefreshSnapshot(configVersion, sources.toList(), rawChannels.groupBy(Channel::sourceId))
            }
            val staged = linkedMapOf<String, File>()
            try {
                val batch = prepareRefresh(snapshot, staged, onProgress)
                val job = currentCoroutineContext()
                synchronized(this@ChannelRepository) {
                    job.ensureActive()
                    publishRefresh(snapshot, batch, staged)
                }
            } catch (limit: ImportLimitException) {
                synchronized(this@ChannelRepository) {
                    if (snapshot.version != configVersion) return@synchronized supersededRefresh()
                    val message = limit.message ?: "导入超限，已保留原列表"
                    sources = sources.map { if (it.enabled) it.copy(lastError = message) else it }
                    saveSources()
                    RefreshResult(0, snapshot.sources.count(Source::enabled), sources, prefs.epgUrl)
                }
            } finally {
                staged.values.forEach { it.delete() }
                onProgress("")
            }
        }
    }

    private data class RefreshSnapshot(
        val version: Long,
        val sources: List<Source>,
        val previousBySource: Map<String, List<Channel>>
    )

    private data class PreparedRefresh(
        val sources: List<Source>,
        val channels: List<Channel>,
        val refreshed: Set<String>,
        val expiredFavorites: Set<String>,
        val failed: Int,
        val guide: String
    )

    private suspend fun prepareRefresh(
        snapshot: RefreshSnapshot,
        staged: MutableMap<String, File>,
        onProgress: (String) -> Unit
    ): PreparedRefresh {
        val channels = ArrayList<Channel>()
        val updated = ArrayList<Source>()
        val refreshed = linkedSetOf<String>()
        val started = System.nanoTime()
        var stagedBytes = 0L
        var failures = 0
        var guide = ""
        for (source in snapshot.sources) {
            currentCoroutineContext().ensureActive()
            val previous = snapshot.previousBySource[source.id].orEmpty()
            val selected = if (!source.enabled) {
                updated += source
                previous
            } else {
                onProgress("正在更新 ${source.name} …")
                val remaining = 90_000 - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) throw ImportLimitException("订阅刷新总耗时超过上限，已保留原列表")
                val result = fetchAndParse(source, minOf(remaining, ImportPolicy.IMPORT_MS))
                val file = result.staged
                if (file != null) {
                    staged[source.id] = file
                    stagedBytes += file.length()
                    if (stagedBytes > ImportPolicy.PLAYLIST_BYTES * 2) throw ImportLimitException("订阅文本总量超过上限，已保留原列表")
                    refreshed += source.id
                    val epg = result.epgUrl.orEmpty()
                    if (guide.isEmpty()) guide = epg
                    val resolvedGuide = if (epg.isBlank()) source.epgUrl else
                        runCatching { java.net.URI(source.url).resolve(epg).toString() }.getOrDefault(epg)
                    updated += source.copy(
                        channelCount = mergeChannelRoutes(result.channels).size,
                        lastUpdate = System.currentTimeMillis(), lastError = "", epgUrl = resolvedGuide
                    )
                    result.channels
                } else {
                    failures++
                    updated += source.copy(
                        lastError = result.error.ifBlank { "获取失败" }, lastUpdate = System.currentTimeMillis()
                    )
                    previous.ifEmpty { readSourceCache(source.id, source.url).orEmpty() }
                }
            }
            if (channels.size + selected.size > ImportPolicy.CHANNEL_CACHE_RECORDS) {
                throw ImportLimitException("所有订阅的频道/线路总量超过上限")
            }
            channels.addAll(selected)
        }

        val retained = channels.mapTo(hashSetOf()) { M3uParser.stableChannelId(it.sourceId, it.name, it.tvgId) }
        val expired = refreshed.asSequence().flatMap { snapshot.previousBySource[it].orEmpty().asSequence() }
            .map { M3uParser.stableChannelId(it.sourceId, it.name, it.tvgId) }
            .filterNot(retained::contains).toSet()
        return PreparedRefresh(updated, channels, refreshed, expired, failures, guide)
    }

    /** Called under the source lock after IO. A changed source invalidates the entire batch. */
    private fun publishRefresh(
        snapshot: RefreshSnapshot,
        batch: PreparedRefresh,
        staged: Map<String, File>
    ): RefreshResult {
        if (configVersion != snapshot.version) return supersededRefresh()
        try {
            saveCache(batch.channels)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return RefreshResult(0, snapshot.sources.count(Source::enabled), sources, prefs.epgUrl,
                "订阅更新失败：频道缓存无法写入，已保留原列表")
        }

        val unavailable = staged.count { (id, file) ->
            runCatching { file.inputStream().use { writeSourceCache(id, it) } }.getOrDefault(false).not()
        }
        sources = batch.sources
        saveSources()
        prefs.removeFavorites(batch.expiredFavorites)
        applyChannels(batch.channels, persist = false)
        val warning = if (unavailable == 0) "" else "已更新频道，${unavailable} 个订阅备份未能保存"
        return RefreshResult(batch.refreshed.size, batch.failed, sources, batch.guide, warning)
    }

    private fun supersededRefresh() = RefreshResult(0, 0, sources, prefs.epgUrl)

    private data class ParseOutcome(
        val channels: List<Channel>,
        val staged: File?,
        val error: String,
        val epgUrl: String?
    )

    private suspend fun fetchAndParse(src: Source,timeoutMs:Long=ImportPolicy.IMPORT_MS): ParseOutcome {
        val stage = File.createTempFile("playlist-", ".m3u", context.cacheDir)
        var keep = false
        return try {
            val job = currentCoroutineContext()[Job]
            ImportBudget("播放源", timeoutMs, cancelled = { job?.isActive == false }).use { budget ->
                transport.stage(src, stage, budget)
                val parsed = stage.bufferedReader().use { reader ->
                    M3uParser.parse(reader, src.id, src.url, budget::check)
                }
                keep = parsed.channels.isNotEmpty()
                ParseOutcome(parsed.channels, stage.takeIf { keep },
                    if (keep) "" else "解析到 0 个频道", parsed.epgUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ParseOutcome(emptyList(), null, e.message ?: "网络错误", null)
        } finally {
            if (!keep) stage.delete()
        }
    }

    private fun writeSourceCache(id: String, input: java.io.InputStream): Boolean =
        runCatching {
            ImportBudget("播放源缓存",10000).use { budget ->
                val cache=android.util.AtomicFile(File(context.filesDir,"pl_$id.m3u"))
                val stream=cache.startWrite()
                try {
                    input.copyTo(budget.output(stream,ImportPolicy.PLAYLIST_BYTES));budget.check();stream.fd.sync();cache.finishWrite(stream)
                } catch(t:Throwable){cache.failWrite(stream);throw t}
            }
        }.isSuccess

    private fun readSourceCache(id: String, base: String = sources.firstOrNull { it.id == id }?.url.orEmpty()): List<Channel>? {
        val f = File(context.filesDir, "pl_$id.m3u")
        if (!f.exists()&&!File(f.path+".bak").exists()) return null
        return runCatching {
            ImportBudget("播放源缓存", 10000).use { budget ->
                budget.input(android.util.AtomicFile(f).openRead(), ImportPolicy.PLAYLIST_BYTES)
                    .bufferedReader().use { M3uParser.parse(it, id, base, budget::check).channels }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ---------------- 查询 ----------------

    fun channelsIn(group: String?, favoritesOnly: Boolean, query: String): List<Channel> {
        val fav = if (favoritesOnly) prefs.favorites else null
        var list = channels
        if (group != null && group.isNotBlank() && group != GROUP_ALL) {
            list = list.filter { it.groupName == group }
        }
        if (fav != null) list = list.filter { fav.contains(it.id) }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            list = list.filter {
                it.name.lowercase(Locale.ROOT).contains(q) ||
                    it.groupName.lowercase(Locale.ROOT).contains(q) ||
                    it.number.toString() == q
            }
        }
        return list
    }

    // ---------------- JSON ----------------

    companion object {
        const val GROUP_ALL = "全部频道"

        /** 老版本内置演示源的固定 id，仅用于升级时清理 */
        private const val LEGACY_BUILTIN_ID = "builtin"

        /** 解析 "Key: Value" 每行一条的自定义请求头 */
        fun parseHeaders(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val out = HashMap<String, String>()
            raw.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
                }
            }
            return out
        }

    }
}
