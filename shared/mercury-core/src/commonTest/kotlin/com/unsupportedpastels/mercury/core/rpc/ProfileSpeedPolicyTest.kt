package com.unsupportedpastels.mercury.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSpeedPolicyTest {
    @Test fun normalAlwaysRemainsSelectableAndFastRequiresAdvertisedSupport() {
        assertTrue(ProfileSpeedPolicy.isSelectable("normal", fastSupported = false))
        assertTrue(ProfileSpeedPolicy.isSelectable("normal", fastSupported = true))
        assertFalse(ProfileSpeedPolicy.isSelectable("fast", fastSupported = false))
        assertTrue(ProfileSpeedPolicy.isSelectable("fast", fastSupported = true))
        for (speed in listOf("", "priority", "auto", "cold", "ultrafast", "normal ", "FAST")) {
            assertFalse(ProfileSpeedPolicy.isSelectable(speed, fastSupported = true), speed)
            assertFalse(ProfileSpeedPolicy.isSelectable(speed, fastSupported = false), speed)
        }
    }

    @Test fun missingAndExplicitNormalDefaultsSelectNormal() {
        for (config in listOf("{}", "{\"agent\":{}}", "{\"agent\":{\"service_tier\":null}}")) {
            assertEquals("normal", ProfileSpeedPolicy.selectedSpeed(config), config)
        }
        for (tier in listOf("", "normal", "default", "standard", "off", "none", " NORMAL ")) {
            assertEquals("normal", ProfileSpeedPolicy.selectedSpeed(config(tier)), tier)
        }
    }

    @Test fun fastAndOfficialPriorityAliasesSelectFast() {
        for (tier in listOf("fast", "priority", "on", " PRIORITY ")) {
            assertEquals("fast", ProfileSpeedPolicy.selectedSpeed(config(tier)), tier)
        }
    }

    @Test fun otherModesStayUnselectedInsteadOfBeingOverwrittenOnLoad() {
        for (tier in listOf("auto", "cold", "ultrafast", "ultra", "future-tier")) {
            assertNull(ProfileSpeedPolicy.selectedSpeed(config(tier)), tier)
        }
    }

    @Test fun malformedConfigAndWrongTypesStayUnselected() {
        for (config in listOf(
            "", "{", "[]", "null", "true", "\"normal\"",
            "{\"agent\":null}", "{\"agent\":[]}", "{\"agent\":true}",
            "{\"agent\":{\"service_tier\":false}}", "{\"agent\":{\"service_tier\":0}}",
            "{\"agent\":{\"service_tier\":[]}}", "{\"agent\":{\"service_tier\":{}}}",
        )) {
            assertNull(ProfileSpeedPolicy.selectedSpeed(config), config)
        }
    }

    @Test fun supportedChoicesProduceOnlyTheMinimalProfileConfigUpdate() {
        assertEquals(listOf("normal", "fast"), ProfileSpeedPolicy.speeds)
        for (speed in ProfileSpeedPolicy.speeds) {
            assertEquals(
                "{\"config\":{\"agent\":{\"service_tier\":\"$speed\"}}}",
                ProfileSpeedPolicy.configUpdate(speed),
            )
        }
    }

    @Test fun updatesRejectAliasesUnknownModesAndUncanonicalInput() {
        for (speed in listOf("", "priority", "on", "auto", "cold", "ultrafast", "normal ", "FAST", "unknown")) {
            assertNull(ProfileSpeedPolicy.configUpdate(speed), speed)
        }
    }

    private fun config(tier: String): String = "{\"agent\":{\"service_tier\":\"$tier\"}}"
}
