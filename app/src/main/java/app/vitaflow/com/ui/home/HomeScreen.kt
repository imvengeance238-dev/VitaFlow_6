package app.vitaflow.com.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.data.db.ProfileEntity
import app.vitaflow.com.ui.auth.AuthViewModel
import app.vitaflow.com.ui.chat.ChatScreen
import app.vitaflow.com.ui.chat.ChatViewModel
import app.vitaflow.com.ui.chat.FloatingMax
import app.vitaflow.com.ui.fitness.FitnessScreen
import app.vitaflow.com.ui.onboarding.OnboardingScreen
import app.vitaflow.com.ui.overview.OverviewScreen
import app.vitaflow.com.ui.profile.ProfileTabScreen
import app.vitaflow.com.ui.settings.SettingsScreen
import app.vitaflow.com.ui.tasks.TasksScreen
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class HomeTab(val label: String, val icon: ImageVector) {
    HOME("হোম", Icons.Rounded.Home),
    FITNESS("ফিটনেস", Icons.Rounded.FitnessCenter),
    TASKS("টাস্ক", Icons.Rounded.Checklist),
    CHAT("Max", Icons.Rounded.AutoAwesome),
    PROFILE("প্রোফাইল", Icons.Rounded.Person)
}

/** A place in the app. [part] picks the sub-tab inside Fitness (0 workout, 1 diet). */
data class Dest(val tab: HomeTab, val part: Int = 0)

/** Where to go to see the thing a change record is about. */
fun destFor(entity: String): Dest = when (entity) {
    "TASK" -> Dest(HomeTab.TASKS)
    "WORKOUT", "EXERCISE", "SESSION" -> Dest(HomeTab.FITNESS, 0)
    "MEAL", "WATER" -> Dest(HomeTab.FITNESS, 1)
    "WEIGHT" -> Dest(HomeTab.PROFILE)
    else -> Dest(HomeTab.CHAT)
}

@Composable
fun HomeScreen(account: AccountEntity, container: AppContainer, authVm: AuthViewModel) {
    val state by container.profileRepo.observe(account.id).map { it to true }
        .collectAsStateWithLifecycle(initialValue = null to false)
    val profile = state.first
    var editing by rememberSaveable { mutableStateOf(false) }

    when {
        !state.second -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalGlass.current.accent)
        }
        profile?.onboardingDone != true || editing -> OnboardingScreen(
            account = account, container = container,
            edit = profile?.onboardingDone == true,
            onDone = { editing = false },
            onCancel = if (profile?.onboardingDone == true) ({ editing = false }) else null
        )
        else -> MainShell(account, container, authVm, profile, onEditProfile = { editing = true })
    }
}

@Composable
private fun MainShell(account: AccountEntity, container: AppContainer, authVm: AuthViewModel, profile: ProfileEntity, onEditProfile: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chatVm: ChatViewModel = viewModel(
        key = "chat-${account.id}",
        factory = viewModelFactory { initializer { ChatViewModel(container, account) } }
    )
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var fitnessPart by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var miniOpen by rememberSaveable { mutableStateOf(false) }
    // the screens you came from, so Back returns to them ("TAB:part,TAB:part")
    var stackText by rememberSaveable { mutableStateOf("") }

    fun go(d: Dest) {
        val cur = Dest(tab, fitnessPart)
        if (cur != d || showSettings) {
            stackText = (stackText.split(",").filter { it.isNotEmpty() } + "${cur.tab.name}:${cur.part}").takeLast(20).joinToString(",")
        }
        tab = d.tab
        fitnessPart = d.part
        showSettings = false
    }

    fun goBack(): Boolean {
        val items = stackText.split(",").filter { it.isNotEmpty() }
        if (items.isEmpty()) return false
        val (t, p) = items.last().split(":")
        stackText = items.dropLast(1).joinToString(",")
        tab = HomeTab.valueOf(t)
        fitnessPart = p.toIntOrNull() ?: 0
        showSettings = false
        return true
    }

    // Back goes to the previous screen instead of closing the app. At the very start it closes as usual.
    BackHandler(enabled = miniOpen || showSettings || stackText.isNotEmpty() || tab != HomeTab.HOME) {
        when {
            miniOpen -> miniOpen = false
            showSettings -> showSettings = false
            goBack() -> Unit
            else -> {
                tab = HomeTab.HOME
                fitnessPart = 0
            }
        }
    }

    // Max can open screens. The chat panel then stays with you on the new screen.
    LaunchedEffect(Unit) {
        container.navigator.collect { cmd ->
            when (cmd.screen) {
                "SETTINGS" -> showSettings = true
                "HOME" -> go(Dest(HomeTab.HOME))
                "WORKOUT", "HISTORY" -> go(Dest(HomeTab.FITNESS, 0))
                "DIET" -> go(Dest(HomeTab.FITNESS, 1))
                "TASKS" -> go(Dest(HomeTab.TASKS))
                "PROFILE", "PROGRESS" -> go(Dest(HomeTab.PROFILE))
                "CHAT" -> go(Dest(HomeTab.CHAT))
            }
            if (cmd.screen != "CHAT") miniOpen = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chatVm.onLeaveChat() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            Crossfade(targetState = if (showSettings) null else tab, animationSpec = tween(160), label = "screen") { t ->
                when (t) {
                    null -> SettingsScreen(
                        account, container,
                        onBack = { showSettings = false },
                        onLogout = authVm::logout,
                        onClearChat = { scope.launch { container.chatRepo.clearAll(account.id) } }
                    )
                    HomeTab.HOME -> OverviewScreen(account, container, profile, onGo = ::go)
                    HomeTab.FITNESS -> FitnessScreen(account, container, fitnessPart, onPart = { fitnessPart = it })
                    HomeTab.TASKS -> TasksScreen(account, container.taskRepo)
                    HomeTab.CHAT -> ChatScreen(account, container, chatVm, onGo = ::go)
                    HomeTab.PROFILE -> ProfileTabScreen(account, container, profile, onEdit = onEditProfile, onSettings = { showSettings = true })
                }
            }
            if (tab != HomeTab.CHAT || showSettings) {
                FloatingMax(
                    vm = chatVm, container = container, open = miniOpen,
                    onOpen = { miniOpen = true }, onClose = { miniOpen = false },
                    onFull = { miniOpen = false; go(Dest(HomeTab.CHAT)) },
                    onGo = ::go
                )
            }
        }
        if (!showSettings) NavBar(tab, onSelect = { go(Dest(it)) })
    }
}

@Composable
private fun NavBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().background(g.glassFill).border(1.dp, g.borderBottom).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        HomeTab.entries.forEach { t ->
            val on = t == selected
            val color = if (on) g.accent else g.textSecondary
            Column(
                Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.background(if (on) g.accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(24.dp))
                }
                Text(t.label, color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
