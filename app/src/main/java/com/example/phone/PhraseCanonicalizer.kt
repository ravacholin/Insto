package com.example.phone

/**
 * Deterministic canonical form of a learned-phrase key, so «Che, prendeme la luz porfa», «prendé la luz» and
 * «prender la luz» compare equal.
 *
 * It runs after [com.example.data.LearnedPhraseStore.key], which already rejected questions, negations, conditions and
 * joined actions. It never drops negation or condition words. Verbs are reduced only to their own infinitive, never to
 * a synonym: «prender» and «encender» stay different. Equivalence of meaning belongs to the local grammar and Decisions.
 */
object PhraseCanonicalizer {
  private val leadingFillers = listOf(listOf("a", "ver"), listOf("che"), listOf("dale"), listOf("bueno"), listOf("eh"))
  private val anywhereFillers = listOf(listOf("por", "favor"), listOf("porfa"), listOf("porfis"), listOf("plis"), listOf("please"))

  /** Infinitive to imperative forms after accents are removed: voseo («prendé» → prende), tuteo and irregulars. */
  private val verbs: Map<String, List<String>> = mapOf(
    "prender" to listOf("prende"),
    "encender" to listOf("encende", "enciende"),
    "apagar" to listOf("apaga"),
    "subir" to listOf("subi", "sube"),
    "bajar" to listOf("baja"),
    "poner" to listOf("pone", "pon"),
    "abrir" to listOf("abri", "abre"),
    "cerrar" to listOf("cerra", "cierra"),
    "pausar" to listOf("pausa"),
    "frenar" to listOf("frena"),
    "seguir" to listOf("segui", "sigue"),
    "pasar" to listOf("pasa"),
    "sacar" to listOf("saca"),
    "activar" to listOf("activa"),
    "desactivar" to listOf("desactiva"),
    "silenciar" to listOf("silencia"),
    "iniciar" to listOf("inicia"),
    "arrancar" to listOf("arranca"),
    "reiniciar" to listOf("reinicia"),
    "reproducir" to listOf("reproduci", "reproduce"),
    "mostrar" to listOf("mostra", "muestra"),
    "fijar" to listOf("fija"),
    "dejar" to listOf("deja"),
    "tirar" to listOf("tira"),
    "hacer" to listOf("hace", "haz"),
    "dar" to listOf("da"),
  )
  private val forms: Map<String, String> = buildMap {
    for ((infinitive, imperatives) in verbs) {
      put(infinitive, infinitive)
      imperatives.forEach { put(it, infinitive) }
    }
  }
  /** Infinitives: never changed by the transcription tolerance, so «subir» cannot become «bajar» or similar. */
  val lemmas: Set<String> = verbs.keys

  // Longest first. «me» is the rioplatense benefactive («bajame el volumen») and does not change the action.
  private val clitics = listOf("melas", "melos", "mela", "melo", "las", "los", "me", "la", "lo", "le")

  /** Words the transcription tolerance never touches. */
  private val protected = setOf(
    "no", "ni", "sin", "si", "nunca", "jamas", "tampoco", "salvo", "excepto", "mientras", "cuando", "antes", "despues",
    "solo", "todo", "todos", "todas", "nada",
  )

  fun tokens(key: String): List<String> = key.split(' ').filter(String::isNotEmpty)

  fun canonical(key: String): String = canonicalTokens(tokens(key)).joinToString(" ")

  fun canonicalTokens(source: List<String>): List<String> {
    var tokens = source
    var stripped = true
    while (stripped) {
      stripped = false
      for (filler in leadingFillers) {
        if (tokens.size > filler.size && tokens.subList(0, filler.size) == filler) {
          tokens = tokens.drop(filler.size); stripped = true
        }
      }
    }
    tokens = removeAnywhere(tokens)
    // Too little left to be a phrase: keep the original words, only verbs are reduced.
    if (tokens.size < 2) tokens = source
    return tokens.flatMap(::verb)
  }

  private fun removeAnywhere(tokens: List<String>): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    outer@ while (i < tokens.size) {
      for (filler in anywhereFillers) {
        if (i + filler.size <= tokens.size && tokens.subList(i, i + filler.size) == filler) { i += filler.size; continue@outer }
      }
      result += tokens[i++]
    }
    return result
  }

  private fun verb(token: String): List<String> {
    forms[token]?.let { return listOf(it) }
    for (clitic in clitics) {
      if (!token.endsWith(clitic) || token.length <= clitic.length) continue
      val lemma = forms[token.dropLast(clitic.length)] ?: continue
      val rest = clitic.removePrefix("me")
      return if (rest.isEmpty()) listOf(lemma) else listOf(lemma, rest)
    }
    return listOf(token)
  }

  /**
   * True when [a] and [b] differ in exactly one token by one edit (insert, delete, substitute or swap two adjacent
   * letters), both have at least five letters, and that token is not a verb, a number or a protected word.
   */
  fun nearMatch(a: List<String>, b: List<String>): Boolean {
    if (a.size != b.size || a.isEmpty()) return false
    var diff = -1
    for (i in a.indices) {
      if (a[i] == b[i]) continue
      if (diff >= 0) return false
      diff = i
    }
    return diff >= 0 && tolerable(a[diff], b[diff])
  }

  /** Counts differing token positions for equal-sized lists and checks the single difference. */
  internal fun tolerable(x: String, y: String): Boolean {
    if (x.length < 5 || y.length < 5) return false
    if (x in lemmas || y in lemmas || x in protected || y in protected) return false
    if (x.any(Char::isDigit) || y.any(Char::isDigit) || PhoneCommandParser.number(x) != null || PhoneCommandParser.number(y) != null) return false
    return distance(x, y) == 1
  }

  /** Optimal string alignment distance, capped: only 0, 1 or "more" matters. */
  internal fun distance(x: String, y: String): Int {
    if (kotlin.math.abs(x.length - y.length) > 1) return 2
    val d = Array(x.length + 1) { IntArray(y.length + 1) }
    for (i in 0..x.length) d[i][0] = i
    for (j in 0..y.length) d[0][j] = j
    for (i in 1..x.length) for (j in 1..y.length) {
      val cost = if (x[i - 1] == y[j - 1]) 0 else 1
      d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
      if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[x.length][y.length]
  }
}
