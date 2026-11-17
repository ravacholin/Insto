package com.example.phone
import org.junit.Assert.*
import org.junit.Test
class DocumentNamePolicyTest {
 @Test fun punctuationAndCaseCannotSelectAnotherDocument() {
  assertFalse(DocumentNamePolicy.matches("Informe.pdf","Informe pdf"))
  assertFalse(DocumentNamePolicy.matches("Informe.pdf","informe.pdf"))
  assertFalse(DocumentNamePolicy.matches("Informe.pdf","Informe-pdf"))
  assertTrue(DocumentNamePolicy.matches("José.pdf","Jose\u0301.pdf"))
 }
}
