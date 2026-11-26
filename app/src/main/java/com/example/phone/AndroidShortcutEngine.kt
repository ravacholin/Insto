package com.example.phone

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import com.example.assist.AssistantRoleHelper
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class ShortcutAccess { CHECKING, GRANTED, DENIED, LOCKED, ERROR }

internal data class ShortcutCatalog(
  val access: ShortcutAccess = ShortcutAccess.CHECKING,
  val entries: List<AppShortcut> = emptyList(),
  val partial: Boolean = false,
)

internal class AndroidShortcutEngine private constructor(private val app: Context) {
  private val launcher = app.getSystemService(LauncherApps::class.java)
  private val users = app.getSystemService(UserManager::class.java)
  private val file = AtomicFile(File(app.noBackupFilesDir, "app-shortcuts.json"))
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val updates = Channel<Unit>(Channel.CONFLATED)
  private val pendingLock = Any()
  private var fullRefresh = false
  private val packages = mutableMapOf<Pair<UserHandle, String>, Boolean>()
  private val catalog = MutableStateFlow(ShortcutCatalog())
  val state = catalog.asStateFlow()
  private var rows = emptyMap<String, AppShortcut>()
  private val iconSources = java.util.concurrent.ConcurrentHashMap<ShortcutKey, ShortcutInfo>()
  private var preferences = emptyMap<String, ShortcutPreferences>()
  private var loaded = false
  private var callbackRegistered = false
  private var reconciled = false
  private var accessibleProfiles = emptySet<Long>()
  @Volatile private var foreground = java.lang.ref.WeakReference<Activity>(null)

  private val callback = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(user, listOf(packageName), revoke = true)
    override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(user, listOf(packageName), revoke = true)
    override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(user, listOf(packageName), revoke = true)
    override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
      invalidate(user, packageNames.asList(), revoke = replacing)
    override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
      invalidate(user, packageNames.asList(), revoke = replacing)
    override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) = invalidate(user, packageNames.asList())
    override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) = invalidate(user, packageNames.asList())
    override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) =
      invalidate(user, listOf(packageName))
  }

  init {
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_USER_UNLOCKED)
      addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
      addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
      addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
      addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
      addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
      if (Build.VERSION.SDK_INT >= 35) {
        addAction(Intent.ACTION_PROFILE_AVAILABLE)
        addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
        addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
        addAction(Intent.ACTION_PROFILE_ADDED)
        addAction(Intent.ACTION_PROFILE_REMOVED)
      }
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) = requestRefresh(full = true)
    }
    // These system broadcasts are protected, including on versions before receiver visibility flags.
    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else if (Build.VERSION.SDK_INT >= 25) app.registerReceiver(receiver, filter)
    scope.launch {
      for (signal in updates) {
        val (force, dirtyPackages) = synchronized(pendingLock) {
          val result = fullRefresh to packages.toMap()
          fullRefresh = false
          packages.clear()
          result
        }
        synchronized(lock) {
          runCatching { reconcile(force, dirtyPackages) }.onFailure {
            reconciled = false
            publish(ShortcutAccess.ERROR)
          }
        }
      }
    }
    requestRefresh()
  }

  fun visible(activity: Activity) {
    foreground = java.lang.ref.WeakReference(activity)
    requestRefresh()
  }

  fun hidden(activity: Activity) {
    if (foreground.get() === activity) foreground.clear()
  }

  suspend fun refresh() = withContext(Dispatchers.IO) {
    synchronized(lock) { reconcile(force = true, changes = emptyMap()) }
  }

  fun requestRefresh(full: Boolean = false) {
    synchronized(pendingLock) { fullRefresh = fullRefresh || full }
    updates.trySend(Unit)
  }

  private fun invalidate(user: UserHandle, names: List<String>, revoke: Boolean = false) {
    synchronized(pendingLock) {
      names.forEach { name ->
        val key = user to name
        packages[key] = revoke || packages[key] == true
      }
    }
    updates.trySend(Unit)
  }

  fun hasPermission(): Boolean = Build.VERSION.SDK_INT >= 25 &&
    runCatching { launcher?.hasShortcutHostPermission() == true }.getOrDefault(false)

  private fun access(): ShortcutAccess = when {
    !hasPermission() -> ShortcutAccess.DENIED
    users?.isUserUnlocked != true -> ShortcutAccess.LOCKED
    else -> ShortcutAccess.GRANTED
  }

  private fun profiles(): Map<Long, UserHandle> {
    val visible = runCatching {
      if (Build.VERSION.SDK_INT >= 26) launcher?.profiles.orEmpty() else users?.userProfiles.orEmpty()
    }.getOrDefault(emptyList())
    return (visible + Process.myUserHandle()).distinct().mapNotNull { user ->
      users?.getSerialNumberForUser(user)?.takeIf { it >= 0 }?.let { it to user }
    }.toMap()
  }

  private fun isAvailable(user: UserHandle): Boolean = runCatching {
    users?.isUserUnlocked(user) == true && users.isQuietModeEnabled(user).not()
  }.getOrDefault(false)

  private fun query(user: UserHandle, packageName: String? = null, id: String? = null): List<ShortcutInfo> {
    check(Build.VERSION.SDK_INT >= 25 && android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    var flags = ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED
    if (Build.VERSION.SDK_INT >= 31 && AssistantRoleHelper.isDefaultAssistant(app)) {
      flags = flags or ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER
    }
    val request = ShortcutQuery().setQueryFlags(flags)
    if (packageName != null) request.setPackage(packageName)
    if (id != null) request.setShortcutIds(listOf(id))
    return checkNotNull(launcher?.getShortcuts(request, user))
  }

  private fun appInfo(packageName: String, user: UserHandle): ApplicationInfo? = try {
    if (Build.VERSION.SDK_INT >= 26) launcher?.getApplicationInfo(packageName, 0, user)
    else if (user == Process.myUserHandle()) app.packageManager.getApplicationInfo(packageName, 0) else null
  } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }

  private fun discover(serial: Long, user: UserHandle, packageName: String?): List<AppShortcut> {
    return query(user, packageName).groupBy { it.getPackage() }.flatMap { (pkg, shortcuts) ->
      val info = appInfo(pkg, user)
      val appLabel = info?.let { app.packageManager.getApplicationLabel(it).toString() } ?: pkg
      val enabled = info?.enabled == true && info.flags and ApplicationInfo.FLAG_SUSPENDED == 0
      shortcuts.map { shortcut ->
        val label = shortcut.shortLabel?.toString()?.takeIf(String::isNotBlank)
          ?: shortcut.longLabel?.toString()?.takeIf(String::isNotBlank) ?: "Acceso directo"
        val manifest = shortcut.isDeclaredInManifest
        val dynamic = shortcut.isDynamic
        val key = ShortcutKey(pkg, shortcut.id, serial)
        iconSources[key] = shortcut
        AppShortcut(key, label, appLabel, manifest, dynamic,
          shortcut.isPinned || !manifest && !dynamic, enabled && shortcut.isEnabled, shortcut.lastChangedTimestamp)
      }
    }
  }

  private fun reconcile(force: Boolean, changes: Map<Pair<UserHandle, String>, Boolean>) {
    load()
    val access = access()
    // Package churn while permission is lost must still invalidate old grants if Android notified us.
    val previousRows = rows
    if (changes.values.any { it }) {
      val profileHandles = profiles()
      rows = rows.mapValues { (_, entry) ->
        val user = profileHandles[entry.key.profile]
        if (user != null && changes[user to entry.key.packageName] == true) entry.copy(grant = null) else entry
      }
    }
    if (access != ShortcutAccess.GRANTED) {
      reconciled = false
      if (rows != previousRows) save()
      publish(access)
      return
    }
    if (!callbackRegistered) {
      launcher?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
      callbackRegistered = true
    }
    val all = force || !reconciled || state.value.access != ShortcutAccess.GRANTED
    if (!all && changes.isEmpty()) return
    val currentProfiles = profiles()
    accessibleProfiles = currentProfiles.filterValues(::isAvailable).keys
    val updated = rows.toMutableMap()
    if (all) {
      // Removed profiles are never allowed to authorize a newly created profile.
      updated.replaceAll { _, entry ->
        if (entry.key.profile !in currentProfiles) entry.copy(available = false, grant = null) else entry
      }
    }
    var failed = false
    for ((serial, user) in currentProfiles) {
      val targets = if (all) listOf(null) else changes.keys.filter { it.first == user }.map { it.second }
      if (targets.isEmpty()) continue
      for (pkg in targets) {
        val old = updated.values.filter { it.key.profile == serial && (pkg == null || it.key.packageName == pkg) }
        if (serial !in accessibleProfiles) {
          old.forEach { updated[it.key.token] = it.copy(available = false) }
          continue
        }
        val discovered = try { discover(serial, user, pkg) } catch (_: RuntimeException) {
          failed = true
          old.forEach { updated[it.key.token] = it.copy(available = false) }
          continue
        }
        for (entry in reconcileShortcuts(old, discovered)) {
          if (!entry.available && entry.grant == null && entry.key.token !in preferences && discovered.none { it.key == entry.key }) {
            updated.remove(entry.key.token)
          } else updated[entry.key.token] = entry
        }
      }
    }
    rows = updated
    if (rows != previousRows) save()
    reconciled = !failed
    publish(if (failed && !hasPermission()) ShortcutAccess.DENIED else ShortcutAccess.GRANTED,
      partial = failed || accessibleProfiles.size < currentProfiles.size)
  }

  private fun publish(access: ShortcutAccess, partial: Boolean = catalog.value.partial) {
    val previous = catalog.value
    val entries = if (access == ShortcutAccess.GRANTED) rows.values.filter { it.key.profile in accessibleProfiles }.map {
      it.copy(preferences = preferences[it.key.token] ?: ShortcutPreferences())
    } else emptyList()
    iconSources.keys.retainAll(entries.filter(AppShortcut::available).map(AppShortcut::key).toSet())
    catalog.value = ShortcutCatalog(access, entries, access == ShortcutAccess.GRANTED && partial)
    if (shortcutRemoteTargets(previous.access == ShortcutAccess.GRANTED, previous.entries) !=
      shortcutRemoteTargets(access == ShortcutAccess.GRANTED, entries)) InstinctCatalogPublisher.changed(app)
  }

  fun parser() = PhoneCommandParser().apply {
    shortcutRoutes = { text, native -> ShortcutResolver.route(text, native, state.value.entries) }
  }

  fun summary(command: PhoneCommand): String {
    val token = (command as? PhoneCommand.Platform)?.takeIf { it.action == PlatformAction.EXECUTE_APP_SHORTCUT }?.argument
    return state.value.entries.firstOrNull { it.key.token == token }?.localLabel ?: PhoneCommandCodec.summary(command)
  }

  private fun refreshOnFirstRemoteUse() {
    if (state.value.access != ShortcutAccess.CHECKING || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
    synchronized(lock) {
      if (state.value.access == ShortcutAccess.CHECKING) runCatching { reconcile(false, emptyMap()) }
    }
  }

  fun remoteTargets(): List<String> {
    refreshOnFirstRemoteUse()
    return shortcutRemoteTargets(hasPermission(), state.value.entries)
  }

  fun remoteTarget(token: String): AppShortcut? {
    refreshOnFirstRemoteUse()
    return shortcutRemoteTarget(hasPermission(), state.value.entries, token)
  }

  fun execute(token: String, confirmed: Boolean, remote: Boolean = false, expectedRevision: Long? = null,
    canLaunch: () -> Boolean = { true }): PhoneActionResult = synchronized(lock) {
    val access = access()
    if (access != ShortcutAccess.GRANTED) {
      requestRefresh()
      return@synchronized rejected(if (access == ShortcutAccess.LOCKED) "Desbloqueá el teléfono para abrir el acceso directo."
        else "Android no concede a Insto acceso a los shortcuts. Revisá el diagnóstico en Configuración.")
    }
    refreshOnFirstRemoteUse()
    val entry = if (remote) remoteTarget(token) else state.value.entries.singleOrNull { it.key.token == token && it.usable }
    if (entry == null) return@synchronized rejected("El acceso directo no está disponible o habilitado.")
    val user = profiles()[entry.key.profile]?.takeIf(::isAvailable)
      ?: return@synchronized rejected("El perfil del acceso directo está bloqueado o ya no está disponible.")
    try {
      val shortcut = query(user, entry.key.packageName, entry.key.id).singleOrNull()
      val info = appInfo(entry.key.packageName, user)
      if (shortcut?.isEnabled != true || info?.enabled != true || info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 ||
        shortcut.lastChangedTimestamp != entry.revision ||
        shortcut.shortLabel?.toString()?.takeIf(String::isNotBlank)?.let { it != entry.name } == true) {
        invalidate(user, listOf(entry.key.packageName))
        return@synchronized rejected("El acceso directo cambió o ya no está disponible. Actualizá el catálogo y volvé a elegirlo.")
      }
      if (expectedRevision != null && expectedRevision != entry.revision)
        return@synchronized rejected("El acceso directo cambió desde la confirmación. Volvé a elegirlo.")
      if (!confirmed || !remote && expectedRevision == null) return@synchronized PhoneActionResult("¿Abrir «${entry.localLabel}»? La aplicación decide qué hace este acceso directo.",
        confirmation = entry.command().copy(content = entry.revision.toString()), outcome = PhoneActionOutcome.NEEDS_INPUT)
      val activity = foreground.get()
      if (activity?.hasWindowFocus() != true || activity.isFinishing || activity.isDestroyed ||
        app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
        return@synchronized PhoneActionResult("Abrí Insto con el teléfono desbloqueado para confirmar el acceso directo.",
          outcome = if (remote) PhoneActionOutcome.NEEDS_INPUT else PhoneActionOutcome.REJECTED)
      if (!hasPermission()) return@synchronized rejected("Android retiró el acceso a los shortcuts. Revisá el diagnóstico.")
      if (synchronized(pendingLock) { packages[user to entry.key.packageName] == true })
        return@synchronized rejected("La aplicación cambió. Volvé a elegir el acceso directo.")
      if (!canLaunch()) return@synchronized rejected("Se canceló el pedido del acceso directo.")
      launcher!!.startShortcut(entry.key.packageName, entry.key.id, null, null, user)
      PhoneActionResult("Android aceptó la solicitud de lanzamiento. Revisá el resultado en la aplicación.",
        outcome = PhoneActionOutcome.HANDOFF)
    } catch (_: ActivityNotFoundException) {
      invalidate(user, listOf(entry.key.packageName))
      rejected("Android no pudo abrir ese acceso directo. Revisá si la aplicación está disponible.")
    } catch (_: SecurityException) {
      requestRefresh()
      rejected("Android bloqueó el lanzamiento del acceso directo. Revisá los permisos y el perfil.")
    } catch (_: RuntimeException) {
      requestRefresh()
      rejected("No se pudo lanzar el acceso directo. Revisá si el perfil y la aplicación están disponibles.")
    }
  }

  suspend fun icon(entry: AppShortcut): Drawable? = withContext(Dispatchers.IO) {
    if (!hasPermission() || !entry.available) return@withContext null
    runCatching {
      val user = profiles()[entry.key.profile]?.takeIf(::isAvailable) ?: return@runCatching null
      iconSources[entry.key]?.let { launcher?.getShortcutBadgedIconDrawable(it, app.resources.displayMetrics.densityDpi) }
        ?: appInfo(entry.key.packageName, user)?.loadIcon(app.packageManager)?.let { app.packageManager.getUserBadgedIcon(it, user) }
    }.getOrNull()
  }

  suspend fun setPreferences(entry: AppShortcut, update: (ShortcutPreferences) -> ShortcutPreferences) = withContext(Dispatchers.IO) {
    synchronized(lock) {
      load()
      val id = entry.key.token
      val value = update(preferences[id] ?: ShortcutPreferences())
      require(validShortcutAlias(value.alias))
      preferences = if (value == ShortcutPreferences()) preferences - id else preferences + (id to value)
      if (!value.enabled) rows[id]?.let { rows = rows + (id to it.copy(grant = null)) }
      save()
      publish(access())
    }
  }

  suspend fun setGrant(entry: AppShortcut, allowed: Boolean) = withContext(Dispatchers.IO) {
    synchronized(lock) {
      load()
      val id = entry.key.token
      val current = rows[id] ?: return@synchronized
      if (allowed && (access() != ShortcutAccess.GRANTED || current.revision != entry.revision ||
          !current.available || preferences[id]?.enabled == false)) return@synchronized
      val grant = if (allowed) current.grant ?: newGrant() else null
      rows = rows + (id to current.copy(grant = grant))
      save()
      publish(access())
    }
  }

  private fun newGrant(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

  suspend fun backupPreferences(): Map<String, ShortcutPreferences> = withContext(Dispatchers.IO) {
    synchronized(lock) { load(); preferences }
  }

  suspend fun backupRollback(): suspend () -> Unit {
    val (previousPreferences, previousRows) = withContext(Dispatchers.IO) {
      synchronized(lock) { load(); preferences to rows }
    }
    return {
      withContext(Dispatchers.IO) {
        synchronized(lock) {
          load()
          preferences = previousPreferences
          rows = rows.mapValues { (id, entry) ->
            val prior = previousRows[id]
            if (prior != null && prior.revision == entry.revision && prior.name == entry.name && entry.available)
              entry.copy(grant = prior.grant) else entry
          }
          save()
          reconcile(force = true, changes = emptyMap())
        }
      }
    }
  }

  suspend fun restorePreferences(values: Map<String, ShortcutPreferences>, merge: Boolean) = withContext(Dispatchers.IO) {
    synchronized(lock) {
      load()
      preferences = if (merge) values + preferences else values
      rows = rows.mapValues { (id, entry) -> if (preferences[id]?.enabled == false) entry.copy(grant = null) else entry }
      save()
      reconcile(force = true, changes = emptyMap())
    }
  }

  private fun load() {
    if (loaded) return
    val root = try { JSONObject(file.readFully().toString(Charsets.UTF_8)) }
      catch (_: java.io.FileNotFoundException) { JSONObject() }
    preferences = root.optJSONObject("preferences")?.let(::decodeShortcutPreferences).orEmpty()
    val items = root.optJSONArray("entries") ?: JSONArray()
    rows = (0 until items.length()).mapNotNull { index ->
      runCatching {
        val item = items.getJSONObject(index)
        val key = ShortcutKey(item.getString("package"), item.getString("id"), item.getLong("profile"))
        AppShortcut(key, item.getString("name"), item.getString("app"), item.getBoolean("manifest"),
          item.getBoolean("dynamic"), item.getBoolean("pinned"), item.optBoolean("available"), item.getLong("revision"),
          grant = item.optString("grant").takeIf(ShortcutResolver.token::matches))
      }.getOrNull()
    }.associateBy { it.key.token }
    loaded = true
  }

  private fun save() {
    val items = JSONArray(rows.values.map { entry ->
      JSONObject().put("package", entry.key.packageName).put("id", entry.key.id).put("profile", entry.key.profile)
        .put("name", entry.name).put("app", entry.app).put("manifest", entry.manifest).put("dynamic", entry.dynamic)
        .put("pinned", entry.pinned).put("available", entry.available).put("revision", entry.revision).put("grant", entry.grant)
    })
    val content = JSONObject().put("entries", items).put("preferences", encodeShortcutPreferences(preferences))
    val stream = try { file.startWrite() } catch (error: Exception) {
      loaded = false
      throw error
    }
    try {
      stream.write(content.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      loaded = false
      throw error
    }
  }

  private fun rejected(message: String) = PhoneActionResult(message, outcome = PhoneActionOutcome.REJECTED)

  companion object {
    @Volatile private var instance: AndroidShortcutEngine? = null
    fun of(context: Context): AndroidShortcutEngine {
      val app = context.applicationContext
      return instance?.takeIf { it.app === app } ?: synchronized(this) {
        instance?.takeIf { it.app === app } ?: AndroidShortcutEngine(app).also { instance = it }
      }
    }
  }
}
