package com.example.phone
import org.junit.Assert.*
import org.junit.Test
class StructuredCommandClientTest {
 private fun response(json: String) = org.json.JSONObject().put("candidates", org.json.JSONArray().put(org.json.JSONObject().put("finishReason","STOP").put("content",org.json.JSONObject().put("parts",org.json.JSONArray().put(org.json.JSONObject().put("text",json)))))).toString()
 @Test fun providerCannotReturnArbitraryFieldsOrPartialUnsupportedPlan() {
  assertThrows(Exception::class.java) { StructuredCommandClient.parseResponse(response("""{"supported":true,"commands":[{"type":"torch","enabled":true,"intent":"bad"}]}""")) }
  assertThrows(Exception::class.java) { StructuredCommandClient.parseResponse(response("""{"supported":false,"commands":[{"type":"torch","enabled":true}]}""")) }
 }
 @Test fun validTypedCandidateIsValidatedLocally() {
  val commands=StructuredCommandClient.parseResponse(response("""{"supported":true,"commands":[{"type":"timer","seconds":600,"label":"Fideos"}]}"""))
  assertEquals(listOf(PhoneCommand.Timer(600,"Fideos")),commands)
 }
}
