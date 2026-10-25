package app.zmby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.NavEntry
import app.zmby.log.ZLog
import app.zmby.ui.Nav
import app.zmby.ui.RemoteInput
import app.zmby.ui.Route
import app.zmby.ui.screens.DetailScreen
import app.zmby.ui.screens.HomeScreen
import app.zmby.ui.screens.ImportScreen
import app.zmby.ui.screens.LoginScreen
import app.zmby.ui.screens.SearchScreen
import app.zmby.ui.screens.SeriesScreen
import app.zmby.ui.screens.ServersScreen
import app.zmby.ui.screens.SettingsScreen
import app.zmby.ui.theme.ZmbyTheme

class MainActivity : ComponentActivity() {
    private var jank: androidx.metrics.performance.JankStats? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZmbyTheme {
                // Preferences are read on a background thread at startup; until then an empty frame.
                val ready by zmby.ready.collectAsState()
                if (ready) App()
            }
        }
        jank = Diagnostics.track(this)
    }

    override fun onResume() {
        super.onResume()
        jank?.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jank?.isTrackingEnabled = false
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            RemoteInput.lastKeyAt = event.eventTime
            if (event.repeatCount == 0) ZLog.v("Key", android.view.KeyEvent.keyCodeToString(event.keyCode))
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun App() {
    val library = androidx.compose.ui.platform.LocalContext.current.zmby.library
    val servers by library.servers.collectAsState()
    if (servers.isEmpty()) {
        // First start: add a server by hand or import a batch from the phone.
        var importing by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        if (importing) ImportScreen(onDone = { importing = false }) else LoginScreen(onDone = {}, onImport = { importing = true })
        return
    }
    val stack = remember { mutableStateListOf(NavEntry(0, Route.Home)) }
    var nextId by remember { androidx.compose.runtime.mutableLongStateOf(1L) }
    var homeTab by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()
    val nav = remember(stack) {
        object : Nav {
            override fun go(route: Route) {
                ZLog.i("Nav", "${stack.last().route} -> $route")
                stack.add(NavEntry(nextId++, route))
            }
            override fun back() {
                if (stack.size > 1) {
                    val from = stack.removeAt(stack.lastIndex)
                    states.removeState(from.id)
                    from.viewModelStore.clear()
                    stack.last().focus.beginRestore()
                    ZLog.i("Nav", "${from.route} -> back to ${stack.last().route} (focus ${stack.last().focus.lastKey})")
                }
            }
            override fun replaceAll(route: Route) {
                ZLog.i("Nav", "reset to $route")
                stack.forEach { states.removeState(it.id); it.viewModelStore.clear() }
                stack.clear()
                stack.add(NavEntry(nextId++, route))
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val entry = stack.last()
    val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
    androidx.compose.runtime.LaunchedEffect(entry.id) { Diagnostics.screen(activity, entry.route.javaClass.simpleName) }
    key(entry.id) {
        // Each back-stack entry owns its screen's ViewModels; they are cleared when it is popped.
        CompositionLocalProvider(LocalNavEntry provides entry, androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides entry) {
            states.SaveableStateProvider(entry.id) {
                when (val route = entry.route) {
                    Route.Home -> HomeScreen(nav, homeTab) { homeTab = it }
                    is Route.Detail -> DetailScreen(nav, route.server, route.itemId)
                    is Route.Series -> SeriesScreen(nav, route.server, route.seriesId, route.episodeId)
                    Route.Search -> SearchScreen(nav)
                    Route.Servers -> ServersScreen(nav)
                    Route.AddServer -> LoginScreen(onDone = { nav.back() }, onImport = { nav.go(Route.Import) })
                    Route.Import -> ImportScreen(onDone = nav::back)
                    Route.Settings -> SettingsScreen(nav)
                    is Route.Person -> app.zmby.ui.screens.PersonScreen(nav, route.personKey, route.server, route.personId)
                    is Route.DataSource -> app.zmby.ui.screens.DataSourceScreen(nav, route.feature)
                }
            }
        }
    }
}
