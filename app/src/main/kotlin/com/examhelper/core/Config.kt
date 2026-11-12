package com.examhelper.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一个可保存的模型配置：每个模型/服务商对应一个独立的 API Key。
 */
data class LlmProfile(
    val id: String,
    val name: String,       // 用户给这份配置起的名字，用于区分不同 Key
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    fun isValid() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)

    companion object {
        fun fromJson(o: JSONObject): LlmProfile = LlmProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            baseUrl = o.optString("baseUrl"),
            apiKey = o.optString("apiKey"),
            model = o.optString("model")
        )
    }
}

/**
 * 简单配置存储。
 *
 * 从 v1.8.0 起支持「一个模型对应一个 API Key」：联网配置不再只有一份，
 * 而是可以保存多份（LlmProfile），并在其中选一份作为「当前使用」。
 */
object Config {

    private const val PREF = "examhelper_pref"

    // 旧字段（仅用于向后兼容迁移）
    private const val K_LLM_URL = "llm_url"
    private const val K_LLM_KEY = "llm_key"
    private const val K_LLM_MODEL = "llm_model"

    private const val K_BUILTIN_ENABLED = "builtin_enabled"

    // 新字段：多模型配置
    private const val K_PROFILES = "llm_profiles"      // JSONArray 字符串
    private const val K_CURRENT_ID = "llm_current_id"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 旧版本（单条配置）自动迁移到新结构：生成一份 profile 并设为当前使用。
     * 只在尚未初始化过 K_PROFILES 时执行一次。
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.contains(K_PROFILES)) return
        val url = p.getString(K_LLM_URL, "") ?: ""
        val key = p.getString(K_LLM_KEY, "") ?: ""
        val model = p.getString(K_LLM_MODEL, "") ?: ""
        if (url.isNotBlank() && key.isNotBlank() && model.isNotBlank()) {
            val profile = LlmProfile(
                id = "legacy",
                name = model,
                baseUrl = url,
                apiKey = key,
                model = model
            )
            p.edit()
                .putString(K_PROFILES, JSONArray().put(profile.toJson()).toString())
                .putString(K_CURRENT_ID, profile.id)
                .apply()
        } else {
            // 标记已初始化，避免每次读取都重复判断
            p.edit().putString(K_PROFILES, JSONArray().toString()).apply()
        }
    }

    /** 读取全部已保存的模型配置（不触发迁移，调用方需先保证已迁移） */
    private fun listProfilesRaw(context: Context): List<LlmProfile> {
        val raw = prefs(context).getString(K_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { LlmProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 读取全部已保存的模型配置 */
    fun listProfiles(context: Context): List<LlmProfile> {
        migrateIfNeeded(context)
        return listProfilesRaw(context)
    }

    /** 保存（新增或按 id 更新）一份模型配置，返回该配置 */
    fun saveProfile(context: Context, profile: LlmProfile): LlmProfile {
        migrateIfNeeded(context)
        val list = listProfilesRaw(context).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        val finalProfile = if (profile.id.isBlank()) {
            profile.copy(id = UUID.randomUUID().toString())
        } else profile
        if (idx >= 0) list[idx] = finalProfile else list.add(finalProfile)

        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(K_PROFILES, arr.toString()).apply()
        return finalProfile
    }

    /** 删除一份模型配置 */
    fun deleteProfile(context: Context, id: String) {
        migrateIfNeeded(context)
        val list = listProfilesRaw(context).filter { it.id != id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val p = prefs(context)
        val edit = p.edit().putString(K_PROFILES, arr.toString())
        if (p.getString(K_CURRENT_ID, null) == id) {
            edit.putString(K_CURRENT_ID, list.firstOrNull()?.id ?: "")
        }
        edit.apply()
    }

    /** 设置「当前使用」的模型配置 */
    fun setCurrentProfile(context: Context, id: String) {
        migrateIfNeeded(context)
        prefs(context).edit().putString(K_CURRENT_ID, id).apply()
    }

    /** 当前使用的模型配置 id */
    fun currentProfileId(context: Context): String? {
        migrateIfNeeded(context)
        val p = prefs(context)
        val cur = p.getString(K_CURRENT_ID, null)
        val list = listProfilesRaw(context)
        // 当前指向的配置已不存在时，回退到第一份
        return if (list.any { it.id == cur }) cur else list.firstOrNull()?.id
    }

    /**
     * 读取「当前使用」的联网配置，兼容旧调用方（AnswerEngine / FloatingBallService）。
     * 返回 null 表示未配置。
     */
    fun loadLlm(context: Context): LlmConfig? {
        migrateIfNeeded(context)
        val list = listProfilesRaw(context)
        if (list.isEmpty()) return null
        val curId = currentProfileId(context)
        val profile = list.firstOrNull { it.id == curId } ?: list.first()
        val cfg = LlmConfig(profile.baseUrl, profile.apiKey, profile.model, profile.name)
        return if (cfg.isValid()) cfg else null
    }

    /** 内置知识库是否启用（默认启用） */
    fun isBuiltinEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_BUILTIN_ENABLED, true)

    fun setBuiltinEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(K_BUILTIN_ENABLED, enabled).apply()
    }
}
