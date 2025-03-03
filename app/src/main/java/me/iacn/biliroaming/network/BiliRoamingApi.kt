package me.iacn.biliroaming.network

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.XposedInit
import me.iacn.biliroaming.hook.BangumiSeasonHook.Companion.lastSeasonInfo
import me.iacn.biliroaming.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream


/**
 * Created by iAcn on 2019/3/27
 * Email i@iacn.me
 */
object BiliRoamingApi {
    private const val BILI_SEASON_URL = "api.bilibili.com/pgc/view/v2/app/season"
    private const val BILI_SEARCH_URL = "/x/v2/search/type"
    private const val BILIPLUS_VIEW_URL = "www.biliplus.com/api/view"
    private const val BILI_MEDIA_URL = "www.bilibili.com/bangumi/media/md"
    private const val BILI_SECTION_URL = "api.bilibili.com/pgc/web/season/section"
    private const val BILI_CARD_URL = "https://account.bilibili.com/api/member/getCardByMid"
    private const val BILI_PAGELIST = "api.bilibili.com/x/player/pagelist"
    private const val BILI_MODULE_TEMPLATE =
        "{\"data\": {},\"id\": 0,\"module_style\": {\"hidden\": 0,\"line\": 1},\"more\": \"查看更多\",\"style\": \"positive\",\"title\": \"选集\"}"
    private const val BILI_RIGHT_TEMPLATE =
        "{\"allow_demand\":0,\"allow_dm\":1,\"allow_download\":0,\"area_limit\":0}"
    private const val BILI_VIP_BADGE_TEMPLATE =
        "{\"bg_color\":\"#FB7299\",\"bg_color_night\":\"#BB5B76\",\"text\":\"%s\"}"

    private const val PATH_PLAYURL = "/pgc/player/api/playurl"
    private const val THAILAND_PATH_PLAYURL = "/intl/gateway/v2/ogv/playurl"
    private const val THAILAND_PATH_SUBTITLES = "/intl/gateway/v2/app/subtitle"
    private const val THAILAND_PATH_SEARCH = "/intl/gateway/v2/app/search/type"
    private const val THAILAND_PATH_SEASON = "/intl/gateway/v2/ogv/view/app/season"

    const val overseaTestParams =
        "cid=120453316&ep_id=285145&otype=json&fnval=16&module=pgc&platform=android&test=true"
    const val mainlandTestParams =
        "cid=13073143&ep_id=100615&otype=json&fnval=16&module=pgc&platform=android&test=true"

    private val seasonCache: AtomicReference<Triple<Int, AtomicReference<String?>, CountDownLatch>?> =
        AtomicReference(null)

    @JvmStatic
    fun getSeason(info: Map<String, String?>, season: JSONObject?): String? {
        val seasonId = info.getOrDefault("season_id", null)?.toInt() ?: return null
        val cache = seasonCache.get()
        val cacheTuple = if (seasonId != 0) {
            if (cache?.first == seasonId) {
                cache.third.await()
                return cache.second.get()
            } else {
                Triple(seasonId, AtomicReference<String?>(null), CountDownLatch(1)).also {
                    seasonCache.compareAndSet(cache, it)
                }
            }
        } else null

        var seasonJson = season

        seasonJson = seasonJson ?: run {
            val query = mapOf(
                    "season_id" to seasonId.toString(),
            )
            val content = getContent(Uri.Builder().scheme("https").encodedAuthority(BILI_SEASON_URL).encodedQuery(signQuery(query)).toString())
            content?.toJSONObject()?.optJSONObject("data")
        } ?: run {
            val content = getContent(Uri.Builder().scheme("https").encodedAuthority("${BILI_MEDIA_URL}${seasonId}").toString()) ?: return@run null
            val json = Regex("""[\w\W]*window\.__INITIAL_STATE__=(.*);\(function\(\)[\w\W]*""").matchEntire(content)?.groupValues?.get(1)?.toJSONObject() ?: return@run null
            json.optJSONObject("mediaInfo")?.also {
                it.put("modules", JSONArray().put(JSONObject().put("data", JSONObject().put("seasons", it.optJSONObject("seasons")))))
            }
        }

        seasonJson = seasonJson?.let {
            fixSection(it)
            fixEpisodes(it)
            fixPrevueSection(it)
            reconstructModules(it)
            fixRight(it)
            JSONObject().put("code", 0).put("result", it)
        } ?: run {
            val thUrl = sPrefs.getString("th_server", null)
            val mobiApp = sPrefs.getString("th_server_platform", platform)!!
            if (thUrl != null) {
                val builder = Uri.Builder()
                builder.scheme("https").encodedAuthority(thUrl)
                        .encodedPath(THAILAND_PATH_SEASON)
                        .appendQueryParameter("s_locale", "zh_SG")
                        .appendQueryParameter("access_key", instance.getCustomizeAccessKey("th_server"))
                        .appendQueryParameter("mobi_app", "bstar_a")
                        .appendQueryParameter("build", "1080003")
                        .appendQueryParameter("season_id", seasonId.toString())
                        .appendQueryParameter("ep_id", info.getOrDefault("ep_id", "0"))
                getContent(builder.toString(), mobiApp)?.toJSONObject()?.also {
                    it.optJSONObject("result")?.let { result ->
                        fixThailandSeason(result)
                        return@run it
                    }
                    checkErrorToast(it, true)
                }
            } else {
                checkErrorToast("""{"code" = -404, "message" = "未设置泰区解析服务器"}""".toJSONObject())
            }
            null
        }
        return seasonJson?.toString().also {
            if (seasonJson?.optInt("code", -1) == 0)
                cacheTuple?.second?.set(it)
            cacheTuple?.third?.countDown()
        }
    }

    @JvmStatic
    private fun fixSection(result: JSONObject) {
        val seasonId = result.optString("season_id")
        val uri = Uri.Builder()
            .scheme("https")
            .encodedAuthority(BILI_SECTION_URL)
            .appendQueryParameter("season_id", seasonId)
            .toString()
        val sectionJson = getContent(uri).toJSONObject().optJSONObject("result") ?: return
        val sections = sectionJson.optJSONArray("section") ?: return

        for ((i, section) in sections.iterator().withIndex()) {
            section.put("episode_id", i)
            val newEpisodes = JSONArray()
            for (episode in section.optJSONArray("episodes").orEmpty()) {
                newEpisodes.put(episode)
            }
            section.put("episodes", newEpisodes)
        }
        result.put("section", sections)
        result.optJSONObject("newest_ep")?.run {
            put("title", optString("index"))
            result.put("new_ep", this)
        }

        val newEpisodes = JSONArray()
        for (episode in sectionJson.optJSONObject("main_section")?.optJSONArray("episodes")
            .orEmpty()) {
            newEpisodes.put(episode)
        }
        result.put("episodes", newEpisodes)
    }

    @JvmStatic
    fun getPagelist(aid: String) = getContent(
        Uri.Builder().scheme("https")
            .encodedAuthority(BILI_PAGELIST)
            .appendQueryParameter("aid", aid).toString()
    )?.toJSONObject()?.let {
        if (it.optInt("code", -1) == 0) it else null
    }

    @JvmStatic
    fun getThailandSubtitles(epId: String?): String? {
        Log.d("Getting subtitle $epId form thailand")
        epId ?: return null
        val thUrl = sPrefs.getString("th_server", null) ?: return null
        val uri = Uri.Builder()
            .scheme("https")
            .encodedAuthority(thUrl)
            .encodedPath(THAILAND_PATH_SUBTITLES)
            .appendQueryParameter("ep_id", epId)
            .toString()
        return getContent(uri)
    }

    @JvmStatic
    fun getAreaSearchBangumi(query: Map<String, String>, area: String, type: String): String? {
        if (area == "th") {
            return getThailandSearchBangumi(query, type)
        }
        val hostUrl = sPrefs.getString(area + "_server", null) ?: return null
        val uri = Uri.Builder()
            .scheme("https")
            .encodedAuthority(hostUrl)
            .encodedPath(BILI_SEARCH_URL)
            .encodedQuery(
                signQuery(
                    query, mapOf(
                        "type" to type,
                        "build" to "6400000",
                        "area" to area,
                    )
                )
            )
            .toString()
        return getContent(uri)
    }

    @JvmStatic
    fun getThailandSearchBangumi(query: Map<String, String>, type: String): String? {
        val thUrl = sPrefs.getString("th_server", null) ?: return null
        val uri = Uri.Builder()
            .scheme("https")
            .encodedAuthority(thUrl)
            .encodedPath(THAILAND_PATH_SEARCH)
            .encodedQuery(
                signQuery(
                    query, mapOf(
                        "type" to type,
                        "appkey" to "7d089525d3611b1c",
                        "build" to "1001310",
                        "mobi_app" to "bstar_a",
                        "platform" to "android",
                        "s_locale" to "zh_SG",
                        "c_locale" to "zh_SG",
                        "sim_code" to "52004",
                        "lang" to "hans",
                    )
                )
            )
            .toString()
        return getContent(uri)?.replace(
            "bstar://bangumi/season/",
            "https://bangumi.bilibili.com/anime/"
        )
    }

    @JvmStatic
    private fun fixPrevueSection(result: JSONObject) {
        result.put("prevueSection", result.optJSONObject("section"))
    }

    @JvmStatic
    private fun fixEpisodes(result: JSONObject, sid: Int = 0) {
        val episodes = result.optJSONArray("episodes")
        for ((eid, episode) in episodes.orEmpty().iterator().withIndex()) {
            fixRight(episode)
            if (episode.optInt("badge_type", -1) == 0)
                episode.remove("badge_info")
            if (episode.optString("badge") != "受限")
                episode.put(
                    "badge_info",
                    JSONObject(BILI_VIP_BADGE_TEMPLATE.format(episode.optString("badge")))
                )
            episode.put("ep_index", eid + 1)
            episode.put("section_index", sid + 1)
        }
        for ((off, section) in result.optJSONArray("section").orEmpty().iterator().withIndex()) {
            fixEpisodes(section, sid + off + 1)
        }
    }

    @JvmStatic
    private fun reconstructModules(result: JSONObject) {
        var id = 0
        val module = BILI_MODULE_TEMPLATE.toJSONObject()
        val episodes = result.optJSONArray("episodes")
        module.optJSONObject("data")?.put("episodes", episodes)
        module.put("id", ++id)
        val modules = arrayListOf(module)

        if (result.has("section")) {
            val sections = result.optJSONArray("section")
            for (section in sections.orEmpty()) {
                val sectionModule = BILI_MODULE_TEMPLATE.toJSONObject()
                    .put("data", section)
                    .put("style", "section")
                    .put("title", section.optString("title"))
                    .put("id", ++id)
                modules.add(sectionModule)
            }
        }
        if (result.has("seasons")) {
            val seasons = result.optJSONArray("seasons")
            for (season in seasons.orEmpty()) {
                season.put("title", season.optString("season_title"))
            }
            val seasonModule = BILI_MODULE_TEMPLATE.toJSONObject()
            seasonModule.put("data", JSONObject().put("seasons", seasons))
                .put("style", "season")
                .put("title", "")
                .put("id", ++id)
                .put("module_style", JSONObject("{\"line\": 1}"))
            modules.add(seasonModule)

        }
        // work around
        result.put("modules", JSONArray(modules))
    }

    @JvmStatic
    private fun fixRight(result: JSONObject) {
        result.optJSONObject("rights")?.run {
            put("area_limit", 0)
            put("allow_dm", 1)
        } ?: run { result.put("rights", BILI_RIGHT_TEMPLATE.toJSONObject()) }
    }
    class CustomServerException(private val errors: Map<String, String>) : Throwable() {
        override val message: String
            get() = errors.asSequence().joinToString("\n") { "${it.key}: ${it.value}" }.trim()
    }

    @JvmStatic
    fun getPlayUrl(queryString: String?, priorityArea: Array<String>? = null): String? {
        queryString ?: return null
        val twUrl = sPrefs.getString("tw_server", null)
        val hkUrl = sPrefs.getString("hk_server", null)
        val cnUrl = sPrefs.getString("cn_server", null)
        val thUrl = sPrefs.getString("th_server", null)

        val hostList = LinkedHashMap<String, String>(4, 1f, true)

        if (hostList.isEmpty())
        // reversely
            linkedMapOf("tw" to twUrl, "hk" to hkUrl, "th" to thUrl, "cn" to cnUrl).filterKeys {
                if (!sPrefs.getString("${it}_server_accessKey", null).isNullOrEmpty())
                    return@filterKeys true
                it != (runCatchingOrNull { XposedInit.country.get(5L, TimeUnit.SECONDS) } ?: true)
            }.filterValues {
                it != null
            }.mapValuesTo(hostList) {
                it.value!!
            }

        val epIdStartIdx = queryString.indexOf("ep_id=")
        val epIdEndIdx = queryString.indexOf("&", epIdStartIdx)
        val epId = queryString.substring(epIdStartIdx + 6, epIdEndIdx)

        if (!lastSeasonInfo.containsKey("ep_ids") || lastSeasonInfo["ep_ids"]?.contains(epId) != true)
            lastSeasonInfo.clear()

        lastSeasonInfo["title"]?.run {
            if (contains(Regex("僅.*台")) && twUrl != null) hostList["tw"]
            if (contains(Regex("僅.*港")) && hkUrl != null) hostList["hk"]
            if (contains(Regex("[仅|僅].*[东南亚|其他]")) && thUrl != null) hostList["th"]
        }

        priorityArea?.forEach { area ->
            if (hostList.containsKey(area)) hostList[area]
        }

        if (hostList.isEmpty()) return null

        val seasonId = lastSeasonInfo["season_id"] ?: if (epId.isEmpty()) null else "ep$epId"

        if (seasonId != null && sCaches.contains(seasonId)) {
            val cachedArea = sCaches.getString(seasonId, null)
            if (hostList.containsKey(cachedArea)) {
                Log.d("use cached area $cachedArea for $seasonId")
                hostList[cachedArea]
            }
        }

        val errors: MutableMap<String, String> = mutableMapOf()

        for ((area, host) in hostList.toList().asReversed()) {
            val accessKey = instance.getCustomizeAccessKey("${area}_server") ?: ""
            val mobiApp = sPrefs.getString("${area}_server_platform", platform)!!
            val extraMap = if (area == "th") mapOf(
                "area" to area,
                "appkey" to "7d089525d3611b1c",
                "build" to "1001310",
                "mobi_app" to "bstar_a",
                "platform" to "android",
                "access_key" to accessKey,
            )
            else mapOf(
                "area" to area,
                "access_key" to accessKey,
            )
            val path = if (area == "th") THAILAND_PATH_PLAYURL else PATH_PLAYURL
            val uri = Uri.Builder()
                .scheme("https")
                .encodedAuthority(host)
                .encodedPath(path)
                .encodedQuery(signQuery(queryString, extraMap))
                .toString()
            getContent(uri, mobiApp)?.let {
                Log.d("use server $area $host for playurl")
                if (it.contains("\"code\":0")) {
                    lastSeasonInfo["area"] = area
                    lastSeasonInfo["epid"] = epId
                    if (seasonId != null && !sCaches.contains(seasonId) || sCaches.getString(
                            seasonId,
                            null
                        ) != area
                    ) {
                        sCaches.edit().run {
                            putString(seasonId, area)
                            lastSeasonInfo["ep_ids"]?.split(";")
                                ?.forEach { epId -> putString("ep$epId", area) }
                            apply()
                        }
                    }
                    return if (area == "th") fixThailandPlayurl(it) else it
                }
                errors.put(area, JSONObject(it).optString("message"))
            } ?: errors.putIfAbsent(area, "服务器不可用")
        }
        throw CustomServerException(errors)
    }

    @JvmStatic
    fun fixThailandPlayurl(result: String): String {
        val input = JSONObject(result)
        val videoInfo = result.toJSONObject().optJSONObject("data")?.optJSONObject("video_info")
        val streamList = videoInfo?.optJSONArray("stream_list")
        val dashAudio = videoInfo?.optJSONArray("dash_audio")

        val output = JSONObject().apply {
            put("format", "flv720")
            put("type", "DASH")
            put("result", "suee")
            put("video_codecid", 7)
            put("no_rexcode", 0)

            put("code", input.optInt("code"))
            put("message", input.optInt("message"))
            put("timelength", videoInfo?.optInt("timelength"))
            put("quality", videoInfo?.optInt("quality"))
            put("accept_format", "hdflv2_4k,hdflv2_hdr,hdflv2_dolby,hdflv2,flv,flv720,flv480,mp4")
        }

        val acceptQuality = JSONArray()
        val acceptDescription = JSONArray()

        val dash = JSONObject().apply {
            put("duration", 0)
            put("minBufferTime", 0.0)
            put("min_buffer_time", 0.0)
        }
        val fixedAudio = JSONArray().apply {
            for (audio in dashAudio.orEmpty()) {
                put(audio)
            }
        }
        dash.put("audio", fixedAudio)

        val supportFormats = JSONArray()
        val dashVideo = JSONArray()
        for (stream in streamList.orEmpty()) {
            if (stream.optJSONObject("dash_video")?.optString("base_url").isNullOrBlank()) {
                continue
            }
            stream.optJSONObject("stream_info")?.let {
                supportFormats.put(it)
            }
            stream.optJSONObject("stream_info")?.let {
                acceptQuality.put(it.optInt("quality"))
            }
            stream.optJSONObject("stream_info")?.let {
                acceptDescription.put(it.optString("new_description"))
            }
            stream.optJSONObject("dash_video")?.let {
                it.put("id", stream.optJSONObject("stream_info")?.optInt("quality"))
                dashVideo.put(it)
            }
        }
        dash.put("video", dashVideo)

        output.put("accept_quality", acceptQuality)
        output.put("accept_description", acceptDescription)
        output.put("support_formats", supportFormats)
        output.put("dash", dash)

        return output.toString()
    }

    @JvmStatic
    fun fixThailandSeason(result: JSONObject) {
        val episodes = JSONArray()

        // 强制已追番
        result.optJSONObject("user_status")?.put("follow", 1)

        for ((mid, module) in result.optJSONArray("modules").orEmpty().iterator().withIndex()) {
            val data = module.optJSONObject("data") ?: continue
            val sid = module.optInt("id", mid + 1)
            for ((eid, ep) in data.optJSONArray("episodes").orEmpty()
                .iterator().withIndex()) {
                if (ep.optInt("status") == 13) {
                    ep.put("badge", "泰区会员")
                    ep.put("badge_info", JSONObject().apply {
                        put("bg_color", "#FB7299")
                        put("bg_color_night", "#BB5B76")
                        put("text", "泰区会员")
                    })
                }
                ep.put("status", 2)
                ep.put("episode_status", 2)
                ep.put("ep_id", ep.optInt("id"))
                ep.put("index", ep.optString("title"))
                ep.put("link", "https://www.bilibili.com/bangumi/play/ep${ep.optInt("id")}")
                ep.put("indexTitle", ep.optString("long_title"))
                ep.put("ep_index", eid + 1)
                ep.put("section_index", sid + 1)
                fixRight(ep)
                if (ep.optInt("cid", 0) == 0) {
                    ep.put("cid", ep.optInt("id"))
                    if (!sPrefs.getBoolean("force_th_comment", false))
                        ep.optJSONObject("rights")?.put("allow_dm", 0)
                }
                if (ep.optInt("aid", 0) == 0) {
                    ep.put("aid", result.optInt("season_id"))
                    if (!sPrefs.getBoolean("force_th_comment", false))
                        ep.optJSONObject("rights")?.put("area_limit", 1)
                }
                episodes.put(ep)
            }
            data.put("id", sid)
        }

        result.put("episodes", episodes)
        val style = JSONArray()
        for (i in result.optJSONArray("styles").orEmpty()) {
            style.put(i.optString("name"))
        }
        result.put("style", style)
        result.optJSONObject("rights")?.put("watch_platform", 1)
            ?.put("allow_comment", 0)
        result.apply {
            put("actors", result.optJSONObject("actor")?.optString("info"))
            put("is_paster_ads", 0)
            put("jp_title", result.optString("origin_name"))
            put("newest_ep", result.optJSONObject("new_ep"))
            put("season_status", result.optInt("status"))
            put("season_title", result.optString("title"))
            put("total_ep", episodes.length())
        }
    }

    @JvmStatic
    fun getView(queryString: String?): String? {
        val builder = Uri.Builder()
        builder.scheme("https").encodedAuthority(BILIPLUS_VIEW_URL)
        builder.encodedQuery(queryString)
        builder.appendQueryParameter("module", "bangumi")
        builder.appendQueryParameter("otype", "json")
        builder.appendQueryParameter("platform", "android")
        return getContent(builder.toString())
    }

    @JvmStatic
    fun getSpace(mid: Long): String? {
        val content = getContent("$BILI_CARD_URL?mid=$mid").toJSONObject()
        if (content.optInt("code") != 0) return null
        val card = content.optJSONObject("card") ?: return null
        val levelInfo = card.optJSONObject("level_info") ?: return null
        val officialVerify = card.optJSONObject("official_verify") ?: return null
        return """{"relation":-999,"guest_relation":-999,"default_tab":"video","is_params":true,"setting":{"fav_video":0,"coins_video":0,"likes_video":0,"bangumi":0,"played_game":0,"groups":0,"comic":0,"bbq":0,"dress_up":0,"disable_following":0,"live_playback":1,"close_space_medal":0,"only_show_wearing":0},"tab":{"archive":true,"article":true,"clip":true,"album":true,"favorite":false,"bangumi":false,"coin":false,"like":false,"community":false,"dynamic":true,"audios":true,"shop":false,"mall":false,"ugc_season":false,"comic":false,"cheese":false,"sub_comic":false,"activity":false,"series":false},"card":{"mid":"$mid","name":"${
            card.optString(
                "name"
            )
        }","approve":false,"sex":"${card.optString("sex")}","rank":"${card.optString("rank")}","face":"${
            card.optString(
                "face"
            )
        }","DisplayRank":"","regtime":0,"spacesta":0,"birthday":"","place":"","description":"该页面由哔哩漫游修复","article":0,"attentions":null,"fans":${
            card.optInt(
                "fans",
                114
            )
        },"friend":${card.optInt("friend", 514)},"attention":${
            card.optInt(
                "attention",
                233
            )
        },"sign":"【该页面由哔哩漫游修复】${card.optString("sign")}","level_info":{"current_level":${
            levelInfo.optInt(
                "current_level"
            )
        },"current_min":${levelInfo.optInt("current_min")},"current_exp":${levelInfo.optInt("current_exp")},"next_exp":"${
            levelInfo.optInt(
                "next_exp"
            )
        }"},"pendant":{"pid":0,"name":"","image":"","expire":0,"image_enhance":"","image_enhance_frame":""},"nameplate":{"nid":0,"name":"","image":"","image_small":"","level":"","condition":""},"official_verify":{"type":${
            officialVerify.optInt(
                "type"
            )
        },"desc":"${officialVerify.optString("desc")}","role":3,"title":"${
            officialVerify.optString(
                "desc"
            )
        }"},"vip":{"vipType":0,"vipDueDate":0,"dueRemark":"","accessStatus":0,"vipStatus":0,"vipStatusWarn":"","themeType":0,"label":{"path":"","text":"","label_theme":"","text_color":"","bg_style":0,"bg_color":"","border_color":""}},"silence":0,"end_time":0,"silence_url":"","likes":{"like_num":0,"skr_tip":"该页面由哔哩漫游修复"},"pr_info":{},"relation":{"status":1},"is_deleted":0,"honours":{"colour":{"dark":"#CE8620","normal":"#F0900B"},"tags":null},"profession":{}},"images":{"imgUrl":"https://i0.hdslb.com/bfs/album/16b6731618d911060e26f8fc95684c26bddc897c.jpg","night_imgurl":"https://i0.hdslb.com/bfs/album/ca79ebb2ebeee86c5634234c688b410661ea9623.png","has_garb":true,"goods_available":true},"live":{"roomStatus":0,"roundStatus":0,"liveStatus":0,"url":"","title":"","cover":"","online":0,"roomid":0,"broadcast_type":0,"online_hidden":0,"link":""},"archive":{"order":[{"title":"最新发布","value":"pubdate"},{"title":"最多播放","value":"click"}],"count":9999,"item":[]},"series":{"item":[]},"play_game":{"count":0,"item":[]},"article":{"count":0,"item":[],"lists_count":0,"lists":[]},"season":{"count":0,"item":[]},"coin_archive":{"count":0,"item":[]},"like_archive":{"count":0,"item":[]},"audios":{"count":0,"item":[]},"favourite2":{"count":0,"item":[]},"comic":{"count":0,"item":[]},"ugc_season":{"count":0,"item":[]},"cheese":{"count":0,"item":[]},"fans_effect":{},"tab2":[{"title":"动态","param":"dynamic"},{"title":"投稿","param":"contribute","items":[{"title":"视频","param":"video"}]}]}"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getContent(urlString: String, mobiApp: String = platform): String? {
        val timeout = 10000
        return try {
            // Work around for android 7
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N &&
                urlString.startsWith("https") &&
                !urlString.contains("bilibili.com")
            ) {
                Log.d("Found Android 7, try to bypass ssl issue")
                val handler = Handler(AndroidAppHelper.currentApplication().mainLooper)
                val listener = object : Any() {
                    val latch = CountDownLatch(1)
                    var result = ""

                    @Suppress("UNUSED")
                    @JavascriptInterface
                    fun callback(r: String) {
                        result = r
                        latch.countDown()
                    }
                }
                handler.post {
                    val webView = WebView(currentContext, null)
                    webView.addJavascriptInterface(listener, "listener")
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.settings?.javaScriptEnabled = true
                            view?.loadUrl("javascript:listener.callback(document.documentElement.innerText)")
                        }
                    }
                    webView.loadUrl(
                        urlString, mapOf(
                            "x-from-biliroaming" to BuildConfig.VERSION_NAME,
                            "platform-from-biliroaming" to mobiApp,
                            "Build" to BuildConfig.VERSION_CODE.toString()
                        )
                    )
                }
                try {
                    if (!listener.latch.await((timeout * 2).toLong(), TimeUnit.MILLISECONDS)) {
                        Log.toast("连接超时，请重试")
                        throw IOException("Timeout connection to server")
                    }
                } catch (e: InterruptedException) {
                    throw IOException("Connection to server was interrupted")
                }
                return listener.result
            } else {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Build", BuildConfig.VERSION_CODE.toString())
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.setRequestProperty("x-from-biliroaming", BuildConfig.VERSION_NAME)
                connection.setRequestProperty(
                    "Accept-Encoding",
                    "${if (instance.brotliInputStreamClass != null) "br," else ""}gzip,deflate"
                )
                connection.setRequestProperty("platform-from-biliroaming", mobiApp)
                connection.connect()
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    getStreamContent(
                        when (connection.contentEncoding?.lowercase()) {
                            "gzip" -> GZIPInputStream(inputStream)
                            "br" -> instance.brotliInputStreamClass!!.new(inputStream) as InputStream
                            "deflate" -> InflaterInputStream(inputStream)
                            else -> inputStream
                        }
                    )
                } else null
            }

        } catch (e: Throwable) {
            Log.e("getContent error: $e with url $urlString")
            Log.e(e)
            null
        }?.also {
            Log.d("getContent url: $urlString mobiApp: $mobiApp")
            Log.d("getContent result: $it")
        }
    }

}
