package com.hfad.starlit.webapp.project

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal object WebAppAcceptanceValidator {
    private val criterionIdRegex = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")

    fun validate(
        criteria: List<WebAppAcceptanceCriterion>,
        selectors: Set<String>,
        persistence: WebAppStatePersistence,
        storageKey: String?,
        hasErrorStates: Boolean,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        if (criteria.isEmpty()) {
            diagnostics += error("APP_SPEC_ACCEPTANCE_MISSING", "appSpec must declare acceptance criteria")
            return
        }
        val duplicateIds = criteria.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        criteria.forEach { criterion ->
            if (!criterionIdRegex.matches(criterion.id)) {
                diagnostics += error(
                    "APP_SPEC_CRITERION_ID_INVALID",
                    "Acceptance criterion id is invalid: ${criterion.id}"
                )
            }
            validateText(
                criterion.title,
                "APP_SPEC_CRITERION_TITLE_INVALID",
                "criterion ${criterion.id} title",
                diagnostics,
                300
            )
            if (criterion.covers.orEmpty().isEmpty()) {
                diagnostics += error(
                    "APP_SPEC_CRITERION_COVERAGE_MISSING",
                    "Acceptance criterion ${criterion.id} must declare covered scenarios"
                )
            }
            criterion.covers.orEmpty()
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .forEach { coverage ->
                    diagnostics += error(
                        "APP_SPEC_CRITERION_COVERAGE_DUPLICATE",
                        "Acceptance criterion ${criterion.id} duplicates coverage $coverage"
                    )
                }
            if (criterion.expected.orEmpty().isEmpty()) {
                diagnostics += error(
                    "APP_SPEC_CRITERION_EXPECTED_MISSING",
                    "Acceptance criterion ${criterion.id} must declare expected assertions"
                )
            }
            if ((criterion.setup.orEmpty() + criterion.actions.orEmpty()).none {
                    it.type != WebAppAcceptanceActionType.WAIT
                }
            ) {
                diagnostics += error(
                    "APP_SPEC_CRITERION_ACTION_MISSING",
                    "Acceptance criterion ${criterion.id} must exercise at least one non-WAIT action"
                )
            }
            criterion.setup.orEmpty().forEach { validateAction(criterion.id, it, selectors, diagnostics) }
            criterion.actions.orEmpty().forEach { validateAction(criterion.id, it, selectors, diagnostics) }
            criterion.expected.orEmpty().forEach {
                validateAssertion(
                    criterionId = criterion.id,
                    assertion = it,
                    selectors = selectors,
                    persistence = persistence,
                    storageKey = storageKey,
                    diagnostics = diagnostics
                )
            }
        }
        duplicateIds.forEach { id ->
            diagnostics += error("APP_SPEC_CRITERION_ID_DUPLICATE", "Acceptance criterion id is duplicated: $id")
        }
        if (criteria.none { it.priority == WebAppAcceptancePriority.P0 }) {
            diagnostics += error("APP_SPEC_P0_CRITERION_MISSING", "Acceptance contract must contain a P0 criterion")
        }
        val coreSuccessCriteria = criteria.filter {
            WebAppAcceptanceCoverage.CORE_SUCCESS in it.covers.orEmpty()
        }
        if (coreSuccessCriteria.isEmpty()) {
            diagnostics += error(
                "APP_SPEC_CORE_SUCCESS_COVERAGE_MISSING",
                "Acceptance contract must cover CORE_SUCCESS"
            )
        }
        if (criteria.none {
                WebAppAcceptanceCoverage.EMPTY_STATE in it.covers.orEmpty()
            }
        ) {
            diagnostics += error(
                "APP_SPEC_EMPTY_STATE_COVERAGE_MISSING",
                "Acceptance contract must cover EMPTY_STATE"
            )
        }
        coreSuccessCriteria.filter { it.priority != WebAppAcceptancePriority.P0 }.forEach { criterion ->
            diagnostics += error(
                "APP_SPEC_CORE_SUCCESS_PRIORITY_INVALID",
                "CORE_SUCCESS criterion ${criterion.id} must have P0 priority"
            )
        }
        if (hasErrorStates && criteria.none {
                WebAppAcceptanceCoverage.ERROR_STATE in it.covers.orEmpty()
            }
        ) {
            diagnostics += error(
                "APP_SPEC_ERROR_STATE_COVERAGE_MISSING",
                "Acceptance contract must cover ERROR_STATE when state.errorStates is not empty"
            )
        }
        val persistenceCriteria = criteria.filter {
            WebAppAcceptanceCoverage.PERSISTENCE in it.covers.orEmpty()
        }
        if (persistence == WebAppStatePersistence.STARLIT_STORAGE && persistenceCriteria.isEmpty()) {
            diagnostics += error(
                "APP_SPEC_PERSISTENCE_COVERAGE_MISSING",
                "STARLIT_STORAGE acceptance contract must cover PERSISTENCE"
            )
        }
        persistenceCriteria.filter { criterion ->
            criterion.expected.orEmpty().none {
                it.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS
            }
        }.forEach { criterion ->
            diagnostics += error(
                "APP_SPEC_PERSISTENCE_ASSERTION_MISSING",
                "PERSISTENCE criterion ${criterion.id} must contain a STORAGE_EQUALS assertion"
            )
        }
        val storageAssertions = criteria
            .flatMap { it.expected.orEmpty() }
            .filter { it.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS }
        if (persistence == WebAppStatePersistence.STARLIT_STORAGE && storageAssertions.isEmpty()) {
            diagnostics += error(
                "APP_SPEC_STORAGE_ASSERTION_MISSING",
                "STARLIT_STORAGE persistence requires a STORAGE_EQUALS acceptance assertion"
            )
        } else if (persistence != WebAppStatePersistence.STARLIT_STORAGE && storageAssertions.isNotEmpty()) {
            diagnostics += error(
                "APP_SPEC_STORAGE_ASSERTION_UNEXPECTED",
                "STORAGE_EQUALS requires STARLIT_STORAGE persistence"
            )
        }
    }

    private fun validateAction(
        criterionId: String,
        action: WebAppAcceptanceAction,
        selectors: Set<String>,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val targetRequired = action.type != WebAppAcceptanceActionType.WAIT
        if (targetRequired && action.target.isNullOrBlank()) {
            diagnostics += error(
                "APP_SPEC_ACTION_TARGET_MISSING",
                "${action.type} action in $criterionId requires a target"
            )
        }
        action.target?.takeIf(String::isNotBlank)?.let { target ->
            if (target !in selectors) {
                diagnostics += error(
                    "APP_SPEC_ACTION_TARGET_UNKNOWN",
                    "Action target in $criterionId is not a declared selector: $target"
                )
            }
        }
        if ((action.type == WebAppAcceptanceActionType.INPUT ||
                action.type == WebAppAcceptanceActionType.SELECT ||
                action.type == WebAppAcceptanceActionType.KEY_PRESS) && action.value == null
        ) {
            diagnostics += error(
                "APP_SPEC_ACTION_VALUE_MISSING",
                "${action.type} action in $criterionId requires a value"
            )
        }
        if (action.timeoutMs != null && action.timeoutMs !in 0..10_000) {
            diagnostics += error(
                "APP_SPEC_ACTION_TIMEOUT_INVALID",
                "${action.type} action in $criterionId requires timeoutMs between 0 and 10000"
            )
        }
        if (action.type == WebAppAcceptanceActionType.WAIT &&
            action.timeoutMs == null && action.value != null
        ) {
            val waitMillis = action.value.toIntOrNull()
            if (waitMillis == null || waitMillis !in 0..10_000) {
                diagnostics += error(
                    "APP_SPEC_ACTION_TIMEOUT_INVALID",
                    "WAIT action value in $criterionId must be an integer between 0 and 10000"
                )
            }
        }
    }

    private fun validateAssertion(
        criterionId: String,
        assertion: WebAppAcceptanceAssertion,
        selectors: Set<String>,
        persistence: WebAppStatePersistence,
        storageKey: String?,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val targetRequired = assertion.type != WebAppAcceptanceAssertionType.URL_CONTAINS &&
            assertion.type != WebAppAcceptanceAssertionType.STORAGE_EQUALS
        if (targetRequired && assertion.target.isNullOrBlank()) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_TARGET_MISSING",
                "${assertion.type} assertion in $criterionId requires a target"
            )
        }
        if (targetRequired) {
            assertion.target?.takeIf(String::isNotBlank)?.let { target ->
                if (target !in selectors) {
                    diagnostics += error(
                        "APP_SPEC_ASSERTION_TARGET_UNKNOWN",
                        "Assertion target in $criterionId is not a declared selector: $target"
                    )
                }
            }
        }
        if (assertion.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS &&
            assertion.target.isNullOrBlank()
        ) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_TARGET_MISSING",
                "STORAGE_EQUALS assertion in $criterionId requires a storage key"
            )
        } else if (assertion.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS &&
            assertion.target != storageKey
        ) {
            diagnostics += error(
                "APP_SPEC_STORAGE_ASSERTION_KEY_MISMATCH",
                "STORAGE_EQUALS target in $criterionId must equal state.storageKey"
            )
        }
        if (assertion.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS &&
            persistence != WebAppStatePersistence.STARLIT_STORAGE
        ) {
            diagnostics += error(
                "APP_SPEC_STORAGE_ASSERTION_UNEXPECTED",
                "STORAGE_EQUALS assertion in $criterionId requires STARLIT_STORAGE persistence"
            )
        }
        val valueRequired = assertion.type == WebAppAcceptanceAssertionType.TEXT_EQUALS ||
            assertion.type == WebAppAcceptanceAssertionType.TEXT_CONTAINS ||
            assertion.type == WebAppAcceptanceAssertionType.VALUE_EQUALS ||
            assertion.type == WebAppAcceptanceAssertionType.ATTRIBUTE_EQUALS ||
            assertion.type == WebAppAcceptanceAssertionType.URL_CONTAINS ||
            assertion.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS
        if (valueRequired && assertion.value == null) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_VALUE_MISSING",
                "${assertion.type} assertion in $criterionId requires a value"
            )
        }
        if ((assertion.type == WebAppAcceptanceAssertionType.TEXT_CONTAINS ||
                assertion.type == WebAppAcceptanceAssertionType.URL_CONTAINS) &&
            assertion.value.isNullOrBlank()
        ) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_VALUE_EMPTY",
                "${assertion.type} assertion in $criterionId requires a non-blank value"
            )
        }
        if (assertion.type == WebAppAcceptanceAssertionType.STORAGE_EQUALS &&
            assertion.value != null &&
            !isValidJsonText(assertion.value)
        ) {
            diagnostics += error(
                "APP_SPEC_STORAGE_ASSERTION_VALUE_INVALID",
                "STORAGE_EQUALS value in $criterionId must be valid JSON text"
            )
        }
        if (assertion.type == WebAppAcceptanceAssertionType.ATTRIBUTE_EQUALS &&
            assertion.attribute.isNullOrBlank()
        ) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_ATTRIBUTE_MISSING",
                "ATTRIBUTE_EQUALS assertion in $criterionId requires an attribute"
            )
        }
        if (assertion.type == WebAppAcceptanceAssertionType.COUNT_EQUALS &&
            (assertion.count == null || assertion.count < 0)
        ) {
            diagnostics += error(
                "APP_SPEC_ASSERTION_COUNT_INVALID",
                "COUNT_EQUALS assertion in $criterionId requires a non-negative count"
            )
        }
    }

    private fun isValidJsonText(value: String): Boolean {
        return runCatching {
            JsonReader(StringReader(value)).use { reader ->
                reader.isLenient = false
                if (reader.peek() == JsonToken.END_DOCUMENT) {
                    return@use false
                }
                reader.skipValue()
                reader.peek() == JsonToken.END_DOCUMENT
            }
        }.getOrDefault(false)
    }


    private fun validateText(
        value: String,
        code: String,
        label: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>,
        maxLength: Int = 160
    ) {
        if (value.isBlank() || value.length > maxLength) {
            diagnostics += error(code, "$label must contain 1-$maxLength characters")
        }
    }

    private fun error(
        code: String,
        message: String,
        path: String? = null
    ) = WebProjectValidationDiagnostic(
        severity = WebProjectDiagnosticSeverity.ERROR,
        code = code,
        message = message,
        path = path
    )
}
