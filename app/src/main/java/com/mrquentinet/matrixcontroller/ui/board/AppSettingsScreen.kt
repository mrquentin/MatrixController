package com.mrquentinet.matrixcontroller.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.SettingValue
import com.mrquentinet.matrixcontroller.ui.common.message
import kotlin.math.roundToInt

/**
 * Fully schema-driven: every widget below reads [AppSettingSchema.type]/`min`/`max`/`maxLen` at
 * runtime. Nothing here may reference a specific app name or setting key — new apps and new
 * settings must render correctly with zero changes to this file.
 */
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.navigateBack.collect { onBack() }
    }

    val errorMessage = (state as? AppSettingsUiState.Loaded)?.error?.message()
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.errorShown()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val loaded = state as? AppSettingsUiState.Loaded
            MediumFlexibleTopAppBar(
                title = { Text(loaded?.appName ?: stringResource(R.string.app_settings_subtitle)) },
                subtitle = { Text(stringResource(R.string.app_settings_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (loaded != null && loaded.dirty) {
                        IconButton(onClick = viewModel::save, enabled = !loaded.saving) {
                            if (loaded.saving) {
                                CircularWavyProgressIndicator(Modifier.size(24.dp))
                            } else {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.action_save),
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
        when (val current = state) {
            AppSettingsUiState.Loading -> Box(contentModifier, Alignment.Center) {
                CircularWavyProgressIndicator()
            }

            is AppSettingsUiState.Failed -> Box(contentModifier, Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = current.error.message(),
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = viewModel::load, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            is AppSettingsUiState.Loaded -> if (current.schema.isEmpty()) {
                Box(contentModifier, Alignment.Center) {
                    Text(
                        text = stringResource(R.string.settings_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = contentModifier,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current.schema, key = { it.key }) { schema ->
                        SettingWidget(
                            schema = schema,
                            value = current.valueFor(schema.key),
                            onChange = { viewModel.setEdit(schema.key, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingWidget(
    schema: AppSettingSchema,
    value: SettingValue?,
    onChange: (SettingValue) -> Unit,
) {
    when (schema.type) {
        AppSettingType.Bool ->
            BoolSetting(schema, (value as? SettingValue.BoolValue)?.value ?: false, onChange)

        AppSettingType.IntType ->
            IntSetting(schema, (value as? SettingValue.IntValue)?.value ?: schema.min ?: 0, onChange)

        AppSettingType.StringType ->
            StringSetting(schema, (value as? SettingValue.StringValue)?.value ?: "", onChange)

        AppSettingType.ColorType ->
            ColorSetting(schema, (value as? SettingValue.IntValue)?.value ?: 0, onChange)

        is AppSettingType.Unknown -> UnknownSetting(schema, value)
    }
}

@Composable
private fun SettingContainer(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun BoolSetting(schema: AppSettingSchema, checked: Boolean, onChange: (SettingValue) -> Unit) {
    SettingContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = schema.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = { onChange(SettingValue.BoolValue(it)) })
        }
    }
}

@Composable
private fun IntSetting(schema: AppSettingSchema, value: Int, onChange: (SettingValue) -> Unit) {
    // The schema is contractually supposed to always carry min/max for an int; these fallbacks
    // only guard against a malformed board response, they never widen a real declared range.
    val lo = schema.min ?: 0
    val hi = schema.max ?: (lo + 100)
    SettingContainer {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(schema.label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lo < hi) {
            Slider(
                value = value.toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
                onValueChange = { onChange(SettingValue.IntValue(it.roundToInt())) },
                valueRange = lo.toFloat()..hi.toFloat(),
                steps = (hi - lo - 1).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun StringSetting(schema: AppSettingSchema, value: String, onChange: (SettingValue) -> Unit) {
    val maxLen = schema.maxLen
    SettingContainer {
        OutlinedTextField(
            value = value,
            onValueChange = { new ->
                if (maxLen == null || new.length <= maxLen) onChange(SettingValue.StringValue(new))
            },
            label = { Text(schema.label) },
            supportingText = maxLen?.let {
                { Text(stringResource(R.string.settings_chars_used, value.length, it)) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColorSetting(schema: AppSettingSchema, rgb: Int, onChange: (SettingValue) -> Unit) {
    // Wire format is a plain 24-bit 0xRRGGBB int with no alpha; every conversion below stays in
    // that space rather than going through an ARGB Android Color, so there is no alpha byte to
    // accidentally leak into the request.
    val clamped = rgb.coerceIn(schema.min ?: 0, schema.max ?: 0xFFFFFF)
    SettingContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(schema.label, style = MaterialTheme.typography.titleMedium)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF000000.toInt() or clamped))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            )
        }
        ColorChannelSlider(stringResource(R.string.settings_color_channel_r), channel(clamped, 16)) {
            onChange(SettingValue.IntValue(withChannel(clamped, 16, it)))
        }
        ColorChannelSlider(stringResource(R.string.settings_color_channel_g), channel(clamped, 8)) {
            onChange(SettingValue.IntValue(withChannel(clamped, 8, it)))
        }
        ColorChannelSlider(stringResource(R.string.settings_color_channel_b), channel(clamped, 0)) {
            onChange(SettingValue.IntValue(withChannel(clamped, 0, it)))
        }
    }
}

@Composable
private fun ColorChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(16.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            steps = 254,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

private fun channel(rgb: Int, shift: Int): Int = (rgb shr shift) and 0xFF

private fun withChannel(rgb: Int, shift: Int, value: Int): Int =
    (rgb and (0xFF shl shift).inv()) or (value.coerceIn(0, 255) shl shift)

@Composable
private fun UnknownSetting(schema: AppSettingSchema, value: SettingValue?) {
    val rawType = (schema.type as? AppSettingType.Unknown)?.raw ?: "?"
    val rawValue = when (value) {
        is SettingValue.RawValue -> value.json
        is SettingValue.BoolValue -> value.value.toString()
        is SettingValue.IntValue -> value.value.toString()
        is SettingValue.StringValue -> value.value
        null -> null
    }
    SettingContainer {
        Text(schema.label, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.settings_unsupported, rawType),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rawValue != null) {
            Text(
                text = rawValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
