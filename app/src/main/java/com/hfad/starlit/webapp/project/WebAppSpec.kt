package com.hfad.starlit.webapp.project

object WebAppSpecValidator {
    private const val PROJECT_PLAN_FILE_NAME = "project.json"
    private val idRegex = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val criterionIdRegex = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    private val dataTestIdSelectorRegex =
        Regex("""^\[data-testid=(['"])[A-Za-z][A-Za-z0-9_-]{0,63}\1\]$""")

    fun validate(manifest: WebAppProjectManifest): List<WebProjectValidationDiagnostic> {
        val diagnostics = mutableListOf<WebProjectValidationDiagnostic>()
        validateFileGraph(manifest.files, diagnostics)
        val spec = manifest.appSpec
        if (spec == null) {
            manifest.files.filter { it.ownsCriteria.orEmpty().isNotEmpty() }.forEach { file ->
                diagnostics += error(
                    code = "APP_SPEC_REQUIRED_FOR_CRITERIA",
                    message = "File criterion ownership requires an appSpec",
                    path = file.path
                )
            }
            return diagnostics
        }

        if (spec.specVersion != WebAppSpec.CURRENT_SPEC_VERSION) {
            diagnostics += error(
                code = "APP_SPEC_SCHEMA_UNSUPPORTED",
                message = "Unsupported appSpec version ${spec.specVersion}"
            )
        }
        validateText(spec.summary, "APP_SPEC_SUMMARY_INVALID", "summary", diagnostics, 500)
        validateText(spec.primaryGoal, "APP_SPEC_PRIMARY_GOAL_INVALID", "primaryGoal", diagnostics, 500)

        val selectors = spec.selectors.orEmpty()
        if (selectors.isEmpty()) {
            diagnostics += error("APP_SPEC_SELECTORS_MISSING", "appSpec must declare stable selectors")
        }
        validateUniqueIds(
            values = selectors.map { it.id },
            code = "APP_SPEC_SELECTOR_ID_INVALID",
            duplicateCode = "APP_SPEC_SELECTOR_ID_DUPLICATE",
            label = "selector",
            diagnostics = diagnostics
        )
        selectors.forEach { selector ->
            validateText(
                selector.selector,
                "APP_SPEC_SELECTOR_INVALID",
                "selector ${selector.id}",
                diagnostics,
                256
            )
            validateText(
                selector.purpose,
                "APP_SPEC_SELECTOR_PURPOSE_INVALID",
                "selector ${selector.id} purpose",
                diagnostics,
                240
            )
            if (!dataTestIdSelectorRegex.matches(selector.selector)) {
                diagnostics += error(
                    code = "APP_SPEC_SELECTOR_FORMAT_INVALID",
                    message = "Selector ${selector.id} must be an exact data-testid attribute selector"
                )
            }
        }
        selectors.groupingBy { it.selector }.eachCount().filterValues { it > 1 }.keys.forEach { selector ->
            diagnostics += error(
                code = "APP_SPEC_SELECTOR_VALUE_DUPLICATE",
                message = "Selector value is duplicated: $selector"
            )
        }
        val selectorIds = selectors.map { it.id }.toSet()
        val selectorValues = selectors.map { it.selector }.toSet()

        val screens = spec.screens.orEmpty()
        if (screens.isEmpty()) {
            diagnostics += error("APP_SPEC_SCREENS_MISSING", "appSpec must declare at least one screen")
        }
        validateUniqueIds(
            values = screens.map { it.id },
            code = "APP_SPEC_SCREEN_ID_INVALID",
            duplicateCode = "APP_SPEC_SCREEN_ID_DUPLICATE",
            label = "screen",
            diagnostics = diagnostics
        )
        screens.forEach { screen ->
            validateText(screen.title, "APP_SPEC_SCREEN_TITLE_INVALID", "screen ${screen.id} title", diagnostics)
            validateText(
                screen.purpose,
                "APP_SPEC_SCREEN_PURPOSE_INVALID",
                "screen ${screen.id} purpose",
                diagnostics,
                300
            )
            validateSelectorReferences(screen.selectorIds, selectorIds, "screen ${screen.id}", diagnostics)
        }

        val components = spec.components.orEmpty()
        validateUniqueIds(
            values = components.map { it.id },
            code = "APP_SPEC_COMPONENT_ID_INVALID",
            duplicateCode = "APP_SPEC_COMPONENT_ID_DUPLICATE",
            label = "component",
            diagnostics = diagnostics
        )
        components.forEach { component ->
            validateText(
                component.name,
                "APP_SPEC_COMPONENT_NAME_INVALID",
                "component ${component.id} name",
                diagnostics
            )
            validateText(
                component.purpose,
                "APP_SPEC_COMPONENT_PURPOSE_INVALID",
                "component ${component.id} purpose",
                diagnostics,
                300
            )
            validateSelectorReferences(
                component.selectorIds,
                selectorIds,
                "component ${component.id}",
                diagnostics
            )
        }

        validateState(spec.state, diagnostics)

        val interactions = spec.interactions.orEmpty()
        if (interactions.isEmpty()) {
            diagnostics += error("APP_SPEC_INTERACTIONS_MISSING", "appSpec must declare interactions")
        }
        validateUniqueIds(
            values = interactions.map { it.id },
            code = "APP_SPEC_INTERACTION_ID_INVALID",
            duplicateCode = "APP_SPEC_INTERACTION_ID_DUPLICATE",
            label = "interaction",
            diagnostics = diagnostics
        )
        interactions.forEach { interaction ->
            validateText(
                interaction.title,
                "APP_SPEC_INTERACTION_TITLE_INVALID",
                "interaction ${interaction.id} title",
                diagnostics
            )
            if (interaction.triggerSelectorId !in selectorIds) {
                diagnostics += error(
                    code = "APP_SPEC_SELECTOR_REFERENCE_UNKNOWN",
                    message = "Interaction ${interaction.id} references unknown selector ${interaction.triggerSelectorId}"
                )
            }
            validateText(
                interaction.outcome,
                "APP_SPEC_INTERACTION_OUTCOME_INVALID",
                "interaction ${interaction.id} outcome",
                diagnostics,
                400
            )
        }

        val criteria = spec.acceptanceContract.criteria.orEmpty()
        WebAppAcceptanceValidator.validate(
            criteria = criteria,
            selectors = selectorValues,
            persistence = spec.state.persistence,
            storageKey = spec.state.storageKey,
            hasErrorStates = spec.state.errorStates.orEmpty().isNotEmpty(),
            diagnostics = diagnostics
        )
        val criterionIds = criteria.map { it.id }.toSet()
        val interactionIds = interactions.map { it.id }.toSet()

        val flows = spec.userFlows.orEmpty()
        if (flows.isEmpty()) {
            diagnostics += error("APP_SPEC_USER_FLOWS_MISSING", "appSpec must declare at least one user flow")
        }
        validateUniqueIds(
            values = flows.map { it.id },
            code = "APP_SPEC_USER_FLOW_ID_INVALID",
            duplicateCode = "APP_SPEC_USER_FLOW_ID_DUPLICATE",
            label = "user flow",
            diagnostics = diagnostics
        )
        flows.forEach { flow ->
            validateText(flow.title, "APP_SPEC_USER_FLOW_TITLE_INVALID", "user flow ${flow.id} title", diagnostics)
            if (flow.interactionIds.orEmpty().isEmpty()) {
                diagnostics += error(
                    code = "APP_SPEC_USER_FLOW_INTERACTIONS_MISSING",
                    message = "User flow ${flow.id} must reference interactions"
                )
            }
            flow.interactionIds.orEmpty().filterNot(interactionIds::contains).forEach { id ->
                diagnostics += error(
                    code = "APP_SPEC_INTERACTION_REFERENCE_UNKNOWN",
                    message = "User flow ${flow.id} references unknown interaction $id"
                )
            }
            if (flow.criterionIds.orEmpty().isEmpty()) {
                diagnostics += error(
                    code = "APP_SPEC_USER_FLOW_CRITERIA_MISSING",
                    message = "User flow ${flow.id} must reference acceptance criteria"
                )
            }
            flow.criterionIds.orEmpty().filterNot(criterionIds::contains).forEach { id ->
                diagnostics += error(
                    code = "APP_SPEC_CRITERION_REFERENCE_UNKNOWN",
                    message = "User flow ${flow.id} references unknown criterion $id"
                )
            }
        }
        val flowCriterionIds = flows.flatMap { it.criterionIds.orEmpty() }.toSet()
        criterionIds.filterNot(flowCriterionIds::contains).forEach { id ->
            diagnostics += error(
                code = "APP_SPEC_CRITERION_USER_FLOW_UNREFERENCED",
                message = "Acceptance criterion is not referenced by any user flow: $id"
            )
        }

        validateDesign(spec.design, diagnostics)
        validateCriterionOwnership(manifest.files, criterionIds, diagnostics)
        return diagnostics
    }

    private fun validateFileGraph(
        files: List<WebAppProjectFile>,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val paths = files.map { it.path }.toSet()
        files.forEach { file ->
            val dependencies = file.dependsOn.orEmpty()
            dependencies.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { dependency ->
                diagnostics += error(
                    code = "PROJECT_FILE_DEPENDENCY_DUPLICATE",
                    message = "File dependency is duplicated: $dependency",
                    path = file.path
                )
            }
            dependencies.forEach { dependency ->
                when {
                    dependency == file.path -> diagnostics += error(
                        code = "PROJECT_FILE_DEPENDENCY_SELF",
                        message = "File cannot depend on itself",
                        path = file.path
                    )

                    dependency !in paths -> diagnostics += error(
                        code = "PROJECT_FILE_DEPENDENCY_UNKNOWN",
                        message = "File depends on undeclared path $dependency",
                        path = file.path
                    )
                }
            }
        }

        val dependenciesByPath = files.associate { it.path to it.dependsOn.orEmpty().filter(paths::contains) }
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(path: String): List<String>? {
            if (path in visited) return null
            if (!visiting.add(path)) return visiting.dropWhile { it != path } + path
            dependenciesByPath[path].orEmpty().forEach { dependency ->
                visit(dependency)?.let { return it }
            }
            visiting.remove(path)
            visited += path
            return null
        }
        dependenciesByPath.keys.forEach { path ->
            visit(path)?.let { cycle ->
                diagnostics += error(
                    code = "PROJECT_FILE_DEPENDENCY_CYCLE",
                    message = "File dependency cycle: ${cycle.joinToString(" -> ")}"
                )
                return
            }
        }
    }

    private fun validateState(
        state: WebAppStateSpec,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val fields = state.fields.orEmpty()
        val duplicateFields = fields.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        duplicateFields.forEach { field ->
            diagnostics += error("APP_SPEC_STATE_FIELD_DUPLICATE", "State field is duplicated: $field")
        }
        fields.forEach { field ->
            if (!idRegex.matches(field.name)) {
                diagnostics += error(
                    "APP_SPEC_STATE_FIELD_NAME_INVALID",
                    "State field name is invalid: ${field.name}"
                )
            }
            validateText(field.type, "APP_SPEC_STATE_FIELD_TYPE_INVALID", "state field ${field.name} type", diagnostics)
            validateText(
                field.description,
                "APP_SPEC_STATE_FIELD_DESCRIPTION_INVALID",
                "state field ${field.name} description",
                diagnostics,
                300
            )
        }
        validateText(state.emptyState, "APP_SPEC_EMPTY_STATE_INVALID", "state.emptyState", diagnostics, 400)
        if (state.persistence == WebAppStatePersistence.STARLIT_STORAGE) {
            when {
                state.storageKey.isNullOrBlank() -> diagnostics += error(
                    "APP_SPEC_STORAGE_KEY_MISSING",
                    "STARLIT_STORAGE persistence requires a stable storageKey"
                )

                !criterionIdRegex.matches(state.storageKey) -> diagnostics += error(
                    "APP_SPEC_STORAGE_KEY_INVALID",
                    "storageKey must start with an ASCII letter and contain at most 64 letters, digits, dots, underscores, or hyphens"
                )
            }
        } else if (!state.storageKey.isNullOrBlank()) {
            diagnostics += error(
                "APP_SPEC_STORAGE_KEY_UNEXPECTED",
                "storageKey is only valid for STARLIT_STORAGE persistence"
            )
        }
    }

    private fun validateDesign(
        design: WebAppDesignSpec,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        validateText(design.theme, "APP_SPEC_DESIGN_THEME_INVALID", "design.theme", diagnostics)
        if (design.tokens.orEmpty().isEmpty()) {
            diagnostics += error("APP_SPEC_DESIGN_TOKENS_MISSING", "Design tokens must not be empty")
        }
        val duplicateTokens = design.tokens.orEmpty()
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateTokens.forEach { token ->
            diagnostics += error("APP_SPEC_DESIGN_TOKEN_DUPLICATE", "Design token is duplicated: $token")
        }
        design.tokens.orEmpty().forEach { token ->
            if (!idRegex.matches(token.name)) {
                diagnostics += error("APP_SPEC_DESIGN_TOKEN_NAME_INVALID", "Design token name is invalid: ${token.name}")
            }
            validateText(token.value, "APP_SPEC_DESIGN_TOKEN_VALUE_INVALID", "design token ${token.name}", diagnostics)
            validateText(
                token.purpose,
                "APP_SPEC_DESIGN_TOKEN_PURPOSE_INVALID",
                "design token ${token.name} purpose",
                diagnostics,
                240
            )
        }
        if (design.constraints.orEmpty().isEmpty()) {
            diagnostics += error("APP_SPEC_DESIGN_CONSTRAINTS_MISSING", "Design constraints must not be empty")
        }
        if (design.viewportWidths.orEmpty().isEmpty() ||
            design.viewportWidths.orEmpty().any { it !in 240..2_560 }
        ) {
            diagnostics += error(
                "APP_SPEC_VIEWPORTS_INVALID",
                "viewportWidths must contain supported widths between 240 and 2560"
            )
        }
        if (design.minTouchTargetPx !in 44..128) {
            diagnostics += error(
                "APP_SPEC_TOUCH_TARGET_INVALID",
                "minTouchTargetPx must be between 44 and 128"
            )
        }
    }

    private fun validateCriterionOwnership(
        files: List<WebAppProjectFile>,
        criterionIds: Set<String>,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        files.forEach { file ->
            file.ownsCriteria.orEmpty().filterNot(criterionIds::contains).forEach { id ->
                diagnostics += error(
                    "APP_SPEC_CRITERION_OWNERSHIP_UNKNOWN",
                    "File owns unknown acceptance criterion $id",
                    file.path
                )
            }
        }
        val owned = files.flatMap { it.ownsCriteria.orEmpty() }.toSet()
        criterionIds.filterNot(owned::contains).forEach { id ->
            diagnostics += error(
                "APP_SPEC_CRITERION_UNOWNED",
                "Acceptance criterion is not owned by any file: $id"
            )
        }
        files.filter { it.path != PROJECT_PLAN_FILE_NAME && it.description.isBlank() }.forEach { file ->
            diagnostics += error(
                "PROJECT_FILE_DESCRIPTION_MISSING",
                "Structured project files require a description",
                file.path
            )
        }
    }

    private fun validateSelectorReferences(
        references: List<String>,
        selectorIds: Set<String>,
        owner: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        if (references.orEmpty().isEmpty()) {
            diagnostics += error("APP_SPEC_SELECTOR_REFERENCES_MISSING", "$owner must reference selectors")
        }
        references.orEmpty().filterNot(selectorIds::contains).forEach { id ->
            diagnostics += error(
                "APP_SPEC_SELECTOR_REFERENCE_UNKNOWN",
                "$owner references unknown selector $id"
            )
        }
    }

    private fun validateUniqueIds(
        values: List<String>,
        code: String,
        duplicateCode: String,
        label: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        values.filterNot(idRegex::matches).forEach { id ->
            diagnostics += error(code, "$label id is invalid: $id")
        }
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            diagnostics += error(duplicateCode, "$label id is duplicated: $id")
        }
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
