/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.i18n

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The rules that keep a translated app translated.
 *
 * Read off the resource files rather than through the framework, because what is being
 * checked is the *set* of strings in each language, and the framework's answer to a missing
 * one is to quietly fall back to English. Falling back is the right runtime behaviour and
 * exactly the wrong thing for a test: an app that silently shows half its interface in
 * English is the failure this catches.
 */
class TranslationsTest {
    private val res = File("src/main/res")

    private fun strings(dir: String): Map<String, String> {
        val file = File(res, "$dir/strings.xml")
        if (!file.exists()) return emptyMap()
        return ENTRY.findAll(file.readText()).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun translatable(): Map<String, String> {
        val text = File(res, "values/strings.xml").readText()
        return ENTRY.findAll(text)
            .filterNot { it.value.contains("translatable=\"false\"") }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun locales(): List<String> = res.listFiles()
        .orEmpty()
        .map { it.name }
        .filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }
        .sorted()

    @Test
    fun `there is at least one translation`() {
        // Guards the rest of this class: every assertion below passes trivially against an
        // empty list, so a build that lost its translations would look green.
        assertThat(locales()).isNotEmpty()
    }

    @Test
    fun `every language has every string`() {
        val expected = translatable().keys
        locales().forEach { locale ->
            val missing = expected - strings(locale).keys
            assertThat(missing).isEmpty()
        }
    }

    @Test
    fun `no language invents a string English does not have`() {
        // A leftover from a renamed key. It is dead weight, and the rename it survived means
        // the screen that used it is showing English.
        val known = ENTRY.findAll(File(res, "values/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()
        locales().forEach { locale ->
            assertThat(strings(locale).keys - known).isEmpty()
        }
    }

    @Test
    fun `nothing is left in English by accident`() {
        // Not a check that the words differ, which would be wrong: "Audio", "PDF" and
        // "Proxy" are the same in several of these. It checks that a language has not been
        // filled in by copying the file, which is what a machine translation run that failed
        // halfway leaves behind.
        val english = translatable()
        locales().forEach { locale ->
            val translated = strings(locale)
            val same = english.count { (key, value) -> translated[key] == value }
            assertThat(same).isLessThan(english.size / 2)
        }
    }

    @Test
    fun `a name is never translated`() {
        // "unsloth" and "lfm2.5" are identifiers. A translator handed them will render them,
        // and then the filter chip no longer matches the thing it filters.
        val text = File(res, "values/strings.xml").readText()
        listOf("unsloth_liquidai_bartowski", "lfm2_5", "app_name").forEach { key ->
            assertThat(text).contains("name=\"$key\" translatable=\"false\"")
        }
    }

    @Test
    fun `every shipped language is offered in the system picker`() {
        // Without an entry in locales_config the per-app language setting does not list it,
        // so the translation exists and nobody can choose it.
        val config = File(res, "xml/locales_config.xml").readText()
        locales().map { it.removePrefix("values-") }.forEach { tag ->
            assertThat(config).contains("android:name=\"$tag\"")
        }
    }

    @Test
    fun `a format placeholder means the same thing in every language`() {
        // A translator who drops or reorders a placeholder produces a crash at runtime, not
        // a wrong word.
        val english = translatable()
        locales().forEach { locale ->
            strings(locale).forEach { (key, value) ->
                val expected = PLACEHOLDER.findAll(english[key].orEmpty()).count()
                assertThat(PLACEHOLDER.findAll(value).count()).isEqualTo(expected)
            }
        }
    }

    private companion object {
        val ENTRY =
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d*\$?[sd]""")
    }
}
