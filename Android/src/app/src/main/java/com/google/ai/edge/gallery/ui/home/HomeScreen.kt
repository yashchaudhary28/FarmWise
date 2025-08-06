/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.ui.common.TaskIcon
import com.google.ai.edge.gallery.ui.common.tos.TosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.homePageTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "GMHomeScreen"
private const val TASK_COUNT_ANIMATION_DURATION = 250
private const val ANIMATION_INIT_DELAY = 100L
private const val TOP_APP_BAR_ANIMATION_DURATION = 600
private const val TITLE_FIRST_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION = 600
private const val TITLE_SECOND_LINE_ANIMATION_DURATION2 = 800
private const val TITLE_SECOND_LINE_ANIMATION_START =
  ANIMATION_INIT_DELAY + (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.5).toInt()
private const val TASK_LIST_ANIMATION_START = TITLE_SECOND_LINE_ANIMATION_START + 110
private const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
private const val TASK_CARD_ANIMATION_DURATION = 600
private const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
private const val CONTENT_COMPOSABLES_OFFSET_Y = 16

/** Navigation destination data */
object HomeScreenDestination {
  @StringRes val titleRes = R.string.gram_mitra_app_name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showImportModelSheet by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val selectedLocalModelFileUri = remember { mutableStateOf<Uri?>(null) }
  val selectedImportedModelInfo = remember { mutableStateOf<ImportedModel?>(null) }
  val coroutineScope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  val filePickerLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val fileName = getFileName(context = context, uri = uri)
          Log.d(TAG, "Selected file: $fileName")
          if (fileName != null && !fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) {
            showUnsupportedFileTypeDialog = true
          } else {
            selectedLocalModelFileUri.value = uri
            showImportDialog = true
          }
        } ?: run { Log.d(TAG, "No file selected or URI is null.") }
      } else {
        Log.d(TAG, "File picking cancelled.")
      }
    }

  // Show home screen content when TOS has been accepted.
  if (!showTosDialog) {
    var loadingModelAllowlistDelayed by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.loadingModelAllowlist) {
      if (uiState.loadingModelAllowlist) {
        delay(200)
        if (uiState.loadingModelAllowlist) {
          loadingModelAllowlistDelayed = true
        }
      } else {
        loadingModelAllowlistDelayed = false
      }
    }

    if (loadingModelAllowlistDelayed) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(
          stringResource(R.string.loading_model_list),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    if (!loadingModelAllowlistDelayed && !uiState.loadingModelAllowlist) {
      Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          val progress =
            rememberDelayedAnimationProgress(
              initialDelay = ANIMATION_INIT_DELAY - 50,
              animationDurationMs = TOP_APP_BAR_ANIMATION_DURATION,
              animationLabel = "top bar",
            )
          Box(
            modifier =
              Modifier.graphicsLayer {
                alpha = progress
                translationY = ((-16).dp * (1 - progress)).toPx()
              }
          ) {
            CenterAlignedTopAppBar(
              title = {
                Text(
                  text = stringResource(R.string.gram_mitra_app_name),
                  style = MaterialTheme.typography.titleLarge,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              },
              colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
              actions = {
                IconButton(onClick = { showSettingsDialog = true }) {
                  Icon(Icons.Filled.Add, contentDescription = "Settings")
                }
              },
              scrollBehavior = scrollBehavior,
            )
          }
        },
        floatingActionButton = {
          SmallFloatingActionButton(
            onClick = { showImportModelSheet = true },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.secondary,
          ) {
            Icon(Icons.Filled.Add, contentDescription = "Import Model")
          }
        },
      ) { innerPadding ->
        Box(
          contentAlignment = Alignment.TopCenter,
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
          Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
          ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
              Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                AppTitle()
                IntroText()
              }

              TaskList(tasks = uiState.tasks, navigateToTaskScreen = navigateToTaskScreen)
            }

            SnackbarHost(
              hostState = snackbarHostState,
              modifier = Modifier.align(alignment = Alignment.BottomCenter).padding(bottom = 32.dp),
            )
          }
        }
      }
    }
  }

  if (showTosDialog) {
    TosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  if (showImportModelSheet) {
    ModalBottomSheet(onDismissRequest = { showImportModelSheet = false }, sheetState = sheetState) {
      Text(
        "Import model",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
      )
      Box(
        modifier =
          Modifier.clickable {
            coroutineScope.launch {
              delay(200)
              showImportModelSheet = false
              val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "*/*"
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                }
              filePickerLauncher.launch(intent)
            }
          }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
          Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "")
          Text("From local model file")
        }
      }
    }
  }

  if (showImportDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedImportedModelInfo.value = info
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }

  if (showImportingDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      selectedImportedModelInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            modelManagerViewModel.addImportedLlmModel(info = it)
            showImportingDialog = false
            scope.launch { snackbarHostState.showSnackbar("Model imported successfully") }
          },
        )
      }
    }
  }

  if (showUnsupportedFileTypeDialog) {
    AlertDialog(
      onDismissRequest = { showUnsupportedFileTypeDialog = false },
      title = { Text("Unsupported file type") },
      text = { Text("Only \".task\" or \".litertlm\" file type is supported.") },
      confirmButton = {
        Button(onClick = { showUnsupportedFileTypeDialog = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }

  if (uiState.loadingModelAllowlistError.isNotEmpty()) {
    AlertDialog(
      icon = {
        Icon(Icons.Rounded.Error, contentDescription = "", tint = MaterialTheme.colorScheme.error)
      },
      title = { Text(uiState.loadingModelAllowlistError) },
      text = { Text("Please check your internet connection and try again later.") },
      onDismissRequest = { modelManagerViewModel.loadModelAllowlist() },
      confirmButton = {
        TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) { Text("Retry") }
      },
    )
  }
}

@Composable
private fun AppTitle() {
  val firstLineText = "Gram-Mitra"
  val secondLineText = "AI Assistant for Agriculture"
  val titleColor = MaterialTheme.customColors.appTitleGradientColors.getOrElse(1) { MaterialTheme.colorScheme.primary }

  Box {
    var delay = ANIMATION_INIT_DELAY
    SwipingText(
      text = firstLineText,
      style = MaterialTheme.typography.headlineMedium,
      color = titleColor,
      animationDelay = delay,
      animationDurationMs = TITLE_FIRST_LINE_ANIMATION_DURATION,
    )
    delay += (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.3).toLong()
    SwipingText(
      text = firstLineText,
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      animationDelay = delay,
      animationDurationMs = TITLE_FIRST_LINE_ANIMATION_DURATION,
    )
  }
  Box {
    var delay = TITLE_SECOND_LINE_ANIMATION_START
    SwipingText(
      text = secondLineText,
      style = MaterialTheme.typography.headlineSmall,
      color = titleColor,
      modifier = Modifier.offset(y = (-16).dp),
      animationDelay = delay,
      animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
    )
    delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.3).toInt()
    SwipingText(
      text = secondLineText,
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.offset(y = (-16).dp),
      animationDelay = delay,
      animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
    )
    delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.6).toInt()
    RevealingText(
      text = secondLineText,
      style =
        MaterialTheme.typography.headlineSmall.copy(
          brush = linearGradient(colors = MaterialTheme.customColors.appTitleGradientColors)
        ),
      modifier = Modifier.offset(y = (-16).dp),
      animationDelay = delay,
      animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION2,
    )
  }
}

@Composable
private fun SwipingText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 1.0f,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "swiping text",
      easing = LinearEasing,
    )
  Text(
    text,
    style =
      style.copy(
        brush =
          linearGradient(
            colorStops =
              arrayOf(
                (1f + edgeGradientRelativeSize) * progress - edgeGradientRelativeSize to color,
                (1f + edgeGradientRelativeSize) * progress to Color.Transparent,
              )
          )
      ),
    modifier = modifier.graphicsLayer { alpha = progress },
  )
}

@Composable
private fun RevealingText(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 0.5f,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "revealing text",
    )
  val maskBrush =
    linearGradient(
      colorStops =
        arrayOf(
          (1f + edgeGradientRelativeSize) * progress - edgeGradientRelativeSize to
                  Color.Transparent,
          (1f + edgeGradientRelativeSize) * progress to Color.Red,
        )
    )
  Text(
    text,
    style = style,
    modifier =
      modifier
        .graphicsLayer(alpha = 0.99f, compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
          drawContent()
          drawRect(brush = maskBrush, blendMode = BlendMode.DstOut)
        },
  )
}

@Composable
private fun IntroText() {
  val url = "https://huggingface.co/litert-community"
  val linkColor = MaterialTheme.customColors.linkColor
  val uriHandler = LocalUriHandler.current

  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
      animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
      animationLabel = "intro text animation",
    )

  val introText = buildAnnotatedString {
    append("Gram-Mitra is your AI-powered assistant for agriculture and allied livelihoods. Learn more at ")
    withLink(
      link =
        LinkAnnotation.Url(
          url = url,
          styles =
            TextLinkStyles(
              style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            ),
          linkInteractionListener = { _ ->
            firebaseAnalytics?.logEvent("resource_link_click", bundleOf("link_destination" to url))
            uriHandler.openUri(url)
          },
        )
    ) {
      append("Gram-Mitra Community")
    }
  }
  Text(
    introText,
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}

@Composable
private fun TaskList(tasks: List<Task>, navigateToTaskScreen: (Task) -> Unit) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = TASK_LIST_ANIMATION_START,
      animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
      animationLabel = "task card animation",
    )
  Column(
    modifier =
      Modifier.fillMaxWidth().padding(16.dp).graphicsLayer {
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    var index = 0
    for (task in tasks) {
      TaskCard(
        task = task,
        index = index,
        onClick = { navigateToTaskScreen(task) },
        modifier = Modifier.fillMaxWidth(),
      )
      index++
    }
  }
}

@Composable
private fun TaskCard(task: Task, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val modelCount by remember {
    derivedStateOf {
      val trigger = task.updateTrigger.value
      if (trigger >= 0) {
        task.models.size
      } else {
        0
      }
    }
  }
  val modelCountLabel by remember {
    derivedStateOf {
      when (modelCount) {
        1 -> "1 Model"
        else -> "%d Models".format(modelCount)
      }
    }
  }
  var curModelCountLabel by remember { mutableStateOf("") }
  var modelCountLabelVisible by remember { mutableStateOf(true) }

  LaunchedEffect(modelCountLabel) {
    if (curModelCountLabel.isEmpty()) {
      curModelCountLabel = modelCountLabel
    } else {
      modelCountLabelVisible = false
      delay(TASK_COUNT_ANIMATION_DURATION.toLong())
      curModelCountLabel = modelCountLabel
      modelCountLabelVisible = true
    }
  }

  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = TASK_LIST_ANIMATION_START + index * TASK_CARD_ANIMATION_DELAY_OFFSET,
      animationDurationMs = TASK_CARD_ANIMATION_DURATION,
      animationLabel = "task card animation",
    )

  Card(
    modifier =
      modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick).graphicsLayer {
        alpha = progress
      },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.customColors.taskCardBgColor),
  ) {
    Row(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text(
          task.type.label,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          curModelCountLabel,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      TaskIcon(task = task, width = 40.dp)
    }
  }
}

@Composable
private fun rememberDelayedAnimationProgress(
  initialDelay: Long = 0,
  animationDurationMs: Int,
  animationLabel: String,
  easing: Easing = FastOutSlowInEasing,
): Float {
  var startAnimation by remember { mutableStateOf(false) }
  val progress: Float by
  animateFloatAsState(
    if (startAnimation) 1f else 0f,
    label = animationLabel,
    animationSpec = tween(durationMillis = animationDurationMs, easing = easing),
  )
  LaunchedEffect(Unit) {
    delay(initialDelay)
    startAnimation = true
  }
  return progress
}

fun getFileName(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          return cursor.getString(nameIndex)
        }
      }
    }
  } else if (uri.scheme == "file") {
    return uri.lastPathSegment
  }
  return null
}
