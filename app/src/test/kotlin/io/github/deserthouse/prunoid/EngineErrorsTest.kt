package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.engine.EngineErrorKind
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.BACKUP_CREATE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.BACKUP_PATH
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.BACKUP_UNPACK
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.CACHE_WRITE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.EMPTY_SOURCE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.HTTP
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.IFW_REMOVE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.IFW_WRITE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.INVALID_TARGET
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.LEDGER_CORRUPTED
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.LEDGER_WRITE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.NETWORK
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.PM_COMMAND
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.SYSTEM_BLOCKED
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.TOO_LARGE
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.UNKNOWN
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.Companion.classify
import io.github.deserthouse.prunoid.core.engine.EngineErrorKind.Companion.httpCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批Y5（第五轮审查 R5-11）：失败原因分类——输入取自各入口实际抛出的消息原文。 */
class EngineErrorsTest {

    @Test
    fun `engine failure messages map to their kinds`() {
        assertEquals(IFW_WRITE, classify("ifw write failed: []"))
        assertEquals(IFW_WRITE, classify("ifw rewrite failed"))
        assertEquals(IFW_REMOVE, classify("ifw remove failed"))
        assertEquals(PM_COMMAND, classify("pm disable: all 5 components failed"))
        assertEquals(PM_COMMAND, classify("pm enable failed"))
        assertEquals(BACKUP_PATH, classify("backup path outside managed directory"))
        assertEquals(BACKUP_UNPACK, classify("restore unpack failed: tar: x"))
        assertEquals(EngineErrorKind.BACKUP_APPLY, classify("restore apply failed: y"))
        assertEquals(BACKUP_CREATE, classify("backup not created (tar rc=1, root ok?): e"))
        assertEquals(BACKUP_CREATE, classify("backup listing failed: e"))
        assertEquals(LEDGER_WRITE, classify("ledger atomic replace failed: old ledger intact, new data kept in x.tmp"))
        assertEquals(LEDGER_CORRUPTED, classify("ledger corrupted — reset required before writing"))
        assertEquals(INVALID_TARGET, classify("invalid component name"))
        assertEquals(INVALID_TARGET, classify("invalid package name"))
        assertEquals(SYSTEM_BLOCKED, classify("system app blocked by whitelist: com.x"))
    }

    @Test
    fun `subscription failure messages map to their kinds`() {
        assertEquals(TOO_LARGE, classify("response too large (>20MB)"))
        assertEquals(HTTP, classify("HTTP 404"))
        assertEquals("404", httpCode("HTTP 404"))
        assertEquals("503", httpCode("HTTP 503"))
        assertEquals(NETWORK, classify("Failed to connect to /127.0.0.1:9"))
        assertEquals(NETWORK, classify("timeout"))
        assertEquals(NETWORK, classify("Software caused connection abort"))
        assertEquals(EMPTY_SOURCE, classify("rule source has no entries"))
        assertEquals(CACHE_WRITE, classify("rule cache atomic replace failed"))
    }

    @Test
    fun `unknown and null stay unknown`() {
        assertEquals(UNKNOWN, classify(null))
        assertEquals(UNKNOWN, classify(""))
        assertEquals(UNKNOWN, classify("boom"))
        assertEquals(null, httpCode("boom"))
    }
}
