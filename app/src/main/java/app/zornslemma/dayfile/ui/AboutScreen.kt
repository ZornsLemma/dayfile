@file:OptIn(ExperimentalMaterial3Api::class)

package app.zornslemma.dayfile.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zornslemma.dayfile.BuildConfig
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.ui.components.BulletPoint
import app.zornslemma.dayfile.ui.components.ClickableLink

@Composable
fun AboutScreen(onBack: () -> Unit, onViewLegalClick: () -> Unit) {
    val context = LocalContext.current
    // The default launcher icon is an adaptive icon (XML), which painterResource cannot
    // load directly. Retrieving the Drawable and using a Painter that draws it via the
    // native canvas renders it correctly regardless of whether it is a vector, raster, or adaptive
    // icon.
    val appIcon = remember {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_about_app_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = screenContentHorizontalPadding)
                    .verticalScroll(rememberScrollState())
        ) {
            // We manually implement the vertical border so it is part of the scrollable region, not
            // something which reduces the size of the scrollable region. This feels a bit better to
            // me.
            Spacer(modifier = Modifier.height(screenContentVerticalPadding))
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // App icon
                if (appIcon != null) {
                    Image(
                        painter = remember(appIcon) { DrawablePainter(appIcon) },
                        contentDescription = stringResource(R.string.content_description_app_icon),
                        modifier = Modifier.size(96.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Version
                val version =
                    if (BuildConfig.DEBUG) {
                        getAppVersion(context) + " " + stringResource(R.string.debug_version_suffix)
                    } else {
                        getAppVersion(context)
                    }
                Text(text = version, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Links card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.title_resources),
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.message_links_below_will_open_in_your_browser),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ClickableLink(
                        stringResource(R.string.title_user_manual),
                        stringResource(R.string.user_manual_url),
                        showRawUrl = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ClickableLink(
                        stringResource(R.string.title_source_code_on_github),
                        stringResource(R.string.github_url),
                        showRawUrl = true,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Attributions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // This is where we give credit for third-party components we are using in a
                    // readable way. The full legally compliant stuff which is not actually
                    // readable doesn't go here, it goes on LegalScreen().
                    Text(
                        stringResource(R.string.title_attributions),
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BulletPoint(
                        stringResource(R.string.message_material_design_icons_google_apache_2_0)
                    )
                    BulletPoint(stringResource(R.string.about_attribution_reorderable))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Button to legal screen
            FilledTonalButton(onClick = onViewLegalClick, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.button_view_full_legal_information))
            }
            Spacer(modifier = Modifier.height(screenContentVerticalPadding))
        }
    }
}

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size =
        Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}

private fun getAppVersion(context: Context): String {
    return try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        // minSdk is 30 (Android 11) per SPEC.md, so no legacy versionCode paths needed.
        context.getString(R.string.label_version, pInfo.versionName)
    } catch (e: PackageManager.NameNotFoundException) {
        context.getString(R.string.version_unknown)
    }
}
