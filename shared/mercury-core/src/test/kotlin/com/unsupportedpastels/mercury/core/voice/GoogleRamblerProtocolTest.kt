package com.unsupportedpastels.mercury.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleRamblerProtocolTest {
    private val id = "mercury-rambler-012345abcdef"
    private val project = GoogleRamblerProject(id, "123456789012")
    private val account = GoogleRamblerAccount("123456789", "person@example.com")
    private val keyName = "projects/${project.number}/locations/global/keys/mercury-rambler"
    private val accountEmail = "mercury-rambler@$id.iam.gserviceaccount.com"

    @Test
    fun generatedProjectIdsBelongOnlyToThisFeatureAndContainNoAccountIdentity() {
        assertEquals(id, GoogleRamblerProtocol.newProjectId("012345abcdef"))
        assertTrue(GoogleRamblerProtocol.isOwnedProjectId(id))
        for (input in listOf("", "012345abcde", "012345abcdef0", "person@email", "../012345abc")) {
            assertFailsWith<IllegalArgumentException> { GoogleRamblerProtocol.newProjectId(input) }
        }
        for (input in listOf("another-project", "mercury-rambler-012345abcdeg", "$id/keys", id.uppercase())) {
            assertFalse(GoogleRamblerProtocol.isOwnedProjectId(input))
        }
    }

    @Test
    fun projectCreationUsesV3DisplayNameAndAnOwnershipLabelWithoutBillingFields() {
        assertJsonEquals(
            """{"projectId":"$id","displayName":"Mercury Rambler","labels":{"mercury-feature":"rambler"}}""",
            GoogleRamblerProtocol.projectCreateBody(id),
        )
        assertFailsWith<IllegalArgumentException> { GoogleRamblerProtocol.projectCreateBody("unrelated-project") }
        assertJsonEquals(
            """{"serviceIds":["generativelanguage.googleapis.com","apikeys.googleapis.com","iam.googleapis.com"]}""",
            GoogleRamblerProtocol.enableServicesBody(),
        )
        assertTrue(GoogleRamblerProtocol.isValidProject(project))
        assertFalse(GoogleRamblerProtocol.isValidProject(GoogleRamblerProject(id, "0")))
        assertFalse(GoogleRamblerProtocol.isValidProject(GoogleRamblerProject("other-project", "123456789012")))
    }

    @Test
    fun accountComesFromBoundedVerifiedOidcUserInfo() {
        assertEquals(
            account,
            GoogleRamblerProtocol.parseAccount(
                """{"sub":"123456789","email":"person@example.com","email_verified":true,"name":"ignored"}""",
            ),
        )
        for (response in listOf(
            """{"sub":"123456789","email":"person@example.com"}""",
            """{"sub":"123456789","email":"person@example.com","email_verified":false}""",
            """{"sub":"123456789","email":"person@example.com","email_verified":"true"}""",
            """{"sub":123456789,"email":"person@example.com","email_verified":true}""",
            """{"sub":"123456789","email":"person@example.com","verified_email":true}""",
        )) assertNull(GoogleRamblerProtocol.parseAccount(response))
    }

    @Test
    fun malformedIdentityCannotBecomeStoredMetadata() {
        for (subject in listOf("", " ", "a b", "line\nbreak", "a".repeat(256))) {
            assertFalse(GoogleRamblerProtocol.isValidSubject(subject))
            assertNull(GoogleRamblerProtocol.parseAccount(accountJson(subject, "person@example.com")))
        }
        for (email in listOf("", "person", "@example.com", "person@", "a@@example.com", " person@example.com", "a\n@example.com", "a".repeat(255) + "@example.com")) {
            assertNull(GoogleRamblerProtocol.parseAccount(accountJson("123456789", email)))
        }
    }

    @Test
    fun projectParsingRequiresTheExpectedActiveOwnedV3Project() {
        assertEquals(project, GoogleRamblerProtocol.parseProject(projectJson(), id))
        for (response in listOf(
            projectJson(projectId = "mercury-rambler-fedcba543210"),
            projectJson(name = "projects/999999999999/keys"),
            projectJson(name = "projects/0"),
            projectJson(name = "projects/0123456789012"),
            projectJson(name = "projects/9223372036854775808"),
            projectJson(name = "https://attacker.invalid/projects/123456789012"),
            projectJson(feature = "other"),
            projectJson(state = "DELETE_REQUESTED"),
            """{"projectId":"$id","projectNumber":"123456789012","name":"Mercury Rambler","labels":{"mercury-feature":"rambler"}}""",
            """{"projectId":"$id","name":"projects/123456789012","state":"ACTIVE"}""",
        )) assertNull(GoogleRamblerProtocol.parseProject(response, id))
        assertNull(GoogleRamblerProtocol.parseProject(projectJson(), "unrelated-project"))
    }

    @Test
    fun billingMustExplicitlyBeOffAndHaveNoLinkedAccount() {
        assertTrue(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false}"""))
        assertTrue(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false,"billingAccountName":""}"""))
        for (response in listOf(
            "{}", "null", "[]", "{", """{"billingEnabled":"false"}""", """{"billingEnabled":0}""",
            """{"billingEnabled":true}""", """{"billingEnabled":false,"billingAccountName":"billingAccounts/123"}""",
            """{"billingEnabled":false,"billingAccountName":" "}""", """{"billingEnabled":false,"billingAccountName":null}""",
        )) assertFalse(GoogleRamblerProtocol.billingDisabled(response))
    }

    @Test
    fun billingResourceCannotReferToADifferentProject() {
        assertTrue(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false,"projectId":"$id","name":"projects/$id/billingInfo"}""", id))
        assertFalse(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false,"projectId":"another-project"}""", id))
        assertFalse(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false,"name":"projects/another-project/billingInfo"}""", id))
        assertFalse(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false,"projectId":null}""", id))
    }

    @Test
    fun billingFailureReportsLinkageOnlyWhenItIsAffirmativelyPresent() {
        assertNull(GoogleRamblerProtocol.billingFailure("""{"billingEnabled":false}""", id))
        for (response in listOf(
            """{"billingEnabled":true}""",
            """{"billingEnabled":true,"billingAccountName":"billingAccounts/ABC-123"}""",
            """{"billingEnabled":false,"billingAccountName":"billingAccounts/ABC-123"}""",
            """{"billingAccountName":"billingAccounts/ABC-123"}""",
        )) assertEquals(GoogleRamblerFailure.BillingLinked, GoogleRamblerProtocol.billingFailure(response, id))
    }

    @Test
    fun omittedDefaultFalseIsAcceptedOnlyForTheExactReturnedProjectIdentity() {
        val identity = "\"projectId\":\"$id\",\"name\":\"projects/$id/billingInfo\""
        for (response in listOf("{$identity}", "{$identity,\"billingAccountName\":\"\"}")) {
            assertNull(GoogleRamblerProtocol.billingFailure(response, id))
            assertTrue(GoogleRamblerProtocol.billingDisabled(response, id))
            assertFalse(GoogleRamblerProtocol.billingDisabled(response))
        }
    }

    @Test
    fun omittedBillingDefaultDoesNotValidateMissingMismatchedOrMalformedIdentity() {
        for (response in listOf(
            "{}",
            """{"projectId":"$id"}""",
            """{"name":"projects/$id/billingInfo"}""",
            """{"projectId":"another-project","name":"projects/$id/billingInfo"}""",
            """{"projectId":"$id","name":"projects/another-project/billingInfo"}""",
            """{"projectId":"$id","name":"projects/$id/billingInfo","billingEnabled":null}""",
            """{"projectId":"$id","name":"projects/$id/billingInfo","billingEnabled":"false"}""",
        )) assertEquals(GoogleRamblerFailure.InvalidResponse, GoogleRamblerProtocol.billingFailure(response, id))
        assertEquals(
            GoogleRamblerFailure.BillingLinked,
            GoogleRamblerProtocol.billingFailure("""{"projectId":"$id","name":"projects/$id/billingInfo","billingAccountName":"billingAccounts/ABC-123"}""", id),
        )
    }

    @Test
    fun uncertainBillingStateIsInvalidResponseWithoutClaimingAnAccountIsLinked() {
        for (response in listOf(
            "", "{", "[]", "{}", """{"billingEnabled":"false"}""",
            """{"billingEnabled":false,"billingAccountName":null}""",
            """{"billingEnabled":false,"billingAccountName":123}""",
            """{"billingEnabled":false,"billingAccountName":" "}""",
            """{"billingEnabled":false,"billingAccountName":"not-a-billing-resource"}""",
            """{"billingEnabled":false,"billingAccountName":"billingAccounts/line\nbreak"}""",
            """{"billingEnabled":false,"projectId":"another-project"}""",
            """{"billingEnabled":true,"name":"projects/another-project/billingInfo"}""",
        )) assertEquals(GoogleRamblerFailure.InvalidResponse, GoogleRamblerProtocol.billingFailure(response, id))
        assertEquals(GoogleRamblerFailure.InvalidResponse, GoogleRamblerProtocol.billingFailure("""{"billingEnabled":false}""", "unrelated-project"))
        assertEquals(GoogleRamblerFailure.InvalidResponse, GoogleRamblerProtocol.billingFailure("""{"billingEnabled":false}""" + " ".repeat(GoogleRamblerProtocol.MAX_JSON_CHARS), id))
    }

    @Test
    fun serviceAccountCreationDoesNotCreateOrRequestAPrivateKey() {
        assertEquals(accountEmail, GoogleRamblerProtocol.serviceAccountEmail(id))
        assertJsonEquals(
            """{"accountId":"mercury-rambler","serviceAccount":{"displayName":"Mercury Rambler"}}""",
            GoogleRamblerProtocol.serviceAccountCreateBody(),
        )
        assertFailsWith<IllegalArgumentException> { GoogleRamblerProtocol.serviceAccountEmail("../other-project") }
    }

    @Test
    fun serviceAccountResponseMustBelongToTheProjectAndRemainEnabled() {
        val valid = """{"email":"$accountEmail","projectId":"$id"}"""
        assertTrue(GoogleRamblerProtocol.parseServiceAccount(valid, id))
        assertTrue(GoogleRamblerProtocol.parseServiceAccount(valid.dropLast(1) + ",\"disabled\":false}", id))
        for (response in listOf(
            valid.replace(accountEmail, "other@$id.iam.gserviceaccount.com"),
            valid.replace("\"projectId\":\"$id\"", "\"projectId\":\"other-project\""),
            valid.dropLast(1) + ",\"disabled\":true}",
            valid.dropLast(1) + ",\"disabled\":\"false\"}",
            valid.dropLast(1) + ",\"name\":\"projects/other-project/serviceAccounts/$accountEmail\"}",
        )) assertFalse(GoogleRamblerProtocol.parseServiceAccount(response, id))
    }

    @Test
    fun authKeyIsBoundOnlyToItsDedicatedAccountAndGeminiApi() {
        assertJsonEquals(
            """{"displayName":"Mercury Rambler","serviceAccountEmail":"$accountEmail","restrictions":{"apiTargets":[{"service":"generativelanguage.googleapis.com"}]}}""",
            GoogleRamblerProtocol.authKeyCreateBody(id),
        )
        assertEquals(keyName, GoogleRamblerProtocol.parseAuthKeyName(keyJson(), project))
    }

    @Test
    fun existingKeysMustMatchTheFixedResourceAccountAndRestriction() {
        for (response in listOf(
            keyJson(name = "projects/999999999999/locations/global/keys/mercury-rambler"),
            keyJson(name = "$keyName/anything"),
            keyJson(name = keyName.replace("mercury-rambler", "some-other-key")),
            keyJson(email = "different@$id.iam.gserviceaccount.com"),
            keyJson(targets = listOf("generativelanguage.googleapis.com", "aiplatform.googleapis.com")),
            keyJson(targets = emptyList()),
            keyJson(targets = listOf("aiplatform.googleapis.com")),
            """{"name":"$keyName","serviceAccountEmail":"$accountEmail"}""",
            """{"name":"$keyName","restrictions":{"apiTargets":[{"service":"generativelanguage.googleapis.com"}]}}""",
        )) assertNull(GoogleRamblerProtocol.parseAuthKeyName(response, project))
        assertNull(GoogleRamblerProtocol.parseAuthKeyName(keyJson(), GoogleRamblerProject(id, "../123")))
    }

    @Test
    fun keysWithUnexpectedMethodRestrictionsAreRejected() {
        val json = """{"name":"$keyName","serviceAccountEmail":"$accountEmail","restrictions":{"apiTargets":[{"service":"generativelanguage.googleapis.com","methods":["GenerateContent"]}]}}"""
        assertNull(GoogleRamblerProtocol.parseAuthKeyName(json, project))
    }

    @Test
    fun returnedKeyStringsUseTheExistingCredentialPolicy() {
        assertEquals("test-key_123", GoogleRamblerProtocol.parseKeyString("""{"keyString":"test-key_123"}"""))
        for (response in listOf("{}", """{"keyString":123}""", """{"keyString":""}""", """{"keyString":"x\ny"}""", """{"keyString":"x y"}""")) {
            assertNull(GoogleRamblerProtocol.parseKeyString(response))
        }
    }

    @Test
    fun provisionedDottedAuthKeysUseTheSameOpaqueCredentialPolicy() {
        assertEquals(
            "AQ.segment_one.segment-two",
            GoogleRamblerProtocol.parseKeyString("""{"keyString":"AQ.segment_one.segment-two"}"""),
        )
        assertNull(GoogleRamblerProtocol.parseKeyString("""{"keyString":"AQ.segment\n"}"""))
    }

    @Test
    fun operationNamesStayWithinTheChosenGoogleServiceHost() {
        val examples = mapOf(
            GoogleRamblerService.ResourceManager to "operations/pc.123456789",
            GoogleRamblerService.ServiceUsage to "operations/acf.p2-123456789-abc",
            GoogleRamblerService.ApiKeys to "operations/akmf.p7-123456789-abc",
        )
        for ((service, name) in examples) assertTrue(GoogleRamblerProtocol.isAllowedOperationName(service, name))
        for (service in GoogleRamblerService.entries) {
            for (name in listOf("", "operations/", "/operations/abc", "operations/../keys", "operations/..", "operations/a/b", "operations/a?key=secret", "operations/a#fragment", "operations/a%2fb", "https://attacker.invalid/operations/abc", "operations/line\nbreak", "operations/" + "a".repeat(256))) {
                assertFalse(GoogleRamblerProtocol.isAllowedOperationName(service, name))
            }
        }
    }

    @Test
    fun pendingOperationMayOmitItsDefaultDoneFlagAndCompletionStaysScoped() {
        val name = "operations/pc.123456789"
        val service = GoogleRamblerService.ResourceManager
        assertEquals(GoogleRamblerOperation(name, false, null), GoogleRamblerProtocol.parseOperation("""{"name":"$name"}""", service))
        assertEquals(GoogleRamblerOperation(name, false, null), GoogleRamblerProtocol.parseOperation("""{"name":"$name","done":false}""", service))
        assertEquals(GoogleRamblerOperation(name, true, null), GoogleRamblerProtocol.parseOperation("""{"name":"$name","done":true,"response":{"future":"ignored"}}""", service))
        for (response in listOf(
            """{"name":"$name","done":"true"}""",
            """{"name":"$name","done":null}""",
            """{"name":"https://attacker.invalid/operation","done":true}""",
            """{"name":"$name","done":false,"error":{"code":7}}""",
        )) assertNull(GoogleRamblerProtocol.parseOperation(response, service))
    }

    @Test
    fun completedOperationErrorsMapRpcCodesWithoutKeepingMessages() {
        for ((code, expected) in mapOf(
            16 to GoogleRamblerFailure.Authorization,
            7 to GoogleRamblerFailure.Permission,
            8 to GoogleRamblerFailure.Quota,
            3 to GoogleRamblerFailure.DeveloperConfiguration,
            14 to GoogleRamblerFailure.Unavailable,
        )) {
            assertEquals(
                GoogleRamblerOperation("operations/pc.123", true, expected),
                GoogleRamblerProtocol.parseOperation("""{"name":"operations/pc.123","done":true,"error":{"code":$code,"message":"secret"}}""", GoogleRamblerService.ResourceManager),
            )
        }
        assertEquals(
            GoogleRamblerFailure.BillingRequired,
            GoogleRamblerProtocol.parseOperation(
                """{"name":"operations/pc.123","done":true,"error":{"code":9,"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"BILLING_DISABLED"}]}}""",
                GoogleRamblerService.ResourceManager,
            )?.failure,
        )
        assertNull(GoogleRamblerProtocol.parseOperation("""{"name":"operations/pc.123","done":true,"error":{"code":"7"}}""", GoogleRamblerService.ResourceManager))
    }

    @Test
    fun httpFailuresHaveClearSafeCategories() {
        for ((code, expected) in mapOf(
            401 to GoogleRamblerFailure.Authorization,
            403 to GoogleRamblerFailure.Permission,
            429 to GoogleRamblerFailure.Quota,
            400 to GoogleRamblerFailure.DeveloperConfiguration,
            500 to GoogleRamblerFailure.Unavailable,
            503 to GoogleRamblerFailure.Unavailable,
            0 to GoogleRamblerFailure.Unavailable,
            200 to GoogleRamblerFailure.InvalidResponse,
        )) assertEquals(expected, GoogleRamblerProtocol.failure(code, null))
    }

    @Test
    fun billingAndTermsErrorInfoOverrideGenericPermissionFailures() {
        for (reason in listOf("BILLING_DISABLED", "BILLING_REQUIRED", "PROJECT_BILLING_DISABLED", "BILLING_NOT_ACTIVE")) {
            assertEquals(GoogleRamblerFailure.BillingRequired, GoogleRamblerProtocol.failure(403, errorJson(reason)))
        }
        for (reason in listOf("TOS_NOT_ACCEPTED", "TERMS_OF_SERVICE_NOT_ACCEPTED", "TERMS_NOT_ACCEPTED")) {
            assertEquals(GoogleRamblerFailure.Terms, GoogleRamblerProtocol.failure(403, errorJson(reason)))
        }
    }

    @Test
    fun onlyStructuredErrorStatusAndReasonAreClassified() {
        assertEquals(GoogleRamblerFailure.Authorization, GoogleRamblerProtocol.failure(403, errorJson("ACCESS_TOKEN_SCOPE_INSUFFICIENT")))
        assertEquals(GoogleRamblerFailure.Quota, GoogleRamblerProtocol.failure(403, errorJson("RATE_LIMIT_EXCEEDED")))
        assertEquals(GoogleRamblerFailure.Permission, GoogleRamblerProtocol.failure(403, """{"error":{"message":"BILLING_DISABLED TOS_NOT_ACCEPTED secret"}}"""))
        assertEquals(GoogleRamblerFailure.Permission, GoogleRamblerProtocol.failure(403, errorJson("UNKNOWN_BILLING_DISABLED_FUTURE")))
        assertEquals(GoogleRamblerFailure.Permission, GoogleRamblerProtocol.failure(403, errorJson("BILLING_DISABLED", "untrusted.Detail")))
        assertEquals(GoogleRamblerFailure.Permission, GoogleRamblerProtocol.failure(403, " ".repeat(65_537) + errorJson("BILLING_DISABLED")))
        assertEquals(GoogleRamblerFailure.Quota, GoogleRamblerProtocol.failure(403, """{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
    }

    @Test
    fun connectionMetadataRoundTripsWithAnExplicitVersionAndNoCredential() {
        val connection = GoogleRamblerConnection(account, project)
        val encoded = GoogleRamblerProtocol.encodeConnection(connection)
        assertJsonEquals(
            """{"version":1,"account":{"subject":"123456789","email":"person@example.com"},"project":{"projectId":"$id","number":"123456789012"}}""",
            encoded,
        )
        assertEquals(connection, GoogleRamblerProtocol.parseConnection(encoded))
        assertFailsWith<IllegalArgumentException> {
            GoogleRamblerProtocol.encodeConnection(connection.copy(project = GoogleRamblerProject("unrelated", "123")))
        }
    }

    @Test
    fun connectionMetadataRejectsInvalidVersionAndScope() {
        val valid = """{"version":1,"account":{"subject":"123456789","email":"person@example.com"},"project":{"projectId":"$id","number":"123456789012"}}"""
        for (response in listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace(id, "unrelated-project"),
            valid.replace("123456789012", "../number"),
            valid.replace("person@example.com", "not-an-email"),
            valid.replace("123456789\"", "\""),
        )) assertNull(GoogleRamblerProtocol.parseConnection(response))
    }

    @Test
    fun allParsersRejectMalformedOrOversizedJson() {
        for (response in listOf("", "{", "[]", "null", "23", "\"secret\"", " ".repeat(65_537))) {
            assertNull(GoogleRamblerProtocol.parseAccount(response))
            assertNull(GoogleRamblerProtocol.parseProject(response, id))
            assertNull(GoogleRamblerProtocol.parseAuthKeyName(response, project))
            assertNull(GoogleRamblerProtocol.parseKeyString(response))
            assertNull(GoogleRamblerProtocol.parseConnection(response))
            assertNull(GoogleRamblerProtocol.parseOperation(response, GoogleRamblerService.ApiKeys))
            assertFalse(GoogleRamblerProtocol.parseServiceAccount(response, id))
            assertFalse(GoogleRamblerProtocol.billingDisabled(response))
        }
    }

    @Test
    fun otherwiseValidResponsesStillFailWhenTheirFrameBoundsAreExceeded() {
        val padding = " ".repeat(GoogleRamblerProtocol.MAX_JSON_CHARS)
        assertNull(GoogleRamblerProtocol.parseAccount(accountJson(account.subject, account.email) + padding))
        assertNull(GoogleRamblerProtocol.parseProject(projectJson() + padding, id))
        assertNull(GoogleRamblerProtocol.parseAuthKeyName(keyJson() + padding, project))
        assertNull(GoogleRamblerProtocol.parseKeyString("""{"keyString":"test-key_123"}""" + padding))
        assertNull(GoogleRamblerProtocol.parseOperation("""{"name":"operations/akmf.123"}""" + padding, GoogleRamblerService.ApiKeys))
        assertFalse(GoogleRamblerProtocol.parseServiceAccount("""{"email":"$accountEmail","projectId":"$id"}""" + padding, id))
        assertFalse(GoogleRamblerProtocol.billingDisabled("""{"billingEnabled":false}""" + padding))
        val connection = GoogleRamblerProtocol.encodeConnection(GoogleRamblerConnection(account, project))
        assertNull(GoogleRamblerProtocol.parseConnection(connection + " ".repeat(GoogleRamblerProtocol.MAX_CONNECTION_CHARS)))
    }

    private fun accountJson(subject: String, email: String): String = buildJsonObject {
        put("sub", subject)
        put("email", email)
        put("email_verified", true)
    }.toString()

    private fun projectJson(
        name: String = "projects/${project.number}",
        projectId: String = id,
        feature: String = "rambler",
        state: String = "ACTIVE",
    ): String = buildJsonObject {
        put("name", name)
        put("projectId", projectId)
        put("state", state)
        putJsonObject("labels") { put("mercury-feature", feature) }
    }.toString()

    private fun keyJson(
        name: String = keyName,
        email: String = accountEmail,
        targets: List<String> = listOf("generativelanguage.googleapis.com"),
    ): String = buildJsonObject {
        put("name", name)
        put("serviceAccountEmail", email)
        putJsonObject("restrictions") {
            putJsonArray("apiTargets") {
                targets.forEach { service -> add(buildJsonObject { put("service", service) }) }
            }
        }
    }.toString()

    private fun errorJson(reason: String, type: String = "type.googleapis.com/google.rpc.ErrorInfo"): String = buildJsonObject {
        putJsonObject("error") {
            put("code", 403)
            put("status", "PERMISSION_DENIED")
            put("message", "sensitive details are discarded")
            putJsonArray("details") {
                add(buildJsonObject {
                    put("@type", type)
                    put("reason", reason)
                    put("domain", "googleapis.com")
                })
            }
        }
    }.toString()

    private fun assertJsonEquals(expected: String, actual: String) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
}
