package com.senzaproject.gamespace.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.settings.SettingsViewModel
import kotlinx.coroutines.launch

private data class OnboardingPage(@StringRes val title: Int, @StringRes val body: Int)

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val pages = remember {
        listOf(
            OnboardingPage(R.string.onboarding_1_title, R.string.onboarding_1_body),
            OnboardingPage(R.string.onboarding_2_title, R.string.onboarding_2_body),
            OnboardingPage(R.string.onboarding_3_title, R.string.onboarding_3_body),
        )
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val isLast = index == pages.lastIndex
    val scope = rememberCoroutineScope()

    // Persist first, then navigate, so the flag is never lost when this screen is removed.
    fun finish() {
        scope.launch {
            viewModel.completeOnboarding()
            onFinished()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { finish() }) { Text(stringResource(R.string.onboarding_skip)) }
        }

        AnimatedContent(targetState = index, label = "onboarding-page") { page ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(pages[page].title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(pages[page].body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == index) 24.dp else 8.dp, 8.dp)
                            .background(
                                if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                }
            }
            Button(
                onClick = { if (isLast) finish() else index++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next))
            }
        }
    }
}
