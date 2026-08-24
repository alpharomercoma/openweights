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

    @Test
    fun `no screen puts English straight on the screen`() {
        // The counterpart to the test above, and the one that catches what it cannot. Every
        // check here reads the resource files, so a sentence that was never made a resource
        // is invisible to all of them: it has no key, so no key is missing, and the app
        // shows English in the middle of a translated screen with nothing red anywhere.
        //
        // Scoped to the ui package on purpose. Text there is on a screen by definition.
        // Elsewhere a string literal is as likely to be a prompt sent to the model, which
        // stays in English because that is the language it was trained to follow.
        val offenders = uiSources().flatMap { file ->
            val source = file.readText()
            val previews = previewRanges(source)
            ON_SCREEN.findAll(source)
                .filter { it.groupValues[1].contains(' ') }
                .filterNot { match -> previews.any { match.range.first in it } }
                .map { "${file.name}: ${it.groupValues[1].take(60)}" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `sample text inside a preview is not mistaken for interface copy`() {
        // The exclusion above is what stands between this check and a stream of false
        // alarms, and a wrong exclusion is worse than none: it would quietly stop reporting
        // real strings too. So the shape it depends on is asserted here.
        val source = """
            @Composable
            private fun Greeting() {
                Text(text = stringResource(R.string.hello))
            }

            @Preview
            @Composable
            private fun GreetingPreview() {
                Text(text = "a sample somebody wrote for the tooling")
            }
        """.trimIndent()

        val previews = previewRanges(source)
        val found = ON_SCREEN.findAll(source)
            .filter { it.groupValues[1].contains(' ') }
            .filterNot { match -> previews.any { match.range.first in it } }
            .toList()

        assertThat(previews).hasSize(1)
        assertThat(found).isEmpty()
    }

    private fun uiSources(): List<File> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.path.contains("/ui/") }
        .toList()

    /**
     * The spans a `@Preview` covers, which are sample data rather than interface copy.
     *
     * Found by taking each annotation to the next brace in the first column, which is where
     * a top level function ends in this codebase. That assumption is the load-bearing part
     * of the exclusion, so it is tested rather than trusted.
     */
    private fun previewRanges(source: String): List<IntRange> =
        Regex("""^@Preview""", RegexOption.MULTILINE).findAll(source).map { preview ->
            val close = Regex("""^\}""", RegexOption.MULTILINE)
                .find(source, preview.range.first)?.range?.last
                ?: source.lastIndex
            preview.range.first..close
        }.toList()

    private companion object {
        val ENTRY =
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d*\$?[sd]""")

        /**
         * Text on its way to a screen: what a control says, and what a screen reader reads.
         *
         * Deliberately these three and no more. `label` is the fourth slot that takes a
         * string and it is mostly the name of an animation, which nobody reads and nobody
         * translates, so including it would make this test cry wolf until somebody turned
         * it off.
         */
        val ON_SCREEN =
            Regex("""(?:\btext\s*=\s*|\bcontentDescription\s*=\s*|\bText\(\s*)"([^"\\]{6,})"""")
    }
}
