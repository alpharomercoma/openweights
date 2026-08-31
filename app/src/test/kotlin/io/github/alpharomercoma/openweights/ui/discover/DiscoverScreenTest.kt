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

package io.github.alpharomercoma.openweights.ui.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen the listing's first claim rests on.
 *
 * "Any model, not a catalogue" is the sentence the whole app is sold on, and this is the
 * only place it is either true or not. What is worth asserting is therefore not that the
 * screen draws, but that a repository the app has never heard of arrives with the two
 * things a person needs before spending a gigabyte of mobile data on it: who published it
 * and how big it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class DiscoverScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a result names its publisher and its size`() {
        showDiscover()

        // Both, because either alone is the wrong amount of information. The name says
        // nothing about whether the phone can hold it, and the size says nothing about
        // whether the repository is one anybody should trust.
        compose.onNodeWithText("Hammer2.1-1.5b").assertIsDisplayed()
        compose.onNodeWithText("MadeAgents", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1.5B").assertIsDisplayed()
    }

    @Test
    fun `typing in the field reaches the view model rather than the screen`() {
        // The search is not local. Every keystroke has to reach the thing that talks to the
        // Hub, and a field that kept its own state would look right and search nothing.
        var typed: String? = null
        // Empty, so the field shows its placeholder and can be found by it. A field with
        // text in it renders the text instead, which is how this test first failed.
        showDiscover(query = HubQuery(sort = HubSort.TRENDING), onQueryChange = { typed = it })

        compose.onNodeWithText("Search Hugging Face").performTextInput("qwen")

        assert(typed == "qwen") { "expected the typed text to reach onQueryChange, got $typed" }
    }

    @Test
    fun `search ime action reaches the hub callback`() {
        var searches = 0
        showDiscover(query = HubQuery(sort = HubSort.TRENDING), onSearch = { searches++ })

        compose.onNodeWithText("Search Hugging Face").performImeAction()

        assert(searches == 1) { "expected the keyboard search action to run once, got $searches" }
    }

    @Test
    fun `the sort in use is the one the state names`() {
        showDiscover(query = HubQuery(text = "gguf", sort = HubSort.DOWNLOADS))

        // Read off the state rather than remembered by the chip row, so that reopening the
        // screen cannot show one order while the results are in another. Scrolled to
        // first: the runtime chip pushed the sorts past the right edge of the row, which
        // scrolls on the phone exactly as it does here.
        compose.onNodeWithText("Downloads").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `asking for what fits reaches the view model`() {
        // The fit filter is the honest-about-your-device promise in its cheapest form, and
        // it is the view model that knows what this phone can hold.
        var asked: Boolean? = null
        showDiscover(onPhoneSizedChange = { asked = it })

        compose.onNodeWithText("Fits my phone").performClick()

        assert(asked == true) { "expected the fit filter to be switched on, got $asked" }
    }

    @Test
    fun `filter rail exposes a scroll affordance when chips do not fit`() {
        showDiscover()

        // A narrow phone cannot show every sort and filter chip at once. The right-edge cue
        // is part of the contract: clipping a chip without it is indistinguishable from a
        // layout overflow to a user.
        compose.onNodeWithContentDescription("Show more filters").assertIsDisplayed()
    }

    @Suppress("LongParameterList")
    @Test
    fun `official narrows the list to organisations`() {
        var asked: Boolean? = null
        showDiscover(onOfficialOnlyChange = { asked = it })

        // The Hub has no parameter for this, so the screen only asks; the view model does
        // the filtering against the account kind each publisher's own endpoint reports.
        compose.onNodeWithText("Official").performClick()

        assert(asked == true) { "Official must reach the view model, got $asked" }
    }

    @Test
    fun `recommended is the one filter the screen opens on`() {
        var asked: Boolean? = null
        showDiscover(onRecommendedOnlyChange = { asked = it })

        // On by default, so the first tap is the one that opens the search up. What it
        // narrows to is a shortlist this app has measured on hardware, which is a stronger
        // claim than any of the other three and makes them unnecessary while it is on.
        compose.onNodeWithText("Recommended").performClick()

        assert(asked == false) { "Recommended starts on, so the first tap turns it off: $asked" }
    }

    private fun showDiscover(
        query: HubQuery = HubQuery(text = "gguf", sort = HubSort.TRENDING),
        onQueryChange: (String) -> Unit = {},
        onSearch: () -> Unit = {},
        onSortChange: (HubSort) -> Unit = {},
        onPhoneSizedChange: (Boolean) -> Unit = {},
        onOfficialOnlyChange: (Boolean) -> Unit = {},
        onRecommendedOnlyChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                DiscoverScreen(
                    state = DiscoverUiState(
                        query = query,
                        results = listOf(
                            hub("MadeAgents/Hammer2.1-1.5b", downloads = 41_206, likes = 118),
                            hub("LiquidAI/LFM2-1.2B-GGUF", downloads = 33_512, likes = 91),
                        ),
                        parameterCeilingBillions = 8,
                    ),
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onSortChange = onSortChange,
                    onRuntimeToggled = { _, _ -> },
                    onFiltersChange = {},
                    onPhoneSizedChange = onPhoneSizedChange,
                    onOfficialOnlyChange = onOfficialOnlyChange,
                    onRecommendedOnlyChange = onRecommendedOnlyChange,
                    onClearFilters = {},
                    onOpenModel = {},
                    onCloseModel = {},
                    onContextLengthChange = {},
                    onDownload = { _, _ -> },
                )
            }
        }
    }

    private fun hub(id: String, downloads: Int, likes: Int) = HubModel(
        id = id,
        downloads = downloads,
        likes = likes,
        isGated = false,
        tags = listOf("gguf", "text-generation"),
        updatedAt = "2026-08-01T00:00:00.000Z",
        pipelineTag = "text-generation",
    )
}
