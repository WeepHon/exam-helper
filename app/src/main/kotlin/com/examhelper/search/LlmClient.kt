package com.examhelper.search

import com.examhelper.core.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 联网能力：调用 OpenAI 兼容的 Chat Completions 接口。
 *
 * 单次作答：把本地题库命中条款作为「参考依据」与题目一起交给模型，
 * 由模型自行决定「依据条款」还是「用自身知识」作答。这样每题只发 1 次请求
 * （速度最快，且天然兼容「并发上限=1」的账号），同时不依赖任何阈值/判定——
 * 手册内题有条款依据，手册外题用知识作答，答案都不丢。
 *
 * 兼容 OpenAI / DeepSeek / Moonshot / 通义千问(OpenAI兼容模式) / 智谱GLM 等。
 */
object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 单次作答：把本地题库命中条款作为「参考依据」与题目一起交给模型，
     * 由模型自行决定「依据条款」还是「用自身知识」作答。
     *
     * @param fullText 完整识别文本（含选项）
     * @param localContext 本地题库命中条款（可能为空/占位），作为参考依据
     */
    suspend fun askUnified(
        baseUrl: String,
        apiKey: String,
        model: String,
        question: String,
        fullText: String,
        localContext: String
    ): String? {
        val system = "你是严谨、知识渊博的答题助手。\n" +
            "用户会提供一道题目，以及若干条「本地题库条款」作为参考依据（内部资料，网上可能搜不到）。\n\n" +
            "作答规则：\n" +
            "1. 若「本地题库条款」中存在该题的答案依据，优先严格依据条款作答，并注明依据；\n" +
            "2. 若条款与题目无关（如时政、通识、其他课程题）或条款为空，则用你的全部知识作答；\n" +
            "3. 选择题输出「答案：X」并简要说明；判断题输出「答案：对 / 错」并说明理由；填空/问答直接给答案；\n" +
            "4. 准确、简洁，先给结论再解释；不确定时明确说明不确定。"

        val user = "题目原文（含选项）：\n$fullText\n\n" +
            "题干：$question\n\n" +
            "本地题库条款（参考依据，可能为空）：\n$localContext"

        return chat(baseUrl, apiKey, model, system, user)
    }

    /** 底层 Chat Completions 调用 */
    private suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String
    ): String? {
        return chatMessages(
            baseUrl, apiKey, model,
            listOf("system" to system, "user" to user),
            0.1, 3000
        )
    }

    /** 底层 Chat Completions 调用（支持多轮 messages，用于追问） */
    private suspend fun chatMessages(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            for ((role, content) in messages) {
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            val url = baseUrl.trimEnd('/') + "/chat/completions"

            // 第一次：带 temperature
            var resp = postChat(url, apiKey, model, arr, temperature, maxTokens)
            // 部分模型（如 kimi-k3.1-pro）只允许 temperature=1，会报 400；去掉该参数重试一次
            if (resp != null && resp.first == 400 && resp.second.contains("temperature")) {
                LogUtil.w("模型不接受 temperature=$temperature，去掉后重试")
                resp = postChat(url, apiKey, model, arr, null, maxTokens)
            }
            // 429 限流（RPM / 并发超限）：短暂等待后重试一次，提高低限额账号的成功率
            if (resp != null && resp.first == 429) {
                LogUtil.w("LLM 限流(429)，等待 1.5s 后重试")
                delay(1500)
                resp = postChat(url, apiKey, model, arr, null, maxTokens)
            }
            val (code, body) = resp ?: return@runCatching null
            if (code !in 200..299) {
                LogUtil.w("LLM 请求失败 http=$code body=${body.take(200)}")
                return@runCatching null
            }
            val json = JSONObject(body)
            val msg = json.getJSONArray("choices")
                .optJSONObject(0)
                ?.getJSONObject("message")
            // 只取最终答案 content；reasoning_content 是模型的思考过程（常为英文碎片），
            // 直接展示会给用户看垃圾，content 为空时宁可返回失败由调用方兜底。
            msg?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** 发起一次 Chat 请求，返回 (httpCode, body)；网络异常返回 null。须在 IO 线程调用。 */
    private fun postChat(
        url: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        temperature: Double?,
        maxTokens: Int
    ): Pair<Int, String>? = runCatching {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
        if (temperature != null) body.put("temperature", temperature)
        body.put("max_tokens", maxTokens)

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            resp.code to bodyStr
        }
    }.getOrNull()

    /**
     * 追问 / 纠正：基于原题 + 之前的问答上下文，回答用户的追加问题或按用户指出的方向修正。
     *
     * @param history 之前的多轮消息（不含 system 与首条 user），形如 [(assistant, 答案), (user, 追问), ...]
     * @param userNote 用户本次的追问 / 纠正内容
     */
    suspend fun askFollowUp(
        baseUrl: String,
        apiKey: String,
        model: String,
        question: String,
        fullText: String,
        history: List<Pair<String, String>>,
        userNote: String
    ): String? {
        val system = "你是严谨的答题助手。你会结合题目上下文与用户的追加消息继续作答：\n" +
            "1. 用户追问联想问题时，直接、准确地回答该问题；\n" +
            "2. 用户指出你之前的回答侧重或方向有误时，先简要确认，再给出修正后的答案；\n" +
            "3. 保持准确、简洁，先给结论再解释；不确定时明确说明不确定。"
        val messages = mutableListOf<Pair<String, String>>(
            "system" to system,
            "user" to "题目原文（含选项）：\n$fullText\n\n题干：$question\n\n请先作答。"
        )
        messages.addAll(history)
        messages.add("user" to userNote)
        return chatMessages(baseUrl, apiKey, model, messages, 0.2, 3000)
    }

    /**
     * 视觉/文本分类：把图片（base64 data URL）与文本一起发给多模态模型。
     * 用于导入时辅助判断题库类型等场景。失败返回 null（调用方回退本地逻辑）。
     *
     * @param images 形如 "data:image/jpeg;base64,..." 的图片列表；传空列表时退化为纯文本判断。
     */
    suspend fun askVision(
        baseUrl: String,
        apiKey: String,
        model: String,
        images: List<String>,
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val userContent = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
            for (img in images) {
                userContent.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", img))
                )
            }

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "你是题库类型识别助手，请严格按照要求输出简短标记，不要多余解释。"))
                    .put(JSONObject().put("role", "user").put("content", userContent)))
                .put("temperature", 0.0)
                // thinking 模型需要先思考再输出分类标记，留足空间避免 content 被截空
                .put("max_tokens", 1000)

            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogUtil.w("LLM 视觉请求失败 http=${resp.code} body=${resp.body?.string()?.take(200)}")
                    return@runCatching null
                }
                val json = JSONObject(resp.body?.string() ?: return@runCatching null)
                val msg = json.getJSONArray("choices")
                    .optJSONObject(0)
                    ?.getJSONObject("message")
                msg?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
