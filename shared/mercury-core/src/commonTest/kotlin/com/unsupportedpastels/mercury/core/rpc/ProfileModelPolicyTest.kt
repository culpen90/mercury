package com.unsupportedpastels.mercury.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileModelPolicyTest {
    @Test fun acceptedModelResponseUsesOfficialFieldsAndIgnoresAdditions() {
        assertEquals(
            ProfileModelSetResult(ok = true, confirmationRequired = false, confirmationMessage = null),
            ProfileModelPolicy.setResult("""{"ok":true,"confirm_required":false,"future":{"value":1}}"""),
        )
    }

    @Test fun confirmationRequiredPreservesTheServersMessage() {
        assertEquals(
            ProfileModelSetResult(ok = false, confirmationRequired = true, confirmationMessage = "Higher cost model"),
            ProfileModelPolicy.setResult("""{"ok":false,"confirm_required":true,"confirm_message":"Higher cost model"}"""),
        )
    }

    @Test fun omittedFlagsDefaultToFalseAndMissingMessagesStayAbsent() {
        val empty = ProfileModelSetResult(ok = false, confirmationRequired = false, confirmationMessage = null)
        assertEquals(empty, ProfileModelPolicy.setResult("{}"))
        assertEquals(empty, ProfileModelPolicy.setResult("""{"confirm_message":null}"""))
    }

    @Test fun oversizedConfirmationTextIsBounded() {
        val message = "m".repeat(1_200)
        assertEquals(
            message.take(1_000),
            ProfileModelPolicy.setResult("""{"confirm_required":true,"confirm_message":"$message"}""")?.confirmationMessage,
        )
    }

    @Test fun malformedRootsAndMistypedFieldsAreRejected() {
        for (response in listOf(
            "", "{", "[]", "null", "true", "\"ok\"",
            """{"ok":"true"}""", """{"ok":1}""", """{"ok":null}""", """{"ok":[]}""",
            """{"confirm_required":"false"}""", """{"confirm_required":null}""",
            """{"confirm_message":false}""", """{"confirm_message":12}""", """{"confirm_message":{}}""",
        )) {
            assertNull(ProfileModelPolicy.setResult(response), response)
        }
    }
}
