package com.eigenkodex.navonweb.diagnostics.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticClientLocaleTest {
    @Test
    fun canonicalizesSupportedLanguageTags() {
        assertEquals("ko-KR", DiagnosticClientLocale.normalize("ko-kr"))
        assertEquals("zh-Hans-CN", DiagnosticClientLocale.normalize("zh-hans-cn"))
        assertEquals("pt-BR", DiagnosticClientLocale.normalize("pt_BR"))
    }

    @Test
    fun malformedOrOversizedTagsFailClosedToUnknown() {
        assertEquals("und", DiagnosticClientLocale.normalize(""))
        assertEquals("und", DiagnosticClientLocale.normalize("ko/../../secret"))
        assertEquals("und", DiagnosticClientLocale.normalize("a".repeat(100)))
    }
}
