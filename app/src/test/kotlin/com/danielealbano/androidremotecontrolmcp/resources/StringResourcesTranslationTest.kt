package com.danielealbano.androidremotecontrolmcp.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Guards the translated string catalogs.
 *
 * Every locale ships a complete copy of the default catalog, so an English string added without its
 * translations would silently fall back to English at runtime. The resource XML files are checked in
 * as plain text, so this test parses them directly — no Robolectric, no device — and a missing or
 * malformed translation fails the build instead of shipping.
 *
 * The gms flavor overrides a handful of default strings; those are guarded the same way.
 */
@DisplayName("String resources translations")
class StringResourcesTranslationTest {
    @ParameterizedTest
    @MethodSource("supportedLocales")
    fun `main catalog is fully translated with matching keys and format specifiers`(locale: String) {
        assertCatalogParity(
            defaultPath = MAIN_DEFAULT,
            translationPath = "src/main/res/values-$locale/strings.xml",
        )
    }

    @ParameterizedTest
    @MethodSource("supportedLocales")
    fun `gms flavor overrides are fully translated with matching keys and format specifiers`(locale: String) {
        assertCatalogParity(
            defaultPath = GMS_DEFAULT,
            translationPath = "src/gms/res/values-$locale/strings.xml",
        )
    }

    @Test
    fun `every main locale directory is a supported locale`() {
        val localeDirectories =
            resolve("src/main/res")
                .listFiles { file -> file.isDirectory && LOCALE_DIR.matches(file.name) }
                .orEmpty()
                .map { it.name.removePrefix("values-") }
                .toSet()

        assertEquals(
            LOCALES.toSet(),
            localeDirectories,
            "Add a locale only by translating the whole catalog and listing it in SUPPORTED_LOCALES",
        )
    }

    private fun assertCatalogParity(
        defaultPath: String,
        translationPath: String,
    ) {
        val default = readEntries(defaultPath)
        val translated = readEntries(translationPath)

        assertEquals(default.keys.toList(), translated.keys.toList()) {
            "$translationPath must define exactly the same keys in the same order as $defaultPath"
        }

        default.forEach { (key, english) ->
            val value = translated.getValue(key)

            assertEquals(
                specifiersOf(english),
                specifiersOf(value),
                "Format specifiers in \"$key\" of $translationPath must match $defaultPath",
            )
            assertTrue(!UNESCAPED_APOSTROPHE.containsMatchIn(value)) {
                "The apostrophe in \"$key\" of $translationPath must be escaped as \\'"
            }
            assertTrue(!UNESCAPED_QUOTE.containsMatchIn(value)) {
                "The double quote in \"$key\" of $translationPath must be escaped as \\\""
            }
            if (key in VERBATIM_KEYS) {
                assertEquals(
                    english,
                    value,
                    "\"$key\" in $translationPath must stay byte-for-byte identical to $defaultPath",
                )
            }
        }
    }

    private fun readEntries(path: String): Map<String, String> =
        STRING_ENTRY
            .findAll(resolve(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun specifiersOf(value: String): List<String> =
        FORMAT_SPECIFIER
            .findAll(value)
            .map { it.value }
            .sorted()
            .toList()

    companion object {
        const val MAIN_DEFAULT = "src/main/res/values/strings.xml"
        const val GMS_DEFAULT = "src/gms/res/values/strings.xml"

        /**
         * Locales shipped with the app, matching the sibling `wallet` project's language set
         * (Simplified Chinese only). English is the default catalog and is not listed here.
         */
        val LOCALES = listOf("ar", "de", "es", "fr", "hi", "id", "ja", "ko", "pt", "ru", "zh")

        /** Values that must remain identical to English: identifiers and CLI examples. */
        val VERBATIM_KEYS =
            setOf(
                "notification_channel_mcp_server_id",
                "remote_access_cloudflare_extra_args_hint",
            )

        val STRING_ENTRY = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val LOCALE_DIR = Regex("""values-[a-z]{2}""")
        val FORMAT_SPECIFIER = Regex("""%%|%\d+\$[sd]""")
        val UNESCAPED_APOSTROPHE = Regex("""(?<!\\)'""")
        val UNESCAPED_QUOTE = Regex("(?<!\\\\)\"")

        @JvmStatic
        fun supportedLocales(): List<String> = LOCALES

        /** Unit tests run with the module directory as CWD; fall back to the repository root. */
        fun resolve(relativePath: String): File =
            listOf(File(relativePath), File("app", relativePath)).firstOrNull { it.exists() }
                ?: error("Resource path not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
