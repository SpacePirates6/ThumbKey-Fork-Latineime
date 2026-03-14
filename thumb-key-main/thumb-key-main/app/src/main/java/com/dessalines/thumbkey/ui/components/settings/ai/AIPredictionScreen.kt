package com.dessalines.thumbkey.ui.components.settings.ai

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.prediction.AdapterTrainerHelper
import com.dessalines.thumbkey.prediction.ModelPaths
import com.dessalines.thumbkey.prediction.PredictionBridge
import com.dessalines.thumbkey.prediction.SuggestionBlacklist
import com.dessalines.thumbkey.prediction.TrainingLog
import com.dessalines.thumbkey.prediction.UserDictionaryObserver
import com.dessalines.thumbkey.ui.components.settings.about.SettingsDivider
import com.dessalines.thumbkey.utils.SimpleTopAppBar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SwitchPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIPredictionScreen(
    navController: NavController,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Shared preferences for AI settings
    val aiPrefs = remember { ctx.getSharedPreferences("ai_settings", android.content.Context.MODE_PRIVATE) }
    var aiEnabled by remember { mutableStateOf(aiPrefs.getBoolean("ai_enabled", true)) }
    var autocorrectEnabled by remember { mutableStateOf(aiPrefs.getBoolean("autocorrect_enabled", true)) }
    var autocorrectThreshold by remember { mutableFloatStateOf(aiPrefs.getFloat("autocorrect_threshold", 4.0f)) }
    var transformerWeight by remember { mutableFloatStateOf(aiPrefs.getFloat("transformer_weight", 3.4f)) }

    // Blacklist
    val blacklist = remember { SuggestionBlacklist(ctx) }
    var blacklistedWords by remember { mutableStateOf(blacklist.currentBlacklist.toList()) }
    var showAddBlacklistDialog by remember { mutableStateOf(false) }
    var newBlacklistWord by remember { mutableStateOf("") }

    // Models
    var models by remember { mutableStateOf(ModelPaths.getModels(ctx)) }

    // Training
    val trainingLog = remember { TrainingLog(ctx) }
    var trainingEntries by remember { mutableIntStateOf(trainingLog.size) }
    var isTraining by remember { mutableStateOf(false) }
    var trainingProgress by remember { mutableFloatStateOf(0f) }

    // User dictionary
    val userDict = remember { UserDictionaryObserver(ctx) }
    var userDictCount by remember { mutableIntStateOf(userDict.words.size) }

    // Model import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            try {
                ModelPaths.importModel(ctx, it)
                models = ModelPaths.getModels(ctx)
                Toast.makeText(ctx, "Model imported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Save helpers
    fun saveAiEnabled(enabled: Boolean) {
        aiEnabled = enabled
        aiPrefs.edit().putBoolean("ai_enabled", enabled).apply()
    }

    fun saveAutocorrectEnabled(enabled: Boolean) {
        autocorrectEnabled = enabled
        aiPrefs.edit().putBoolean("autocorrect_enabled", enabled).apply()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SimpleTopAppBar(
                text = stringResource(R.string.ai_prediction),
                navController = navController,
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .background(color = MaterialTheme.colorScheme.surface)
                    .imePadding(),
            ) {
                ProvidePreferenceTheme {
                    LazyColumn {
                        // ── AI toggle ───────────────────────────────
                        item {
                            SwitchPreference(
                                value = aiEnabled,
                                onValueChange = { saveAiEnabled(it) },
                                title = { Text(stringResource(R.string.ai_prediction_enabled)) },
                                summary = { Text(stringResource(R.string.ai_prediction_enabled_desc)) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }

                        // ── Autocorrect toggle ──────────────────────
                        item {
                            SwitchPreference(
                                value = autocorrectEnabled,
                                onValueChange = { saveAutocorrectEnabled(it) },
                                title = { Text(stringResource(R.string.autocorrect_enabled)) },
                                summary = { Text(stringResource(R.string.autocorrect_enabled_desc)) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Spellcheck,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }

                        item { SettingsDivider() }

                        // ── Model management ────────────────────────
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.model_management)) },
                                summary = {
                                    val count = models.size
                                    Text(
                                        if (count > 0) "$count model(s) available"
                                        else stringResource(R.string.no_model_loaded),
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Storage,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { },
                            )
                        }

                        // List models
                        items(models) { model ->
                            Preference(
                                title = { Text(model.name) },
                                summary = { Text("${model.length() / 1024 / 1024}MB") },
                                onClick = {
                                    // Could add model selection here in the future
                                },
                            )
                        }

                        // Import model
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.import_model)) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FileUpload,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                            )
                        }

                        item { SettingsDivider() }

                        // ── Blacklist ────────────────────────────────
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.blacklisted_words)) },
                                summary = {
                                    Text(
                                        if (blacklistedWords.isEmpty()) {
                                            stringResource(R.string.no_blacklisted_words)
                                        } else {
                                            "${blacklistedWords.size} words"
                                        },
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Block,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { showAddBlacklistDialog = true },
                            )
                        }

                        // List blacklisted words
                        items(blacklistedWords) { word ->
                            Preference(
                                title = { Text(word) },
                                onClick = {
                                    blacklist.removeWord(word)
                                    blacklistedWords = blacklist.currentBlacklist.toList()
                                },
                                summary = { Text("Tap to remove") },
                            )
                        }

                        item { SettingsDivider() }

                        // ── Training ────────────────────────────────
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.training_data)) },
                                summary = {
                                    Text(stringResource(R.string.training_entries, trainingEntries))
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.ModelTraining,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { },
                            )
                        }

                        // Training progress
                        if (isTraining) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Text(
                                        stringResource(R.string.training_in_progress),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { trainingProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        // Start training button
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.start_training)) },
                                summary = {
                                    Text(
                                        if (trainingEntries < 10) "Need at least 10 entries"
                                        else "Fine-tune the model to your writing style",
                                    )
                                },
                                onClick = {
                                    if (trainingEntries < 10) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Need at least 10 training entries")
                                        }
                                        return@Preference
                                    }
                                    isTraining = true
                                    trainingProgress = 0f
                                    scope.launch {
                                        val progressFlow = MutableSharedFlow<Float>(
                                            replay = 0,
                                            extraBufferCapacity = 1,
                                        )
                                        // Collect progress updates
                                        launch {
                                            progressFlow.collect { p -> trainingProgress = p }
                                        }

                                        val success = try {
                                            AdapterTrainerHelper.trainFromLog(
                                                context = ctx,
                                                trainingLog = trainingLog,
                                                progressFlow = progressFlow,
                                            )
                                        } catch (_: Exception) {
                                            false
                                        }

                                        isTraining = false
                                        snackbarHostState.showSnackbar(
                                            if (success) ctx.getString(R.string.training_complete)
                                            else ctx.getString(R.string.training_failed),
                                        )
                                        models = ModelPaths.getModels(ctx)
                                    }
                                },
                            )
                        }

                        // Clear training data
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.clear_training_data)) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    trainingLog.clear()
                                    trainingEntries = 0
                                },
                            )
                        }

                        item { SettingsDivider() }

                        // ── User dictionary info ────────────────────
                        item {
                            Preference(
                                title = { Text("User dictionary") },
                                summary = {
                                    Text(stringResource(R.string.user_dictionary_count, userDictCount))
                                },
                                onClick = { },
                            )
                        }

                        // ── Crash guard reset ───────────────────────
                        item {
                            Preference(
                                title = { Text(stringResource(R.string.reset_crash_guard)) },
                                summary = { Text(stringResource(R.string.reset_crash_guard_desc)) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    PredictionBridge.resetCrashGuard(ctx)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Crash guard reset. Restart keyboard to load AI.")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )

    // ── Add blacklist word dialog ────────────────────────────────────
    if (showAddBlacklistDialog) {
        AlertDialog(
            onDismissRequest = { showAddBlacklistDialog = false },
            title = { Text(stringResource(R.string.add_blacklisted_word)) },
            text = {
                OutlinedTextField(
                    value = newBlacklistWord,
                    onValueChange = { newBlacklistWord = it },
                    label = { Text("Word") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newBlacklistWord.isNotBlank()) {
                            blacklist.addWord(newBlacklistWord)
                            blacklistedWords = blacklist.currentBlacklist.toList()
                            newBlacklistWord = ""
                        }
                        showAddBlacklistDialog = false
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddBlacklistDialog = false }) { Text("Cancel") }
            },
        )
    }
}
