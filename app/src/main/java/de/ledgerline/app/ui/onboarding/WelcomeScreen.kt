package de.ledgerline.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import de.ledgerline.app.R
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.launch

/**
 * First-run onboarding as a swipeable multi-step pager: an intro page plus one page
 * per optional permission (location / contacts). The permissions no longer
 * have to fit on a single screen. "Skip" or the final "Get started" hands off to
 * pairing via [onGetStarted]; every permission is optional.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val context = LocalContext.current
    fun granted(vararg perms: String) =
        perms.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var locationGranted by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var contactsGranted by remember { mutableStateOf(granted(Manifest.permission.READ_CONTACTS)) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> locationGranted = r.values.any { it } }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> contactsGranted = r.values.any { it } }

    val pageCount = 3
    val pager = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    val isLast = pager.currentPage >= pageCount - 1

    de.ledgerline.app.ui.theme.LedgerlineBackground {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> IntroPage()
                1 -> PermissionPage(
                    icon = Icons.Outlined.Place, tint = Brand.tintTeal,
                    title = stringResource(R.string.welcome_location_title),
                    body = stringResource(R.string.welcome_location_body),
                    granted = locationGranted,
                    allowLabel = stringResource(R.string.welcome_location_allow),
                    grantedLabel = stringResource(R.string.welcome_location_granted),
                    onAllow = { locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                )
                else -> PermissionPage(
                    icon = Icons.Outlined.Contacts, tint = Brand.tintBlue,
                    title = stringResource(R.string.welcome_contacts_title),
                    body = stringResource(R.string.welcome_contacts_body),
                    granted = contactsGranted,
                    allowLabel = stringResource(R.string.welcome_contacts_allow),
                    grantedLabel = stringResource(R.string.welcome_contacts_granted),
                    onAllow = { contactsLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) },
                )
            }
        }

        PageDots(pageCount, pager.currentPage)
        Spacer(Modifier.height(16.dp))
        PrimaryGradientButton(
            text = stringResource(if (isLast) R.string.welcome_get_started else R.string.welcome_next),
            onClick = { if (isLast) onGetStarted() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.height(52.dp),
        )
        // Permissions are optional — allow skipping straight to pairing from any step.
        TextButton(onClick = onGetStarted, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.welcome_skip))
        }
    }
    }
}

@Composable
private fun IntroPage() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_ledgerline_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Column(Modifier.fillMaxWidth().cardSurface().padding(20.dp)) {
            StepIndicator(1, stringResource(R.string.welcome_step_connect))
            Spacer(Modifier.height(16.dp))
            StepIndicator(2, stringResource(R.string.welcome_step_unlock))
        }
    }
}

@Composable
private fun PermissionPage(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    granted: Boolean,
    allowLabel: String,
    grantedLabel: String,
    onAllow: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconChip(icon, tint = tint, size = 72.dp)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(grantedLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            OutlinedButton(onClick = onAllow, shape = RoundedCornerShape(14.dp)) { Text(allowLabel) }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val active = i == current
            Box(
                Modifier
                    .padding(4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** A numbered circle followed by its step label. */
@Composable
fun StepIndicator(number: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
