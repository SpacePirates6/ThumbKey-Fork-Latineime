package com.dessalines.thumbkey.prediction

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SUGGESTION_BAR_HEIGHT = 36.dp

/**
 * Suggestion bar displayed above the keyboard.
 *
 * Shows up to 3 suggestions. Index 0 is the typed word (shown dimmer),
 * index 1+ are suggestions. When [willAutocorrect] is true, the top
 * suggestion (index 1) is shown with an underline.
 *
 * Always occupies a fixed height to prevent the IME input view
 * from resizing, which would cause the keyboard to bounce.
 */
@Composable
fun SuggestionBar(
    suggestions: List<Suggestion>,
    willAutocorrect: Boolean,
    onSuggestionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (suggestions.isNotEmpty()) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(SUGGESTION_BAR_HEIGHT),
    ) {
        val displaySuggestions = suggestions.toList().take(3)

        AnimatedContent(
            targetState = displaySuggestions,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "suggestionBarContent",
        ) { currentSuggestions ->
            if (currentSuggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    currentSuggestions.forEachIndexed { index, suggestion ->
                        if (index > 0) {
                            VerticalDivider(
                                modifier = Modifier
                                    .height(20.dp)
                                    .padding(horizontal = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        val isTypedWord = suggestion.kind == SuggestionKind.TYPED
                        val isAutocorrectTarget = willAutocorrect && index == 1
                        val isEmoji = suggestion.kind == SuggestionKind.EMOJI

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSuggestionSelected(index) }
                                .padding(vertical = 2.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = suggestion.word,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = if (isEmoji) 18.sp else 14.sp,
                                    fontWeight = when {
                                        isAutocorrectTarget -> FontWeight.Bold
                                        else -> FontWeight.Normal
                                    },
                                    textDecoration = if (isAutocorrectTarget) {
                                        TextDecoration.Underline
                                    } else {
                                        TextDecoration.None
                                    },
                                ),
                                color = when {
                                    isAutocorrectTarget -> MaterialTheme.colorScheme.primary
                                    isTypedWord -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
