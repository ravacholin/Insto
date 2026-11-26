package com.example.phone

import java.security.MessageDigest

internal data class ShortcutKey(val packageName: String, val id: String, val profile: Long) {
  val token: String = MessageDigest.getInstance("SHA-256")
    .digest("$profile\u0000$packageName\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) }
}

internal data class ShortcutPreferences(val alias: String = "", val favorite: Boolean = false, val enabled: Boolean = true)

internal data class AppShortcut(
  val key: ShortcutKey,
  val name: String,
  val app: String,
  val manifest: Boolean = false,
  val dynamic: Boolean = false,
  val pinned: Boolean = false,
  val available: Boolean = true,
  val revision: Long = 0,
  val preferences: ShortcutPreferences = ShortcutPreferences(),
  val grant: String? = null,
) {
  val usable: Boolean get() = available && preferences.enabled
  val label: String get() = "$name · $app"
  val localLabel: String get() = if (key.profile == 0L) label else "$label · Perfil " + key.profile
  fun command() = PhoneCommand.Platform(PlatformAction.EXECUTE_APP_SHORTCUT, key.token)
}

internal fun shortcutRemoteTargets(permitted: Boolean, entries: List<AppShortcut>): List<String> =
  if (permitted) entries.filter(AppShortcut::usable).mapNotNull(AppShortcut::grant).sorted() else emptyList()

internal fun shortcutRemoteTarget(permitted: Boolean, entries: List<AppShortcut>, token: String): AppShortcut? =
  if (permitted) entries.singleOrNull { it.usable && it.grant == token } else null

internal object ShortcutResolver {
  const val CAPABILITY = "execute_app_shortcut"
  const val CONTEXT = "app-shortcut"
  val token = Regex("[0-9a-f]{64}")
  private val explicit = Regex("^(?:(?:ejecuta|ejecutar|abri|abre|abrir|lanza|usa) )?(?:(?:el|un) )?(?:acceso directo|shortcut|atajo(?: de aplicacion)?)(?: (.+))?$")
  private val open = Regex("^(?:abri|abre|abrir|ejecuta|ejecutar|lanza|usa) (.+)$")
  private val negation = Regex("\\b(?:no|nunca|jamas|tampoco|ni)\\b")
  private fun folded(text: String) = PhoneCommandParser.normalize(text.replace(Regex("[\"“”«»]"), ""))
  private fun normalized(text: String) = PhoneCommandParser.canonicalRequest(folded(text))

  fun isShortcutOrder(text: String): Boolean = explicit.matches(normalized(text))

  private fun canResolve(text: String): Boolean =
    RequestSafetyPolicy.refusal(text) == null && !RequestSafetyPolicy.isGeneralRequest(text) &&
      !negation.containsMatchIn(normalized(RequestSafetyPolicy.withoutQuotedText(text))) &&
      '?' !in text && '¿' !in text && ConversationSession.splitSteps(text).size == 1

  fun explicitTarget(text: String): String? = if (canResolve(text))
    explicit.matchEntire(normalized(text))?.groupValues?.get(1)?.removePrefix("de ") else null

  fun route(text: String, native: PhoneRoute, entries: List<AppShortcut>): PhoneRoute {
    val genericApp = (native as? PhoneRoute.Local)?.command as? PhoneCommand.OpenApp
    if (native != PhoneRoute.Instinct && genericApp == null) return native
    if (!canResolve(text)) return if (isShortcutOrder(text))
      PhoneRoute.Unsupported("Indicá un solo acceso directo para abrir ahora.", reason = RejectionReason.UNSAFE_OR_HYPOTHETICAL)
    else native
    val query = explicitTarget(text)
    val syntax = normalized(text)
    if (query == null && entries.any { folded(it.app) == folded(genericApp?.name.orEmpty()) }) return native
    val body = query ?: open.matchEntire(syntax)?.groupValues?.get(1)
    val qualified = body != null && (body.contains(" de ") || body.contains(" en ")) && targets(body, entries).any { it.app != null }
    if (query == null && genericApp != null && !qualified) return native
    val exact = if (body != null) match(body, entries, includeUnavailable = true)
      else entries.filter { it.preferences.alias.isNotBlank() && normalized(it.preferences.alias) == syntax }
    if (exact.isNotEmpty()) {
      val available = exact.filter(AppShortcut::usable)
      return if (available.isNotEmpty()) result(available)
      else PhoneRoute.Unsupported("Ese acceso directo está deshabilitado o ya no está disponible.")
    }
    if (body != null && (query != null || qualified) && RequestSafetyPolicy.quotedTexts(text).isEmpty()) {
      val found = match(body, entries, fuzzy = true)
      if (found.isNotEmpty()) return result(found)
    }
    // Explicit shortcut orders are retained for clarification. They must never become a generic app launch.
    return when {
      query != null -> PhoneRoute.Instinct
      qualified -> PhoneRoute.Unsupported("No encontré ese acceso directo en la aplicación indicada.")
      else -> native
    }
  }

  private data class Target(val label: String, val app: String?)

  private fun appNames(entry: AppShortcut) = listOf(folded(entry.app), folded(entry.key.packageName))

  private fun targets(query: String, entries: List<AppShortcut>): List<Target> = buildList {
    entries.flatMap(::appNames).distinct().filter(String::isNotBlank).forEach { app ->
      for (separator in listOf(" de ", " en ")) {
        if (query.endsWith(separator + app)) add(Target(query.removeSuffix(separator + app), app))
        if (query.startsWith(app + separator)) add(Target(query.removePrefix(app + separator), app))
      }
    }
  }.ifEmpty { listOf(Target(query, null)) }

  fun match(query: String, entries: List<AppShortcut>, fuzzy: Boolean = false, includeUnavailable: Boolean = false): List<AppShortcut> {
    val syntax = folded(query)
    if (syntax.isBlank()) return emptyList()
    val active = if (includeUnavailable) entries else entries.filter(AppShortcut::usable)
    val targets = targets(syntax, entries)
    val scored = active.mapNotNull { entry ->
      val labels = listOf(folded(entry.name), folded(entry.preferences.alias)).filter(String::isNotBlank)
      val names = appNames(entry)
      val score = targets.filter { it.app == null || it.app in names }.minOfOrNull { target ->
        labels.minOfOrNull { label ->
          val distance = if (fuzzy) editDistance(label, target.label) else if (label == target.label) 0 else Int.MAX_VALUE
          when {
            distance == 0 -> if (target.app == null) 1 else 0
            fuzzy && target.label.length >= 5 && distance <= if (target.label.length >= 10) 2 else 1 -> 2 + distance
            else -> Int.MAX_VALUE
          }
        } ?: Int.MAX_VALUE
      } ?: Int.MAX_VALUE
      if (score == Int.MAX_VALUE) null else entry to score
    }
    val best = scored.minOfOrNull { it.second } ?: return emptyList()
    return scored.filter { it.second == best }.map { it.first }
  }

  // Only static publisher labels enter the optional provider shortlist. Voice aliases and dynamic/pinned-only
  // labels can contain personal data and are used solely on-device.
  fun candidates(query: String, entries: List<AppShortcut>): List<AppShortcut> {
    val active = entries.filter { it.usable && it.manifest && !it.dynamic }
    val targets = targets(folded(query), entries)
    return active.mapNotNull { entry ->
      val name = folded(entry.name)
      val names = appNames(entry)
      val score = targets.filter { it.app == null || it.app in names }.maxOfOrNull { target ->
        val terms = target.label.split(' ').filter { it.length >= 3 }.toSet()
        val overlap = terms.intersect(name.split(' ').toSet()).size
        if (target.app != null) overlap + 2 else overlap
      } ?: 0
      if (score > 0) entry to score else null
    }.sortedByDescending { it.second }.take(5).map { it.first }
  }

  fun providerInput(text: String, candidates: List<AppShortcut>): String? {
    if (RequestSafetyPolicy.quotedTexts(text).isNotEmpty() || RequestSafetyPolicy.fixedActionHasExtraParameters(text)) return null
    val target = explicitTarget(text)?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return null
    val vocabulary = candidates.flatMap { folded(it.label).split(' ') }.toSet()
    val connectors = setOf("a", "al", "de", "del", "el", "en", "la", "las", "los", "para", "por", "un", "una", "y")
    return target.takeIf { it.split(' ').all { term -> term in vocabulary || term in connectors } }
  }

  fun result(entries: List<AppShortcut>): PhoneRoute {
    entries.singleOrNull()?.let { return PhoneRoute.Local(it.command()) }
    val options = entries.take(8)
    return PhoneRoute.Clarify(
      "¿Cuál acceso directo querés? " + options.mapIndexed { i, it -> "${i + 1}. ${it.localLabel}" }.joinToString(" · "),
      CONTEXT + ":" + options.joinToString(",") { it.key.token })
  }

  fun reply(context: String, text: String, entries: List<AppShortcut>): PhoneRoute? {
    if (!context.startsWith("$CONTEXT:") || !canResolve(text)) return null
    val ids = context.substringAfter(':').split(',')
    val list = ids.map { id -> entries.singleOrNull { it.key.token == id && it.usable } }
    val index = PhoneCommandParser.choiceIndex(normalized(text))
    if (index != null) return list.getOrNull(index)?.let { PhoneRoute.Local(it.command()) }
      ?: PhoneRoute.Unsupported("Esa opción ya no está disponible. Repetí el pedido.")
    val found = match(text, list.filterNotNull())
    return if (found.isEmpty()) null else result(found)
  }

  private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.length > 160 || b.length > 160 || kotlin.math.abs(a.length - b.length) > 2) return Int.MAX_VALUE
    var previous = IntArray(b.length + 1) { it }
    var next = IntArray(b.length + 1)
    for (i in a.indices) {
      next[0] = i + 1
      for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
      val swap = previous
      previous = next
      next = swap
    }
    return previous.last()
  }
}

/** Preserve user settings across rediscovery. An altered or removed publisher entry retires its remote grant. */
internal fun reconcileShortcuts(old: List<AppShortcut>, discovered: List<AppShortcut>): List<AppShortcut> {
  val previous = old.associateBy(AppShortcut::key)
  val fresh = discovered.associateBy(AppShortcut::key)
  return discovered.map { entry ->
    val prior = previous[entry.key]
    entry.copy(grant = prior?.grant?.takeIf {
      entry.available && prior.revision == entry.revision && prior.name == entry.name && prior.app == entry.app
    })
  } + old.filter { it.key !in fresh }.map { it.copy(available = false, grant = null) }
}

internal fun validShortcutAlias(value: String) =
  value.length <= 100 && value.none(Char::isISOControl) &&
    (value.isEmpty() || PhoneCommandParser.normalize(value).isNotBlank())

internal fun encodeShortcutPreferences(values: Map<String, ShortcutPreferences>) = org.json.JSONObject().apply {
  values.toSortedMap().forEach { (id, prefs) ->
    put(id, org.json.JSONObject().put("alias", prefs.alias).put("favorite", prefs.favorite).put("enabled", prefs.enabled))
  }
}

internal fun decodeShortcutPreferences(json: org.json.JSONObject): Map<String, ShortcutPreferences> {
  require(json.length() <= 10_000)
  return json.keys().asSequence().associateWith { id ->
    require(ShortcutResolver.token.matches(id))
    val entry = json.getJSONObject(id)
    require(entry.keys().asSequence().toSet() == setOf("alias", "favorite", "enabled"))
    val alias = entry.get("alias")
    val favorite = entry.get("favorite")
    val enabled = entry.get("enabled")
    require(alias is String && validShortcutAlias(alias) && favorite is Boolean && enabled is Boolean)
    ShortcutPreferences(alias, favorite, enabled)
  }
}
