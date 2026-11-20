package com.example.data

import com.example.phone.PhoneCommandParser
import com.example.phone.PhoneRoute
import java.text.Normalizer
import java.util.Locale

/** Spoken or typed orders that manage learned phrases. They are read before any interpretation. */
sealed interface PhraseVoiceCommand {
  /** «olvidá esta frase» */
  data object ForgetLast : PhraseVoiceCommand
  /** «mostrame las frases aprendidas» */
  data object Show : PhraseVoiceCommand
  /** «cuando diga luz, prendé la linterna» · «aprendé que luz es prendé la linterna» */
  data class Teach(val phrase: String, val target: String) : PhraseVoiceCommand
  /** «corregí esta frase: abrí Spotify» · «esa frase significa abrí Spotify» */
  data class CorrectLast(val target: String) : PhraseVoiceCommand
}

object PhraseVoiceCommands {
  private val forget = Regex("^(?:olvida(?:te)?(?: de)?|borra|elimina) (?:esta|esa|la ultima|la) frase(?: aprendida)?$")
  private val show = Regex(
    "^(?:(?:mostra(?:me)?|ver|abri|abrime|gestiona(?:r)?|administra(?:r)?) (?:las |mis )?)?frases aprendidas$|" +
      "^que frases (?:aprendiste|sabes|conoces)$",
  )
  /** Needs a separator so the phrase and the action do not run together. */
  private val whenISay = Regex(
    "^(?:cuando|si) (?:yo )?(?:te )?(?:diga|digo|escriba|escribo|ponga|pongo) (.+?)\\s*(?:,|:|;| entonces | quiero que | tenes que | tienes que )\\s*(.+)$",
  )
  private val learnThat = Regex("^(?:aprende(?:te)?|acordate|recorda) (?:que |la frase )?(.+?) (?:significa|quiere decir|es) (.+)$")
  /** «Corregí…» is always an order. */
  private val correct = Regex(
    "^(?:corregi|corrige|corregir)(?: (?:esta|esa|la ultima|la))? frase(?: anterior)?\\s*[,:;]?\\s*" +
      "(?:(?:por|a|que sea|para que|significa|quiere decir|es)\\s+)?(.+)$",
  )
  /** «Cambiá la frase…» or «esa frase es…» could be about any text, so the action must be a local request. */
  private val reword = Regex(
    "^(?:cambia|cambiale)(?: (?:esta|esa|la ultima|la))? frase(?: anterior)?\\s*[,:;]?\\s*(?:(?:por|a|que sea)\\s+)?(.+)$|" +
      "^(?:no,? )?(?:esa|esta) frase (?:significa|quiere decir|es|era) (.+)$",
  )

  /**
   * [parser] decides the conditional form: «si digo hola, ¿qué contesto?» is a question for Instinct, so
   * «cuando/si diga…» only counts when the action is a local request. «Aprendé que…» is always an order.
   */
  fun parse(text: String, parser: PhoneCommandParser = PhoneCommandParser()): PhraseVoiceCommand? {
    val folded = fold(text)
    val bare = PhoneCommandParser.normalize(text)
    if (show.matches(bare)) return PhraseVoiceCommand.Show
    if (text.any { it == '?' || it == '¿' }) return null
    if (forget.matches(bare)) return PhraseVoiceCommand.ForgetLast
    whenISay.matchEntire(folded)?.let { match ->
      val phrase = clean(match.groupValues[1])
      val target = action(match.groupValues[2])
      if (phrase.isEmpty() || target.isEmpty() || parser.parse(target) !is PhoneRoute.Local) return null
      return PhraseVoiceCommand.Teach(phrase, target)
    }
    learnThat.matchEntire(folded)?.let { match ->
      val phrase = clean(match.groupValues[1])
      val target = action(match.groupValues[2])
      if (phrase.isNotEmpty() && target.isNotEmpty()) return PhraseVoiceCommand.Teach(phrase, target)
    }
    correct.matchEntire(folded)?.let { match ->
      val target = action(match.groupValues[1])
      if (target.isNotEmpty()) return PhraseVoiceCommand.CorrectLast(target)
    }
    reword.matchEntire(folded)?.let { match ->
      val target = action(match.groupValues[1].ifEmpty { match.groupValues[2] })
      if (target.isNotEmpty() && parser.parse(target) is PhoneRoute.Local) return PhraseVoiceCommand.CorrectLast(target)
    }
    return null
  }

  /** Lowercase without accents or quotes. Commas and colons stay because they separate phrase and action. */
  private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace(Regex("\\p{M}+"), "")
      .replace(Regex("[«»\"“”‘’'¡!]"), " ")
      .replace(Regex("\\.(?!\\d)"), " ")
      .replace(Regex("\\s*([,:;])\\s*"), "$1 ")
      .replace(Regex("\\s+"), " ").trim()

  private fun clean(value: String) = value.trim().trim(',', ':', ';').trim()
  private fun action(value: String) = clean(value).removePrefix("que ").trim()
}
