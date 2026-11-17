package com.example.phone

import java.text.Normalizer

/** Filename punctuation and case are significant; voice-command normalization must not choose a file. */
object DocumentNamePolicy {
  fun matches(requested:String,actual:String):Boolean =
    Normalizer.normalize(requested,Normalizer.Form.NFC)==Normalizer.normalize(actual,Normalizer.Form.NFC)
}
