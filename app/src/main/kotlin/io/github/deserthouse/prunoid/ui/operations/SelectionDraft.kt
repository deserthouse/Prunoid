package io.github.deserthouse.prunoid.ui.operations

import io.github.deserthouse.prunoid.core.engine.Engine

/** 批FF-B1②（方案B S1）：选择草稿——绑定目标身份与规则代次。
 *  规则刷新（generation 递增）后草稿标 stale：提示用户重审，不自动改选/自动执行（评审契约 2）。 */
data class SelectionDraft(
    val packageName: String,
    val ruleIds: Set<String>,
    val engine: Engine,
    val rulesGeneration: Long,
) {
    fun isStale(currentGeneration: Long, currentPkg: String): Boolean =
        packageName != currentPkg || rulesGeneration != currentGeneration
}
