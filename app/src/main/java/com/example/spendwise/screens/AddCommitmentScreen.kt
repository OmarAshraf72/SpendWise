package com.example.spendwise.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.*
import com.example.spendwise.viewmodel.CommitmentsViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val commitmentFormDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCommitmentScreen(
    commitmentId: Long? = null,
    onSaved: () -> Unit,
    viewModel: CommitmentsViewModel = viewModel()
) {
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var id by rememberSaveable { mutableStateOf(0L) }
    var originalCreatedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(CommitmentType.INSTALLMENT) }
    var frequency by rememberSaveable { mutableStateOf(CommitmentFrequency.MONTHLY) }
    var dueDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toEpochDay()) }
    var hasEndDate by rememberSaveable { mutableStateOf(false) }
    var endDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().plusYears(1).toEpochDay()) }
    var merchantId by rememberSaveable { mutableStateOf<Long?>(null) }
    var merchantQuery by rememberSaveable { mutableStateOf("") }
    var merchantFocused by remember { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }

    var repaymentMode by rememberSaveable { mutableStateOf<RepaymentMode?>(null) }
    var creditor by rememberSaveable { mutableStateOf("") }
    var originalPrincipal by rememberSaveable { mutableStateOf("") }
    var debtStartEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var hasFinancing by rememberSaveable { mutableStateOf(false) }
    var financingType by rememberSaveable { mutableStateOf(FinancingType.FIXED_TOTAL) }
    var financingExpanded by remember { mutableStateOf(false) }
    var totalRepayable by rememberSaveable { mutableStateOf("") }
    var ratePercent by rememberSaveable { mutableStateOf("") }
    var ratePeriod by rememberSaveable { mutableStateOf(RatePeriod.ANNUAL) }
    var rateBasis by rememberSaveable { mutableStateOf(RateBasis.ORIGINAL_PRINCIPAL) }
    var calculationMethod by rememberSaveable { mutableStateOf(FinancingCalculationMethod.CONTRACT_DEFINED) }
    var hasLateRule by rememberSaveable { mutableStateOf(false) }
    var graceDays by rememberSaveable { mutableStateOf("0") }
    var chargeType by rememberSaveable { mutableStateOf(LateChargeType.PERCENT_ONCE) }
    var chargeTypeExpanded by remember { mutableStateOf(false) }
    var chargeValue by rememberSaveable { mutableStateOf("") }
    var chargeInterval by rememberSaveable { mutableStateOf(LateChargeInterval.ONCE) }
    var chargeBasis by rememberSaveable { mutableStateOf(LateChargeBasis.OVERDUE_INSTALLMENT) }
    var minimumCharge by rememberSaveable { mutableStateOf("") }
    var maximumCharge by rememberSaveable { mutableStateOf("") }
    var isCompounding by rememberSaveable { mutableStateOf(false) }
    var debtExpectedEndEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var fixedDueConfirmed by rememberSaveable { mutableStateOf(commitmentId == null) }
    var loadingExisting by remember { mutableStateOf(commitmentId != null) }

    LaunchedEffect(commitmentId) {
        commitmentId?.let { key ->
            viewModel.getCommitment(key)?.let { existing ->
                id = existing.id; originalCreatedAt = existing.createdAt; title = existing.title
                amount = minorToInput(existing.amountMinor); type = existing.type; frequency = existing.frequency
                dueDateEpoch = existing.nextDueDateEpochDay; fixedDueConfirmed = true; hasEndDate = existing.endDateEpochDay != null
                existing.endDateEpochDay?.let { endDateEpoch = it }; merchantId = existing.merchantId; notes = existing.notes.orEmpty()
                viewModel.getDebtForCommitment(key)?.let { debt ->
                    creditor = debt.creditorName.orEmpty(); originalPrincipal = minorToInput(debt.originalPrincipalMinor)
                    debtStartEpoch = debt.startDateEpochDay; repaymentMode = debt.repaymentMode
                    debtExpectedEndEpoch = debt.expectedEndDateEpochDay
                    if (debt.repaymentMode == RepaymentMode.OPEN_ENDED) {
                        amount = ""; frequency = CommitmentFrequency.MONTHLY; hasEndDate = false
                    } else debt.expectedEndDateEpochDay?.let { hasEndDate = true; endDateEpoch = it }
                    viewModel.getFinancing(debt.id)?.let { terms ->
                        hasFinancing = true; financingType = terms.financingType
                        totalRepayable = terms.totalRepayableMinor?.let(::minorToInput).orEmpty()
                        ratePercent = terms.rateBasisPoints?.let(::basisPointsToInput).orEmpty()
                        terms.ratePeriod?.let { ratePeriod = it }; terms.rateBasis?.let { rateBasis = it }; terms.calculationMethod?.let { calculationMethod = it }
                    }
                    viewModel.getLateRule(debt.id)?.let { rule ->
                        hasLateRule = true; graceDays = rule.gracePeriodDays.toString(); chargeType = rule.chargeType
                        chargeValue = if (rule.chargeType.name.startsWith("FIXED")) rule.fixedChargeMinor?.let(::minorToInput).orEmpty() else rule.rateBasisPoints?.let(::basisPointsToInput).orEmpty()
                        chargeInterval = rule.chargeInterval; chargeBasis = rule.chargeBasis
                        minimumCharge = rule.minimumChargeMinor?.let(::minorToInput).orEmpty(); maximumCharge = rule.maximumChargeMinor?.let(::minorToInput).orEmpty(); isCompounding = rule.isCompounding
                    }
                }
            }
        }
        loadingExisting = false
    }
    LaunchedEffect(merchantId, merchants) {
        if (merchantId != null && merchantQuery.isBlank()) merchantQuery = merchants.firstOrNull { it.id == merchantId }?.displayName.orEmpty()
    }

    val isDebt = type == CommitmentType.INSTALLMENT || type == CommitmentType.DEBT_PAYMENT
    val fixedSchedule = !isDebt || repaymentMode == RepaymentMode.FIXED_INSTALLMENTS
    val parsedAmount = parseEgpToMinor(amount)
    val parsedOriginal = parseEgpToMinor(originalPrincipal)
    val draftFinancingTerms = if (hasFinancing) buildFinancingTerms(
        true, financingType, totalRepayable, ratePercent, ratePeriod, rateBasis, calculationMethod
    ) else null
    val isOneTimeFixedDebt = isDebt && fixedSchedule && frequency == CommitmentFrequency.ONE_TIME
    val oneTimeDebtAmount = if (isOneTimeFixedDebt && (!hasFinancing || draftFinancingTerms != null)) {
        DebtCalculator.oneTimeAmount(parsedOriginal ?: 0, draftFinancingTerms, parsedAmount)
    } else null
    val scheduledAmountMinor = if (isOneTimeFixedDebt) oneTimeDebtAmount?.amountMinor else parsedAmount
    val oneTimeNeedsExplicitAmount = isOneTimeFixedDebt && hasFinancing &&
        financingType in setOf(FinancingType.USER_PROVIDED_RATE, FinancingType.UNKNOWN_DETAILS)
    val effectiveHasEndDate = hasEndDate && frequency != CommitmentFrequency.ONE_TIME && fixedSchedule
    val invalidEnd = effectiveHasEndDate && endDateEpoch < dueDateEpoch
    val previewDates = if (fixedSchedule) CommitmentRecurrence.preview(frequency, LocalDate.ofEpochDay(dueDateEpoch), endDateEpoch.takeIf { effectiveHasEndDate }?.let(LocalDate::ofEpochDay)) else emptyList()
    val merchantSuggestions = remember(merchantQuery, merchants) { MerchantRanker.rank(merchantQuery, merchants) }

    if (loadingExisting) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (commitmentId == null) "Add Commitment" else "Edit Commitment", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, isError = showValidation && title.isBlank(), modifier = Modifier.fillMaxWidth())
        EnumDropdown("Type", type.displayName(), CommitmentType.entries.toList(), typeExpanded, { typeExpanded = it }, { type = it; typeExpanded = false }) { it.displayName() }

        if (isDebt) {
            SectionLabel("Debt details")
            OutlinedTextField(creditor, { creditor = it }, label = { Text("Creditor / entity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField("Original principal", originalPrincipal, { originalPrincipal = it }, showValidation && (parsedOriginal == null || parsedOriginal <= 0))
            DateField("Debt start date", LocalDate.ofEpochDay(debtStartEpoch)) { picker = "debtStart" }
            Text("Repayment style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RepaymentMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (repaymentMode != option) {
                        amount = ""
                        frequency = CommitmentFrequency.MONTHLY
                        dueDateEpoch = LocalDate.now().plusDays(1).toEpochDay()
                        fixedDueConfirmed = option == RepaymentMode.OPEN_ENDED
                        hasEndDate = false
                        debtExpectedEndEpoch = null
                    }
                    repaymentMode = option
                }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = repaymentMode == option, onClick = {
                        if (repaymentMode != option) {
                            amount = ""
                            frequency = CommitmentFrequency.MONTHLY
                            dueDateEpoch = LocalDate.now().plusDays(1).toEpochDay()
                            fixedDueConfirmed = option == RepaymentMode.OPEN_ENDED
                            hasEndDate = false
                            debtExpectedEndEpoch = null
                        }
                        repaymentMode = option
                    })
                    Text(option.displayName(), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (showValidation && repaymentMode == null) Text("Choose a repayment style.", color = MaterialTheme.colorScheme.error)
        }

        if (fixedSchedule) {
            if (isDebt) {
                EnumDropdown("Frequency", frequency.displayName(), CommitmentFrequency.entries.filter { it != CommitmentFrequency.CUSTOM }, frequencyExpanded, { frequencyExpanded = it }, { frequency = it; frequencyExpanded = false }) { it.displayName() }
                when {
                    isOneTimeFixedDebt && oneTimeNeedsExplicitAmount -> MoneyField(
                        "Amount due", amount, { amount = it }, showValidation && (scheduledAmountMinor == null || scheduledAmountMinor <= 0)
                    )
                    isOneTimeFixedDebt -> ReadOnlyAmountDue(oneTimeDebtAmount)
                    else -> MoneyField("Installment amount", amount, { amount = it }, showValidation && (parsedAmount == null || parsedAmount <= 0))
                }
            } else {
                MoneyField("Amount", amount, { amount = it }, showValidation && (parsedAmount == null || parsedAmount <= 0))
                EnumDropdown("Frequency", frequency.displayName(), CommitmentFrequency.entries.filter { it != CommitmentFrequency.CUSTOM }, frequencyExpanded, { frequencyExpanded = it }, { frequency = it; frequencyExpanded = false }) { it.displayName() }
            }
            DateField(if (frequency == CommitmentFrequency.ONE_TIME) "Due date" else "First payment date", LocalDate.ofEpochDay(dueDateEpoch), showValidation && !fixedDueConfirmed) { picker = "due" }
            if (showValidation && !fixedDueConfirmed) Text("Select the first payment date.", color = MaterialTheme.colorScheme.error)
            if (frequency != CommitmentFrequency.ONE_TIME) EndDateFields(hasEndDate, endDateEpoch, dueDateEpoch, invalidEnd, { hasEndDate = it }, { endDateEpoch = it }, { picker = "end" })
        } else if (isDebt && repaymentMode == RepaymentMode.OPEN_ENDED) {
            Text("Open-ended debts create no fictional future payments or forecast amounts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            debtExpectedEndEpoch?.let { epoch -> DateField("Expected end date (optional)", LocalDate.ofEpochDay(epoch)) { picker = "debtExpectedEnd" } }
            if (debtExpectedEndEpoch == null) OutlinedButton(onClick = { picker = "debtExpectedEnd" }, modifier = Modifier.fillMaxWidth()) { Text("Add expected end date") }
            if (debtExpectedEndEpoch != null) TextButton(onClick = { debtExpectedEndEpoch = null }) { Text("Clear expected end date") }
            if (showValidation && debtExpectedEndEpoch != null && debtExpectedEndEpoch!! < debtStartEpoch) Text("Expected end date must follow the debt start date.", color = MaterialTheme.colorScheme.error)
        }

        if (isDebt) {
            ToggleRow("Includes financing cost or interest?", hasFinancing) { hasFinancing = it }
            if (hasFinancing) {
                EnumDropdown("Financing terms", financingType.displayName(), FinancingType.entries.toList(), financingExpanded, { financingExpanded = it }, { financingType = it; financingExpanded = false }) { it.displayName() }
                when (financingType) {
                    FinancingType.FIXED_TOTAL -> MoneyField("Total repayable", totalRepayable, { totalRepayable = it })
                    FinancingType.USER_PROVIDED_RATE -> {
                        OutlinedTextField(ratePercent, { ratePercent = it }, label = { Text("Contract rate (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                        SimpleEnumDropdown("Rate period", ratePeriod, RatePeriod.entries) { ratePeriod = it }
                        SimpleEnumDropdown("Rate basis", rateBasis, RateBasis.entries) { rateBasis = it }
                        SimpleEnumDropdown("Calculation method", calculationMethod, FinancingCalculationMethod.entries) { calculationMethod = it }
                        Text("Rate metadata is stored, but no amortization is invented.", style = MaterialTheme.typography.bodySmall)
                    }
                    FinancingType.UNKNOWN_DETAILS -> Text("Terms will be recorded as unknown; no financing calculation is produced.")
                }
            }
            ToggleRow("Late payment rule?", hasLateRule) { hasLateRule = it }
            if (hasLateRule) {
                OutlinedTextField(graceDays, { graceDays = it.filter(Char::isDigit) }, label = { Text("Grace period (complete days)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                EnumDropdown("Charge method", chargeType.displayName(), LateChargeType.entries.toList(), chargeTypeExpanded, { chargeTypeExpanded = it }, { chargeType = it; chargeTypeExpanded = false }) { it.displayName() }
                OutlinedTextField(chargeValue, { chargeValue = it }, label = { Text(if (chargeType.name.startsWith("FIXED")) "Charge value (EGP)" else "Charge value (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (chargeType.name.endsWith("PERIODIC")) SimpleEnumDropdown("Applied", chargeInterval, listOf(LateChargeInterval.DAILY, LateChargeInterval.WEEKLY, LateChargeInterval.MONTHLY)) { chargeInterval = it }
                SimpleEnumDropdown("Calculated on", chargeBasis, LateChargeBasis.entries) { chargeBasis = it }
                MoneyField("Minimum charge (optional)", minimumCharge, { minimumCharge = it })
                MoneyField("Maximum charge (optional)", maximumCharge, { maximumCharge = it })
                if (chargeType == LateChargeType.PERCENT_PERIODIC) ToggleRow("Compound periodic charges", isCompounding) { isCompounding = it }
            }
        }

        MerchantField(merchantQuery, { merchantQuery = it; merchantId = null }, merchantFocused, { merchantFocused = it }, merchantSuggestions,
            onSelect = { merchantId = it.id; merchantQuery = it.displayName; merchantFocused = false; focusManager.clearFocus() },
            onAdd = { viewModel.createMerchant(merchantQuery) { created -> merchantId = created?.id; created?.let { merchantQuery = it.displayName }; merchantFocused = false; focusManager.clearFocus() } })
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())

        if (fixedSchedule) {
            SectionLabel(if (frequency == CommitmentFrequency.ONE_TIME) "One-time payment" else "Upcoming payments")
            previewDates.forEach { Text(it.format(commitmentFormDateFormatter), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (previewDates.isEmpty() && invalidEnd) Text("Choose an end date on or after the first payment.", color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = {
            showValidation = true
            val validBase = title.isNotBlank() && (!isDebt || repaymentMode != null) && !invalidEnd &&
                (fixedSchedule || debtExpectedEndEpoch == null || debtExpectedEndEpoch!! >= debtStartEpoch) &&
                (!fixedSchedule || fixedDueConfirmed && scheduledAmountMinor != null && scheduledAmountMinor > 0)
            if (validBase && (!isDebt || parsedOriginal != null && parsedOriginal > 0)) {
                val now = System.currentTimeMillis()
                val due = if (fixedSchedule) LocalDate.ofEpochDay(dueDateEpoch) else LocalDate.ofEpochDay(debtStartEpoch)
                val commitment = FinancialCommitmentEntity(id, title.trim(), if (fixedSchedule) scheduledAmountMinor!! else 0, type,
                    if (fixedSchedule) frequency else CommitmentFrequency.ONE_TIME, due.toEpochDay(), endDateEpoch.takeIf { effectiveHasEndDate }, due.toEpochDay(), true,
                    merchantId, notes.trim().takeIf(String::isNotBlank), originalCreatedAt ?: now, now)
                if (!isDebt) viewModel.saveCommitment(id, title, parsedAmount!!, type, frequency, due, endDateEpoch.takeIf { effectiveHasEndDate }?.let(LocalDate::ofEpochDay), merchantId, notes, originalCreatedAt, onSaved)
                else {
                    val financingTerms = draftFinancingTerms
                    val lateRule = buildLateRule(hasLateRule, graceDays, chargeType, chargeValue, chargeInterval, chargeBasis, minimumCharge, maximumCharge, isCompounding)
                    if ((hasFinancing && financingTerms == null) || (hasLateRule && lateRule == null)) return@Button
                    if (financingTerms?.financingType == FinancingType.FIXED_TOTAL && financingTerms.totalRepayableMinor!! < parsedOriginal!!) return@Button
                    val expectedEnd = if (repaymentMode == RepaymentMode.OPEN_ENDED) debtExpectedEndEpoch?.let(LocalDate::ofEpochDay)
                        else endDateEpoch.takeIf { effectiveHasEndDate }?.let(LocalDate::ofEpochDay)
                    viewModel.saveDebt(DebtDefinitionInput(commitment, creditor, parsedOriginal!!, LocalDate.ofEpochDay(debtStartEpoch), expectedEnd, checkNotNull(repaymentMode), notes, financingTerms, lateRule), onSaved)
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save Commitment") }
    }

    picker?.let { target ->
        val initial = when (target) { "debtStart" -> debtStartEpoch; "due" -> dueDateEpoch; "debtExpectedEnd" -> debtExpectedEndEpoch ?: LocalDate.ofEpochDay(debtStartEpoch).plusYears(1).toEpochDay(); else -> endDateEpoch }
        SpendWiseDatePickerDialog(LocalDate.ofEpochDay(initial), onDateSelected = { date ->
            when (target) { "debtStart" -> debtStartEpoch = date.toEpochDay(); "due" -> { dueDateEpoch = date.toEpochDay(); fixedDueConfirmed = true }; "debtExpectedEnd" -> debtExpectedEndEpoch = date.toEpochDay(); else -> endDateEpoch = date.toEpochDay() }
            picker = null
        }, onDismiss = { picker = null })
    }
}

@Composable private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, modifier = Modifier.padding(top = 12.dp).weight(1f)); Switch(checked, onChange) }
}

@Composable private fun MoneyField(label: String, value: String, onChange: (String) -> Unit, error: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, suffix = { Text("EGP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = error, modifier = Modifier.fillMaxWidth())
}

@Composable private fun ReadOnlyAmountDue(amount: OneTimeDebtAmount?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Amount due", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(amount?.amountMinor?.let(::formatEgp) ?: "Enter valid financing terms below", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val source = when (amount?.source) {
                OneTimeAmountSource.PRINCIPAL -> "original principal"
                OneTimeAmountSource.FIXED_TOTAL_REPAYABLE -> "fixed total repayable"
                OneTimeAmountSource.EXPLICIT_AMOUNT_DUE -> "entered amount due"
                null -> "debt terms"
            }
            Text("Derived from $source. Late charges are handled separately.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun EndDateFields(hasEnd: Boolean, endEpoch: Long, dueEpoch: Long, invalid: Boolean, setHasEnd: (Boolean) -> Unit, setEnd: (Long) -> Unit, open: () -> Unit) {
    Text("Ends", style = MaterialTheme.typography.labelLarge)
    if (hasEnd) {
        DateField("End date", LocalDate.ofEpochDay(endEpoch), invalid, open)
        TextButton(onClick = { setHasEnd(false) }) { Text("Clear end date") }
    } else OutlinedButton(onClick = { setHasEnd(true); if (endEpoch < dueEpoch) setEnd(LocalDate.ofEpochDay(dueEpoch).plusYears(1).toEpochDay()); open() }, modifier = Modifier.fillMaxWidth()) {
        Text("No end date", Modifier.weight(1f)); Icon(Icons.Outlined.DateRange, "Choose end date")
    }
}

@Composable private fun MerchantField(query: String, onQuery: (String) -> Unit, focused: Boolean, onFocus: (Boolean) -> Unit, suggestions: List<MerchantSuggestion>, onSelect: (MerchantEntity) -> Unit, onAdd: () -> Unit) {
    OutlinedTextField(query, onQuery, label = { Text("Merchant (optional)") }, placeholder = { Text("Search merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) })
    if (focused) InlineSuggestionList(
        suggestions.take(5).map { InlineSuggestion(it.merchant.id, it.merchant, it.merchant.displayName) }, onSelected = onSelect,
        addLabel = if (shouldOfferNewMerchant(query, suggestions)) "Add “${query.trim()}”" else null,
        onAdd = if (shouldOfferNewMerchant(query, suggestions)) onAdd else null
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun <T> EnumDropdown(label: String, value: String, values: List<T>, expanded: Boolean, setExpanded: (Boolean) -> Unit, select: (T) -> Unit, name: (T) -> String) {
    ExposedDropdownMenuBox(expanded, setExpanded) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { setExpanded(false) }) { values.forEach { item -> DropdownMenuItem({ Text(name(item)) }, onClick = { select(item) }) } }
    }
}

@Composable private fun <T : Enum<T>> SimpleEnumDropdown(label: String, value: T, values: List<T>, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    EnumDropdown(label, value.displayName(), values, expanded, { expanded = it }, { onSelected(it); expanded = false }) { it.displayName() }
}

@Composable private fun DateField(label: String, date: LocalDate, isError: Boolean = false, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick, Modifier.fillMaxWidth()) { Text(date.format(commitmentFormDateFormatter), Modifier.weight(1f)); Icon(Icons.Outlined.DateRange, "Choose $label") }
        if (isError) Text("End date must be on or after the first payment date", color = MaterialTheme.colorScheme.error)
    }
}

private fun buildFinancingTerms(enabled: Boolean, type: FinancingType, total: String, rate: String, period: RatePeriod, basis: RateBasis, method: FinancingCalculationMethod): FinancingTermsEntity? {
    if (!enabled) return null
    val totalMinor = if (type == FinancingType.FIXED_TOTAL) parseEgpToMinor(total)?.takeIf { it > 0 } ?: return null else null
    val basisPoints = if (type == FinancingType.USER_PROVIDED_RATE) parsePercentBasisPoints(rate) ?: return null else null
    return FinancingTermsEntity(financingType = type, debtProfileId = 0, totalRepayableMinor = totalMinor, rateBasisPoints = basisPoints,
        ratePeriod = period.takeIf { type == FinancingType.USER_PROVIDED_RATE }, rateBasis = basis.takeIf { type == FinancingType.USER_PROVIDED_RATE },
        calculationMethod = method.takeIf { type == FinancingType.USER_PROVIDED_RATE }, createdAt = 0, updatedAt = 0)
}

private fun buildLateRule(enabled: Boolean, grace: String, type: LateChargeType, value: String, interval: LateChargeInterval, basis: LateChargeBasis, minimum: String, maximum: String, compound: Boolean): LatePaymentRuleEntity? {
    if (!enabled) return null
    val graceDays = grace.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val fixed = if (type.name.startsWith("FIXED")) parseEgpToMinor(value)?.takeIf { it >= 0 } ?: return null else null
    val rate = if (type.name.startsWith("PERCENT")) parsePercentBasisPoints(value)?.takeIf { it >= 0 } ?: return null else null
    val actualInterval = if (type.name.endsWith("ONCE")) LateChargeInterval.ONCE else interval
    val minMinor = minimum.takeIf(String::isNotBlank)?.let(::parseEgpToMinor)
    val maxMinor = maximum.takeIf(String::isNotBlank)?.let(::parseEgpToMinor)
    if ((minimum.isNotBlank() && minMinor == null) || (maximum.isNotBlank() && maxMinor == null) ||
        (minMinor != null && maxMinor != null && minMinor > maxMinor)) return null
    return LatePaymentRuleEntity(debtProfileId = 0, gracePeriodDays = graceDays, chargeType = type, fixedChargeMinor = fixed, rateBasisPoints = rate,
        chargeInterval = actualInterval, chargeBasis = basis, minimumChargeMinor = minMinor,
        maximumChargeMinor = maxMinor, isCompounding = compound && type == LateChargeType.PERCENT_PERIODIC,
        createdAt = 0, updatedAt = 0)
}

private fun parsePercentBasisPoints(value: String): Long? = try {
    BigDecimal(value.trim()).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
} catch (_: Exception) { null }

private fun basisPointsToInput(value: Long): String = BigDecimal.valueOf(value).movePointLeft(2).stripTrailingZeros().toPlainString()
private fun minorToInput(value: Long): String = if (value % 100L == 0L) (value / 100L).toString() else "${value / 100L}.${(value % 100L).toString().padStart(2, '0')}"
private fun Enum<*>.displayName(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
