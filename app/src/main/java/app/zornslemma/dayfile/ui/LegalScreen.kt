@file:OptIn(ExperimentalMaterial3Api::class)

package app.zornslemma.dayfile.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.zornslemma.dayfile.R

@Composable
fun LegalScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // License texts are loaded from res/raw (not strings.xml) so they remain untranslated.
    val appMitLicense = remember { context.loadRawResource(R.raw.license_mit) }
    val reorderableApacheLicense = remember {
        context.loadRawResource(R.raw.license_apache_reorderable)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_legal_information)) },
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
                Modifier.verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = screenContentHorizontalPadding)
        ) {
            // We manually implement the vertical border so it is part of the scrollable region, not
            // something which reduces the size of the scrollable region. This feels a bit better to
            // me.
            Spacer(modifier = Modifier.height(screenContentVerticalPadding))

            // Our license
            Text(
                text = stringResource(R.string.title_app_name_license),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.copyright),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LicenseText(licenseText = appMitLicense)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.legal_third_party_licenses),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            ThirdPartyLicense(
                libraryName = stringResource(R.string.legal_reorderable_library_name),
                licenseName = stringResource(R.string.legal_apache_2_0),
                licenseText = reorderableApacheLicense,
            )

            Spacer(modifier = Modifier.height(screenContentVerticalPadding))
        }
    }
}

@Composable
private fun ThirdPartyLicense(libraryName: String, licenseName: String, licenseText: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "$libraryName $emDash $licenseName",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LicenseText(licenseText)
    }
}

@Composable
private fun LicenseText(licenseText: String) {
    Text(text = licenseText, style = MaterialTheme.typography.bodySmall)
}

private fun Context.loadRawResource(resId: Int): String {
    return resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
