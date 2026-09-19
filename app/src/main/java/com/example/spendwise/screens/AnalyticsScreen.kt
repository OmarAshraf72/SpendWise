package com.example.spendwise.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.formatEgp
import com.example.spendwise.viewmodel.AnalyticsCategoryUi
import com.example.spendwise.viewmodel.AnalyticsPeriod
import com.example.spendwise.viewmodel.AnalyticsUiState
import com.example.spendwise.viewmodel.AnalyticsViewModel
import com.example.spendwise.viewmodel.TrendPointUi

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Analytics", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        PeriodSelector(uiState.selectedPeriod, viewModel::selectPeriod)
        AnalyticsSummary(uiState)
        if (uiState.totalSpentMinor == 0L) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "No expenses were recorded in this period. Try another period or add an expense.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        CategoryAnalysis(uiState.categories)
        SpendingTrend(uiState.trend, uiState.selectedPeriod.usesDailyTrend)
        TopCategories(uiState.categories.take(3))
        AnalyticsInsights(uiState.insights)
    }
}

@Composable
private fun PeriodSelector(selected: AnalyticsPeriod, onSelect: (AnalyticsPeriod) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnalyticsPeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.label) }
            )
        }
    }
}

@Composable
private fun AnalyticsSummary(state: AnalyticsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Period summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SummaryRow("Total spent", formatEgp(state.totalSpentMinor), emphasized = true)
            SummaryRow("Total income", formatEgp(state.totalIncomeMinor))
            SummaryRow("Remaining", formatEgp(state.remainingMinor))
            SummaryRow("Expense transactions", state.expenseCount.toString())
            SummaryRow("Average expense", formatEgp(state.averageExpenseMinor))
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasized: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CategoryAnalysis(categories: List<AnalyticsCategoryUi>) {
    val colors = chartColors()
    val total = categories.fold(0L) { sum, item -> sum + item.amountMinor }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Category spending", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DonutChart(categories, total, colors)
                if (categories.isEmpty()) {
                    Text("No category spending to display.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    categories.forEachIndexed { index, category ->
                        CategoryLegendRow(category, colors[index % colors.size])
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(categories: List<AnalyticsCategoryUi>, total: Long, colors: List<Color>) {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.size(190.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.18f, cap = StrokeCap.Butt)
        if (total <= 0L) {
            drawCircle(color = emptyColor, style = stroke)
        } else {
            var startAngle = -90f
            categories.forEachIndexed { index, category ->
                val sweep = category.amountMinor.toFloat() / total.toFloat() * 360f
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke
                )
                startAngle += sweep
            }
        }
    }
}

@Composable
private fun CategoryLegendRow(category: AnalyticsCategoryUi, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(formatEgp(category.amountMinor), fontWeight = FontWeight.Medium)
            Text(
                formatAnalyticsPercentage(category.percentageTenths),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpendingTrend(points: List<TrendPointUi>, daily: Boolean) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val maxAmount = points.maxOfOrNull { it.amountMinor } ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Spending trend", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (daily) "Daily expense totals" else "Monthly expense totals",
                    style = MaterialTheme.typography.titleMedium
                )
                Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
                    drawLine(
                        color = trackColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f
                    )
                    if (points.isNotEmpty() && maxAmount > 0L) {
                        val slotWidth = size.width / points.size
                        val barWidth = slotWidth * 0.62f
                        points.forEachIndexed { index, point ->
                            val ratio = point.amountMinor.toFloat() / maxAmount.toFloat()
                            val barHeight = size.height * ratio
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(index * slotWidth + (slotWidth - barWidth) / 2f, size.height - barHeight),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(5f, 5f)
                            )
                        }
                    }
                }
                TrendLabels(points, daily)
                if (maxAmount == 0L) {
                    Text("No spending data for this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "Highest ${if (daily) "daily" else "monthly"} total: ${formatEgp(maxAmount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendLabels(points: List<TrendPointUi>, daily: Boolean) {
    if (points.isEmpty()) return
    val labels = if (!daily || points.size <= 6) {
        points.map { it.label }
    } else {
        listOf(points.first().label, points[points.lastIndex / 2].label, points.last().label)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun TopCategories(categories: List<AnalyticsCategoryUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Top Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (categories.isEmpty()) {
                    Text("No categories to rank.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    categories.forEachIndexed { index, category ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${index + 1}. ${category.name}", modifier = Modifier.weight(1f))
                            Text(formatEgp(category.amountMinor), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsInsights(insights: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            insights.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun chartColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.tertiaryContainer
)

private fun formatAnalyticsPercentage(tenths: Int): String =
    if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
