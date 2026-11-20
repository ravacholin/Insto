package com.example.phone

import java.util.Calendar

/**
 * Matches a request against learned phrases beyond exact equality, in this order:
 *
 * 1. the same phrase in canonical form ([PhraseCanonicalizer]), or with one tolerated transcription difference;
 * 2. templates of phrases the user taught, with a typed parameter: «bajá la música al {número}»;
 * 3. templates of phrases learned automatically.
 *
 * Exact equality on the stored key is checked by the store before this index. Within a level, two different
 * commands are ambiguous: the index answers nothing and the normal flow decides. Lower levels are not consulted
 * after an ambiguous one, so a doubt never turns into a weaker match.
 *
 * Parameter values are never trusted: like [PhrasePatterns], the value is parsed by [PhoneCommandParser] from a
 * canonical request, so ranges, rounding and clarifications are those of the native command.
 */
class LearnedPhraseIndex(items: List<Item>) {
  data class Item(val phrase: String, val command: PhoneCommand, val manual: Boolean, val generalize: Boolean = true)

  enum class Slot(val placeholder: String) { NUMBER("{número}"), DURATION("{duración}"), TIME("{hora}"), APP("{app}") }

  /** [example] is the slot text of the source phrase, in canonical form. */
  data class Template(val prefix: List<String>, val suffix: List<String>, val slot: Slot, val example: String, val item: Item) {
    val text: String get() = (prefix + slot.placeholder + suffix).joinToString(" ")
  }

  data class Match(val command: PhoneCommand, val source: Item, val template: Template? = null)

  private sealed interface Outcome {
    data class Found(val match: Match) : Outcome
    data object Ambiguous : Outcome
    data object None : Outcome
  }

  private val canonical: Map<List<String>, List<Item>>
  private val manualTemplates: List<Template>
  private val automaticTemplates: List<Template>
  /** Template derived from each stored phrase, for the settings screen. */
  val templates: Map<String, Template>
  val size: Int = items.size

  init {
    canonical = items.groupBy { PhraseCanonicalizer.canonicalTokens(PhraseCanonicalizer.tokens(it.phrase)) }
    val derived = items.mapNotNull { item -> templateOf(item)?.let { item to it } }
    templates = derived.associate { (item, template) -> item.phrase to template }
    manualTemplates = derived.filter { it.first.manual }.map { it.second }
    automaticTemplates = derived.filterNot { it.first.manual }.map { it.second }
  }

  /** [key] is a learned-phrase key. [resolveApp] returns the one launchable app with exactly that label, or null. */
  fun match(
    key: String,
    resolveApp: (String) -> PhoneCommand.OpenApp? = { null },
    now: () -> Calendar = { Calendar.getInstance() },
  ): Match? {
    if (size == 0) return null
    val tokens = PhraseCanonicalizer.canonicalTokens(PhraseCanonicalizer.tokens(key))
    if (tokens.isEmpty()) return null
    for (level in listOf<() -> Outcome>(
      { phrases(tokens) },
      { templateLevel(manualTemplates, tokens, resolveApp, now) },
      { templateLevel(automaticTemplates, tokens, resolveApp, now) },
    )) {
      when (val outcome = level()) {
        is Outcome.Found -> return outcome.match
        Outcome.Ambiguous -> return null
        Outcome.None -> Unit
      }
    }
    return null
  }

  /** The canonical form of [key] collides with a stored phrase taught by the user. */
  fun hasManual(key: String): Boolean =
    canonical[PhraseCanonicalizer.canonicalTokens(PhraseCanonicalizer.tokens(key))].orEmpty().any { it.manual }

  private fun phrases(tokens: List<String>): Outcome {
    canonical[tokens]?.let { exact -> return decide(exact.map { Match(it.command, it) }) }
    val near = canonical.filterKeys { PhraseCanonicalizer.nearMatch(tokens, it) }.values.flatten()
    return decide(near.map { Match(it.command, it) })
  }

  private fun templateLevel(
    templates: List<Template>,
    tokens: List<String>,
    resolveApp: (String) -> PhoneCommand.OpenApp?,
    now: () -> Calendar,
  ): Outcome {
    if (templates.isEmpty()) return Outcome.None
    val exact = mutableListOf<Match>()
    val near = mutableListOf<Match>()
    for (template in templates) {
      val differences = literalDifferences(tokens, template) ?: continue
      val slot = tokens.subList(template.prefix.size, tokens.size - template.suffix.size)
      val command = resolve(template.slot, slot, resolveApp, now) ?: continue
      (if (differences == 0) exact else near) += Match(command, template.item, template)
    }
    return if (exact.isNotEmpty()) decide(exact) else decide(near)
  }

  /** Number of tolerated differences in the literal text, or null when the template does not apply. */
  private fun literalDifferences(tokens: List<String>, template: Template): Int? {
    val p = template.prefix.size
    val s = template.suffix.size
    if (tokens.size <= p + s) return null
    var differences = 0
    for (i in 0 until p) if (tokens[i] != template.prefix[i]) {
      if (++differences > 1 || !PhraseCanonicalizer.tolerable(tokens[i], template.prefix[i])) return null
    }
    for (i in 0 until s) {
      val actual = tokens[tokens.size - s + i]
      if (actual != template.suffix[i]) {
        if (++differences > 1 || !PhraseCanonicalizer.tolerable(actual, template.suffix[i])) return null
      }
    }
    return differences
  }

  private fun decide(matches: List<Match>): Outcome = when (matches.distinctBy { it.command }.size) {
    0 -> Outcome.None
    1 -> Outcome.Found(matches.first())
    else -> Outcome.Ambiguous
  }

  companion object {
    private const val MAX_APP_TOKENS = 3
    private val blockedSlotWords = setOf(
      "no", "ni", "sin", "si", "nunca", "jamas", "tampoco", "salvo", "excepto", "mientras", "cuando", "pero",
      "despues", "antes", "luego", "tambien",
    )
    /** «cuarenta y cinco» is a number; in an app name a conjunction would join two orders. */
    private val blockedAppWords = setOf("y", "o", "e", "u")

    private fun action(slot: Slot): PhrasePatterns.Action? = when (slot) {
      Slot.NUMBER -> PhrasePatterns.Action.VOLUME
      Slot.DURATION -> PhrasePatterns.Action.TIMER
      Slot.TIME -> PhrasePatterns.Action.ALARM
      Slot.APP -> null
    }

    private fun slot(action: PhrasePatterns.Action): Slot = when (action) {
      PhrasePatterns.Action.VOLUME -> Slot.NUMBER
      PhrasePatterns.Action.TIMER -> Slot.DURATION
      PhrasePatterns.Action.ALARM -> Slot.TIME
    }

    /**
     * The command for a slot value, validated exactly like the native request. An hour that needs morning or
     * evening returns null: the normal flow asks, the template never guesses.
     */
    internal fun resolve(
      slot: Slot,
      tokens: List<String>,
      resolveApp: (String) -> PhoneCommand.OpenApp?,
      now: () -> Calendar,
    ): PhoneCommand? {
      val text = tokens.joinToString(" ")
      if (tokens.isEmpty() || tokens.any { it in blockedSlotWords }) return null
      if (slot == Slot.APP) {
        if (tokens.size > MAX_APP_TOKENS || text.length > 40 || text.any(Char::isDigit) || tokens.any { it in blockedAppWords } ||
          PhrasePatterns.forbiddenLiteral.containsMatchIn(text)) return null
        return resolveApp(text)
      }
      val action = action(slot) ?: return null
      if (!PhrasePatterns.shapeAllowed(action, text)) return null
      val route = PhoneCommandParser(now).parse(action.canonical(text)) as? PhoneRoute.Local ?: return null
      return route.command.takeIf { PhrasePatterns.actionFor(it) == action }
    }

    /** Derives a template from one phrase whose command carries a value present, exactly once, in its own text. */
    internal fun templateOf(item: Item): Template? {
      if (!item.generalize) return null
      val tokens = PhraseCanonicalizer.canonicalTokens(PhraseCanonicalizer.tokens(item.phrase))
      val command = item.command
      if (command is PhoneCommand.OpenApp) {
        val name = PhraseCanonicalizer.tokens(PhoneCommandParser.normalize(command.name))
        if (name.isEmpty() || name.size > MAX_APP_TOKENS) return null
        val starts = (0..tokens.size - name.size).filter { tokens.subList(it, it + name.size) == name }
        val start = starts.singleOrNull() ?: return null
        val before = tokens.subList(0, start)
        val after = tokens.subList(start + name.size, tokens.size)
        if (!PhrasePatterns.literalAllowed(before + after)) return null
        return Template(before, after, Slot.APP, name.joinToString(" "), item)
      }
      val action = PhrasePatterns.actionFor(command) ?: return null
      val observation = PhrasePatterns.observe(tokens.joinToString(" "), command) ?: return null
      return Template(PhraseCanonicalizer.tokens(observation.prefix), PhraseCanonicalizer.tokens(observation.suffix),
        slot(action), observation.example, item)
    }

    /**
     * «Bajá la música al {número}»: the user's own words with the example value replaced by the placeholder.
     * Falls back to the stored key when the written text does not contain the example.
     */
    fun describe(template: Template, display: String?): String {
      val example = template.example
      for (source in listOfNotNull(display, template.item.phrase)) {
        val words = source.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        for (start in words.indices) for (end in start + 1..minOf(words.size, start + 6)) {
          if (PhoneCommandParser.normalize(words.subList(start, end).joinToString(" ")) != example) continue
          val text = (words.subList(0, start) + template.slot.placeholder + words.subList(end, words.size)).joinToString(" ")
          return text.replaceFirstChar { it.uppercase() }
        }
      }
      return template.text.replaceFirstChar { it.uppercase() }
    }
  }
}
