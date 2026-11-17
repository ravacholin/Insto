package com.example.context

import android.content.Intent

/** Read text extras only. Never dereference a URI, fetch a URL or read another app. */
object SharedTextIntents {
  fun supports(intent: Intent): Boolean = intent.action in setOf(Intent.ACTION_PROCESS_TEXT, Intent.ACTION_SEND) &&
    intent.type?.startsWith("text/") == true

  fun read(intent: Intent): SharedTextContext? {
    if (!supports(intent)) return null
    val selected = intent.action == Intent.ACTION_PROCESS_TEXT
    val text = intent.getCharSequenceExtra(if (selected) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT)?.toString().orEmpty()
    return SharedTextContext(text, if (selected) SharedTextContext.Source.SELECTED else SharedTextContext.Source.SHARED)
  }
}
