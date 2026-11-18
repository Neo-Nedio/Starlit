package com.hfad.starlit.webapp.project

enum class WebAppStatePersistence {
    NONE,
    MEMORY,
    STARLIT_STORAGE
}

enum class WebAppAcceptancePriority {
    P0,
    P1,
    P2
}

enum class WebAppAcceptanceCoverage {
    CORE_SUCCESS,
    EMPTY_STATE,
    ERROR_STATE,
    PERSISTENCE
}

enum class WebAppAcceptanceActionType {
    CLICK,
    INPUT,
    SELECT,
    SUBMIT,
    FOCUS,
    BLUR,
    KEY_PRESS,
    WAIT
}

enum class WebAppAcceptanceAssertionType {
    EXISTS,
    NOT_EXISTS,
    VISIBLE,
    HIDDEN,
    TEXT_EQUALS,
    TEXT_CONTAINS,
    VALUE_EQUALS,
    ATTRIBUTE_EQUALS,
    COUNT_EQUALS,
    URL_CONTAINS,
    STORAGE_EQUALS
}

data class WebAppSelectorSpec(
    val id: String,
    val selector: String,
    val purpose: String
)

data class WebAppScreenSpec(
    val id: String,
    val title: String,
    val purpose: String,
    val selectorIds: List<String>
)

data class WebAppComponentSpec(
    val id: String,
    val name: String,
    val purpose: String,
    val selectorIds: List<String>,
    val states: List<String> = emptyList()
)

data class WebAppStateFieldSpec(
    val name: String,
    val type: String,
    val description: String,
    val initialValue: String? = null
)

data class WebAppStateSpec(
    val persistence: WebAppStatePersistence,
    val storageKey: String? = null,
    val fields: List<WebAppStateFieldSpec> = emptyList(),
    val emptyState: String,
    val errorStates: List<String> = emptyList()
)

data class WebAppInteractionSpec(
    val id: String,
    val title: String,
    val triggerSelectorId: String,
    val outcome: String,
    val stateChanges: List<String> = emptyList()
)

data class WebAppUserFlowSpec(
    val id: String,
    val title: String,
    val interactionIds: List<String>,
    val criterionIds: List<String>
)

data class WebAppDesignToken(
    val name: String,
    val value: String,
    val purpose: String
)

data class WebAppDesignSpec(
    val theme: String,
    val tokens: List<WebAppDesignToken>,
    val constraints: List<String>,
    val viewportWidths: List<Int> = listOf(360, 393),
    val minTouchTargetPx: Int = 44
)

data class WebAppAcceptanceAction(
    val type: WebAppAcceptanceActionType,
    val target: String? = null,
    val value: String? = null,
    val timeoutMs: Int? = null
)

data class WebAppAcceptanceAssertion(
    val type: WebAppAcceptanceAssertionType,
    val target: String? = null,
    val value: String? = null,
    val attribute: String? = null,
    val count: Int? = null
)

data class WebAppAcceptanceCriterion(
    val id: String,
    val title: String,
    val priority: WebAppAcceptancePriority,
    val covers: List<WebAppAcceptanceCoverage> = emptyList(),
    val setup: List<WebAppAcceptanceAction> = emptyList(),
    val actions: List<WebAppAcceptanceAction> = emptyList(),
    val expected: List<WebAppAcceptanceAssertion>
)

data class WebAppAcceptanceContract(
    val criteria: List<WebAppAcceptanceCriterion>
)

internal object WebAppSpecPolicy {
    fun preservesBaseline(
        baseline: WebAppSpec?,
        candidate: WebAppSpec?
    ): Boolean {
        return baseline == null || candidate == baseline
    }
}

data class WebAppSpec(
    val specVersion: Int = CURRENT_SPEC_VERSION,
    val summary: String,
    val primaryGoal: String,
    val selectors: List<WebAppSelectorSpec>,
    val screens: List<WebAppScreenSpec>,
    val components: List<WebAppComponentSpec> = emptyList(),
    val state: WebAppStateSpec,
    val interactions: List<WebAppInteractionSpec>,
    val userFlows: List<WebAppUserFlowSpec>,
    val design: WebAppDesignSpec,
    val acceptanceContract: WebAppAcceptanceContract
) {
    companion object {
        const val CURRENT_SPEC_VERSION = 1
    }
}

