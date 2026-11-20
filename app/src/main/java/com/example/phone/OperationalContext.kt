package com.example.phone

/**
 * Brief, in-memory operational context between consecutive orders. It never stores transcripts or conversations:
 * only the identity of the media app and of the app that Insto verifiably launched, plus when that happened.
 *
 * The floating panel closes after every handoff, so the context outlives one panel and lives in the process.
 * It ends after [ttlMillis] or [maxTurns] pertinent turns, whichever comes first, and when the user cancels or
 * closes the assistant. It is never persisted nor sent to model providers.
 */
class OperationalContext(
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val ttlMillis: Long = TTL_MILLIS,
  private val maxTurns: Int = MAX_TURNS,
) {
  /** An app Insto opened or controlled with a resolved package. Not proof that anything is playing. */
  data class AppReference(val packageName: String, val label: String)
  data class Snapshot(val mediaApp: MediaApp?, val lastApp: AppReference?)

  private var mediaApp: MediaApp? = null
  private var lastApp: AppReference? = null
  private var setAt = 0L
  private var turns = 0

  @Synchronized fun snapshot(): Snapshot? {
    expireIfNeeded()
    if (mediaApp == null && lastApp == null) return null
    return Snapshot(mediaApp, lastApp)
  }

  /** The media app to prefer when an unqualified media control finds several sessions. */
  @Synchronized fun preferredMediaPackage(): String? = snapshot()?.mediaApp?.packageName

  /**
   * Called once per new top-level request, before interpretation. Volume and media controls are independent of
   * the topic and do not age the context; other requests count as pertinent turns.
   */
  @Synchronized fun beginTurn(local: PhoneRoute) {
    expireIfNeeded()
    if (mediaApp == null && lastApp == null) return
    val command = (local as? PhoneRoute.Local)?.command
    if (command is PhoneCommand.Volume || command is PhoneCommand.Media) return
    turns++
    if (turns > maxTurns) clear()
  }

  /** Only verified outcomes establish identity. Failures leave the previous context untouched. */
  @Synchronized fun record(command: PhoneCommand, result: PhoneActionResult) {
    val handedOff = result.outcome == PhoneActionOutcome.HANDOFF || result.outcome == PhoneActionOutcome.COMPLETED
    if (!handedOff || result.retainRequest || result.appOptions.isNotEmpty()) return
    when (command) {
      is PhoneCommand.OpenApp -> {
        // The package must have been resolved and launched: a name alone proves nothing.
        val pkg = result.launchedAppPackage ?: return
        // Opening another app is a new explicit reference: a previous media app no longer applies.
        set(MediaApp.entries.firstOrNull { it.packageName == pkg }, AppReference(pkg, command.name))
      }
      is PhoneCommand.MusicSearch -> command.app?.let { set(it, AppReference(it.packageName, it.label)) }
      is PhoneCommand.SpotifySearch -> set(MediaApp.SPOTIFY, AppReference(MediaApp.SPOTIFY.packageName, MediaApp.SPOTIFY.label))
      is PhoneCommand.YouTubeSearch -> set(MediaApp.YOUTUBE, AppReference(MediaApp.YOUTUBE.packageName, MediaApp.YOUTUBE.label))
      is PhoneCommand.Media -> MediaApp.entries.firstOrNull { it.packageName == command.packageName }
        ?.let { set(it, AppReference(it.packageName, it.label)) }
      else -> Unit
    }
  }

  @Synchronized fun clear() {
    mediaApp = null; lastApp = null; setAt = 0L; turns = 0
  }

  private fun set(media: MediaApp?, app: AppReference) {
    mediaApp = media
    lastApp = app
    setAt = clock()
    turns = 0
  }

  private fun expireIfNeeded() {
    if ((mediaApp != null || lastApp != null) && clock() - setAt >= ttlMillis) clear()
  }

  /** A contextual reading. [source] is the request the existing grammar understood, for clarification replies. */
  data class Resolution(val route: PhoneRoute, val source: String, val usedContext: Boolean)

  /**
   * Resolves a single request that the explicit grammar did not, using only well-defined references. Returns null
   * for anything ambiguous, so the normal circuit (Gemini, clarification) decides.
   */
  fun resolve(text: String, now: () -> java.util.Calendar = { java.util.Calendar.getInstance() }): Resolution? {
    if (RequestSafetyPolicy.rejection(text) != null || RequestSafetyPolicy.isQuestion(text) ||
      withoutDiscourseLead(ConversationSession.splitSteps(text)).size != 1 || '?' in text || '¿' in text) return null
    val request = PhoneCommandParser.canonicalRequest(PhoneCommandParser.normalize(text))
    val stripped = discourse.find(request)?.let { request.substring(it.range.last + 1).trim() }
    // «Ahora bajá el volumen»: the marker adds nothing; the rest must be an explicit local command.
    if (!stripped.isNullOrBlank() && RequestSafetyPolicy.rejection(stripped) == null &&
      !RequestSafetyPolicy.hasJoinedActions(stripped)) {
      when (val route = PhoneCommandParser(now).parse(stripped)) {
        is PhoneRoute.Local -> {
          val enriched = enrich(route.command)
          return Resolution(PhoneRoute.Local(enriched), stripped, usedContext = enriched != route.command)
        }
        is PhoneRoute.Clarify -> return Resolution(route, stripped, usedContext = false)
        else -> Unit
      }
    }
    val current = snapshot() ?: return null
    val body = PhoneCommandParser.canonicalRequest(stripped ?: request)
    if (reopen.matches(body)) {
      val app = current.lastApp ?: return null
      return Resolution(PhoneRoute.Local(PhoneCommand.OpenApp(app.label, app.packageName)), body, usedContext = true)
    }
    val media = current.mediaApp ?: return null
    val play = play.matchEntire(body) ?: return null
    val query = play.groupValues[1].trim()
    if (query.length !in 2..80 || notMusic.containsMatchIn(query) || vague.matches(query) || otherTarget.containsMatchIn(query) ||
      PhoneCommandParser.number(query) != null) return null
    // Keep the user's spelling of the title, as the explicit grammar does.
    val folded = FoldedRequest.of(text)
    val at = folded.text.lastIndexOf(query)
    val title = (if (at >= 0) folded.capture(at until at + query.length) else query).trimEnd('.', '!', ' ')
    return Resolution(PhoneRoute.Local(PhoneCommand.MusicSearch(title, media)), body, usedContext = true)
  }

  /** Fills only the media app of a music request that named none. An explicit app always prevails. */
  fun enrich(command: PhoneCommand): PhoneCommand {
    if (command !is PhoneCommand.MusicSearch || command.app != null) return command
    val media = snapshot()?.mediaApp ?: return command
    return command.copy(app = media)
  }

  companion object {
    const val TTL_MILLIS = 120_000L
    const val MAX_TURNS = 3
    private val discourseOnly = Regex("^(?:y )?(?:ahora|ahora si|ahi|bueno|listo|dale)(?: ahora)?$")

    /** «Ahora, poné…»: a leading discourse marker split off by a comma is not a step of its own. */
    fun withoutDiscourseLead(steps: List<String>): List<String> =
      if (steps.size >= 2 && discourseOnly.matches(PhoneCommandParser.normalize(steps.first()))) steps.drop(1) else steps

    /** Process-wide holder: one per app process, gone with it. */
    val shared = OperationalContext()
    private val discourse = Regex("^(?:y )?(?:ahora|ahora si|ahi|bueno|listo|dale)(?: ahora)? ")
    private val play = Regex("^(?:pone|reproduci) (?:(?:la |una )?(?:cancion|tema|musica) (?:de )?|algo de )?(.+?)(?: ahi| en esa app| en la app)?$")
    private val reopen = Regex("^(?:volve a abrir(?:la|lo)?|abri(?:la|lo)(?: de nuevo| otra vez)?|volve a la app)$")
    // Settings, devices and other actions are not titles.
    private val notMusic = Regex(
      "^(?:(?:el|la|los|las|un|una|mi|en|a)\\s+)?(?:volumen|brillo|sonido|modo|alarma|despertador|timer|temporizador|linterna|" +
        "wifi|bluetooth|cronometro|recordatorio|evento|mensaje|llamada|tele|television|aire|luz|camara|telefono|celular|" +
        "silencio|no molestar|vibrar|pausa|play|siguiente|anterior|ruta|mapa|foto|video)\\b",
    )
    // Too vague to be a title: the explicit grammar or the model asks what to play.
    private val vague = Regex("(?:algo|eso|esto|esa|ese|lo mismo|otra cosa|otra|otro|musica|una cancion|un tema|la cancion|el tema|lo de siempre)")
    private val otherTarget = Regex("\\ben (?:netflix|deezer|tidal|soundcloud|apple music|amazon music|youtube music|la radio|la tele|el auto|el living|la cocina)$")
  }
}
