// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.ljubisap.smarttestpicker.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.ljubisap.smarttestpicker.mapper.ClassCoverageMetrics;


/**
 * Generates a self-contained HTML dashboard report for test selection results.
 *
 * <p>The report is a single HTML file with all CSS and JS inlined — no external
 * dependencies, works offline, suitable for CI artifacts and paper screenshots.</p>
 *
 * <p>This class has no Gradle dependency and is fully unit-testable.</p>
 */
public class HtmlReportGenerator
{

	/**
	 * Generates the complete HTML report from the given report data.
	 *
	 * @param data all data needed to render the report
	 * @return the complete HTML document as a string
	 */
	public String generate(ReportData data)
	{
		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html>\n<html lang=\"en\">\n");
		html.append(buildHead());
		html.append("<body>\n");
		html.append(buildHeader(data));
		html.append(buildTabBar(data));

		// --- Selection tab ---
		html.append("<div id=\"tab-selection\" class=\"tab-content active\">\n");

		if (data.isFullSuite())
		{
			html.append(buildWarningBanner("Full Suite Required", data.getFullSuiteReason()));
		}
		else if (data.isNone())
		{
			html.append(buildSuccessBanner("No Changes Detected",
					"No production code changes since the coverage map was generated."));
		}

		html.append(buildStatCards(data));
		html.append(buildMetadataSection(data));
		html.append(buildChartsSection(data));
		html.append(buildChangedCodeSection(data));

		if (!data.isFullSuite() && !data.isNone())
		{
			html.append(buildCoverageMatrix(data));
		}

		if (data.isClassLevelSelection() && !data.isFullSuite() && !data.isNone())
		{
			html.append(buildClassLevelExpansionSection(data));
		}

		html.append(buildUnmappedTestsSection(data));
		html.append(buildTestListSection(data));
		html.append("</div>\n");

		// --- Coverage Metrics tab ---
		html.append("<div id=\"tab-metrics\" class=\"tab-content\">\n");
		html.append(buildCoverageMetricsSection(data));
		html.append("</div>\n");

		// --- Coverage Map tab ---
		html.append("<div id=\"tab-map\" class=\"tab-content\">\n");
		html.append(buildFullCoverageMap(data));
		html.append("</div>\n");

		html.append(buildFooter());
		html.append(buildScript());
		html.append("</body>\n</html>\n");
		return html.toString();
	}

	private String buildTabBar(ReportData data)
	{
		boolean hasMetrics = data.getClassMetrics() != null && !data.getClassMetrics().isEmpty();
		boolean hasMap = data.getTestMappings() != null && !data.getTestMappings().isEmpty();
		StringBuilder sb = new StringBuilder();
		sb.append("<div class=\"tab-bar\">\n");
		sb.append("  <div class=\"tab-item active\" onclick=\"switchTab('tab-selection')\">Selection</div>\n");
		sb.append("  <div class=\"tab-item" + (hasMetrics ? "" : " tab-disabled") + "\" onclick=\"switchTab('tab-metrics')\">");
		sb.append("Coverage Metrics");
		if (!hasMetrics)
		{
			sb.append(" <span class=\"tab-badge\">N/A</span>");
		}
		sb.append("</div>\n");
		sb.append("  <div class=\"tab-item" + (hasMap ? "" : " tab-disabled") + "\" onclick=\"switchTab('tab-map')\">");
		sb.append("Coverage Map");
		if (!hasMap)
		{
			sb.append(" <span class=\"tab-badge\">N/A</span>");
		}
		sb.append("</div>\n");
		sb.append("</div>\n");
		return sb.toString();
	}

	private String buildCoverageMetricsSection(ReportData data)
	{
		Map<String, ClassCoverageMetrics> metrics = data.getClassMetrics();
		StringBuilder sb = new StringBuilder();

		if (metrics == null || metrics.isEmpty())
		{
			sb.append("<div class=\"section\">\n");
			sb.append("  <div class=\"section-title\">Coverage Metrics</div>\n");
			sb.append("  <p class=\"metrics-unavailable\">Coverage metrics not available. ");
			sb.append("Regenerate the coverage map to enable this feature.</p>\n");
			sb.append("</div>\n");
			return sb.toString();
		}

		// Compute summary stats
		int totalClasses = metrics.size();
		int uncovered = 0;
		int poorlyCovered = 0;
		int wellCovered = 0;
		for (ClassCoverageMetrics m : metrics.values())
		{
			double pct = m.getLineCoveragePercent();
			if (pct == 0) uncovered++;
			else if (pct < 50) poorlyCovered++;
			else if (pct >= 75) wellCovered++;
		}
		int moderate = totalClasses - uncovered - poorlyCovered - wellCovered;

		// Summary cards
		sb.append("<div class=\"stats\">\n");
		sb.append(metricCard("Total Classes", String.valueOf(totalClasses), "#3498db"));
		sb.append(metricCard("Uncovered (0%)", String.valueOf(uncovered), "#e74c3c"));
		sb.append(metricCard("Poor (<50%)", String.valueOf(poorlyCovered), "#f39c12"));
		sb.append(metricCard("Moderate (50-75%)", String.valueOf(moderate), "#f1c40f"));
		sb.append(metricCard("Good (\u226575%)", String.valueOf(wellCovered), "#27ae60"));
		sb.append("</div>\n");

		// Filter buttons
		sb.append("<div class=\"section\">\n");
		sb.append("  <div class=\"section-title\">Class Coverage</div>\n");
		sb.append("  <div class=\"metrics-filters\">\n");
		sb.append("    <button class=\"filter-btn active\" onclick=\"filterMetrics('all')\">All (")
				.append(totalClasses).append(")</button>\n");
		sb.append("    <button class=\"filter-btn\" onclick=\"filterMetrics('uncovered')\">Uncovered (")
				.append(uncovered).append(")</button>\n");
		sb.append("    <button class=\"filter-btn\" onclick=\"filterMetrics('poor')\">Below 50% (")
				.append(uncovered + poorlyCovered).append(")</button>\n");
		sb.append("    <button class=\"filter-btn\" onclick=\"filterMetrics('good')\">Above 75% (")
				.append(wellCovered).append(")</button>\n");
		sb.append("  </div>\n");

		// Table
		sb.append("  <table class=\"data-table metrics-table\" id=\"metrics-table\">\n");
		sb.append("  <thead><tr>");
		sb.append("<th onclick=\"sortMetrics('name')\" class=\"sortable\" data-sort=\"name\">Class <span class=\"sort-arrow\"></span></th>");
		sb.append("<th onclick=\"sortMetrics('line')\" class=\"sortable\" data-sort=\"line\">Line Coverage <span class=\"sort-arrow\"></span></th>");
		sb.append("<th onclick=\"sortMetrics('branch')\" class=\"sortable\" data-sort=\"branch\">Branch Coverage <span class=\"sort-arrow\"></span></th>");
		sb.append("</tr></thead>\n");
		sb.append("  <tbody id=\"metrics-table-body\">\n");

		// Sort by line coverage ascending (worst first)
		List<Map.Entry<String, ClassCoverageMetrics>> sorted = new ArrayList<>(metrics.entrySet());
		sorted.sort((a, b) -> Double.compare(a.getValue().getLineCoveragePercent(), b.getValue().getLineCoveragePercent()));

		for (Map.Entry<String, ClassCoverageMetrics> entry : sorted)
		{
			String cls = entry.getKey();
			ClassCoverageMetrics m = entry.getValue();
			double linePct = m.getLineCoveragePercent();
			double branchPct = m.getBranchCoveragePercent();

			String filterClass;
			if (linePct == 0) filterClass = "uncovered poor";
			else if (linePct < 50) filterClass = "poor";
			else if (linePct >= 75) filterClass = "good";
			else filterClass = "";

			sb.append("  <tr class=\"metrics-row ").append(filterClass).append("\"");
			sb.append(" data-class=\"").append(esc(cls)).append("\"");
			sb.append(" data-line-pct=\"").append(String.format(Locale.US, "%.1f", linePct)).append("\"");
			sb.append(" data-branch-pct=\"").append(String.format(Locale.US, "%.1f", branchPct)).append("\"");
			sb.append(">\n");
			sb.append("    <td class=\"cls-name\">").append(esc(cls)).append("</td>\n");
			sb.append("    <td>").append(coverageBar(cls, linePct)).append("</td>\n");
			sb.append("    <td>").append(coverageBar(cls + "_branch", branchPct)).append("</td>\n");
			sb.append("  </tr>\n");
		}

		sb.append("  </tbody>\n");
		sb.append("  </table>\n");
		sb.append("</div>\n");

		return sb.toString();
	}

	private String metricCard(String label, String value, String color)
	{
		return "<div class=\"stat-card\">\n"
				+ "  <div class=\"stat-value\" style=\"color: " + color + "\">" + esc(value) + "</div>\n"
				+ "  <div class=\"stat-label\">" + esc(label) + "</div>\n"
				+ "</div>\n";
	}

	private String buildHead()
	{
		return "<head>\n"
				+ "<meta charset=\"UTF-8\">\n"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
				+ "<title>Smart Test Picker Report</title>\n"
				+ "<style>\n" + getCSS() + "</style>\n"
				+ "</head>\n";
	}

	private String buildHeader(ReportData data)
	{
		String shortCommit = data.getCommitId() != null && data.getCommitId().length() >= 7
				? data.getCommitId().substring(0, 7) : "unknown";
		String branch = data.getCurrentBranch() != null ? data.getCurrentBranch() : "unknown";
		String time = data.getTimestamp() != null ? data.getTimestamp() : "";

		return "<header class=\"header\">\n"
				+ "  <div class=\"header-left\">\n"
				+ "    <h1>Smart Test Picker</h1>\n"
				+ "    <span class=\"header-subtitle\">Test Selection Report</span>\n"
				+ "  </div>\n"
				+ "  <div class=\"header-right\">\n"
				+ "    <span class=\"header-badge\">" + esc(branch) + "</span>\n"
				+ "    <span class=\"header-commit\">" + esc(shortCommit) + "</span>\n"
				+ "    <span class=\"header-time\">" + esc(time) + "</span>\n"
				+ "  </div>\n"
				+ "</header>\n";
	}

	private String buildWarningBanner(String title, String message)
	{
		return "<div class=\"banner banner-warning\">\n"
				+ "  <span class=\"banner-icon\">&#9888;</span>\n"
				+ "  <div><strong>" + esc(title) + "</strong><br>" + esc(message != null ? message : "") + "</div>\n"
				+ "</div>\n";
	}

	private String buildSuccessBanner(String title, String message)
	{
		return "<div class=\"banner banner-success\">\n"
				+ "  <span class=\"banner-icon\">&#10003;</span>\n"
				+ "  <div><strong>" + esc(title) + "</strong><br>" + esc(message) + "</div>\n"
				+ "</div>\n";
	}

	private String buildStatCards(ReportData data)
	{
		int total = data.getTestMappings() != null ? data.getTestMappings().size() : 0;
		int selected = data.getSelectedTests() != null ? data.getSelectedTests().size() : 0;
		int added = 0;
		int changedClassCount = data.getChangedClasses() != null ? data.getChangedClasses().size() : 0;
		int changedMethodCount = data.getChangedMethods() != null ? data.getChangedMethods().size() : 0;

		if (data.isFullSuite())
		{
			selected = total;
		}
		else if (data.isClassLevelSelection())
		{
			added = countClassLevelAddedTests(data);
		}

		int willExecute = selected + added;
		int skipped = total - willExecute;
		double reductionPct = total > 0 ? (double) skipped / total * 100 : 0;

		String selectedLabel = added > 0
				? selected + " + " + added + " / " + total
				: selected + " / " + total;
		String selectedTitle = added > 0 ? "Tests Will Execute" : "Tests Selected";

		return "<section class=\"stats\">\n"
				+ statCard("selected", selectedLabel, selectedTitle, "green")
				+ statCard("reduction", String.format(java.util.Locale.US, "%.0f%%", reductionPct), "Reduction", "amber")
				+ statCard("classes", String.valueOf(changedClassCount), "Changed Classes", "blue")
				+ statCard("methods", String.valueOf(changedMethodCount), "Changed Methods", "purple")
				+ statCard("distance", String.valueOf(data.getCommitDistance()), "Commit Distance", "gray")
				+ statCard("branch", data.getBaseBranch() != null ? data.getBaseBranch() : "—", "Base Branch", "teal")
				+ "</section>\n";
	}

	private String statCard(String id, String value, String label, String color)
	{
		return "  <div class=\"stat-card accent-" + color + "\" id=\"card-" + id + "\">\n"
				+ "    <div class=\"stat-value\">" + esc(value) + "</div>\n"
				+ "    <div class=\"stat-label\">" + esc(label) + "</div>\n"
				+ "  </div>\n";
	}

	private String buildMetadataSection(ReportData data)
	{
		String baseBranch = data.getBaseBranch() != null ? data.getBaseBranch() : "—";
		String commitId = data.getCommitId() != null ? data.getCommitId() : "—";
		String shortCommit = commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
		String timestamp = data.getTimestamp() != null ? data.getTimestamp() : "—";
		String currentBranch = data.getCurrentBranch() != null ? data.getCurrentBranch() : "—";
		int commitDistance = data.getCommitDistance();

		return "<section class=\"section metadata-section\">\n"
				+ "  <h2>Coverage Map Info</h2>\n"
				+ "  <div class=\"metadata-grid\">\n"
				+ "    <div class=\"metadata-item\"><span class=\"metadata-label\">Base Branch</span>"
				+ "<span class=\"metadata-value\">" + esc(baseBranch) + "</span></div>\n"
				+ "    <div class=\"metadata-item\"><span class=\"metadata-label\">Coverage Map Commit</span>"
				+ "<span class=\"metadata-value mono\">" + esc(shortCommit) + "</span></div>\n"
				+ "    <div class=\"metadata-item\"><span class=\"metadata-label\">Map Generated</span>"
				+ "<span class=\"metadata-value\">" + esc(timestamp) + "</span></div>\n"
				+ "    <div class=\"metadata-item\"><span class=\"metadata-label\">Current Branch</span>"
				+ "<span class=\"metadata-value\">" + esc(currentBranch) + "</span></div>\n"
				+ "    <div class=\"metadata-item\"><span class=\"metadata-label\">Commit Distance</span>"
				+ "<span class=\"metadata-value\">" + commitDistance + "</span></div>\n"
				+ (data.isClassLevelSelection()
						? "    <div class=\"metadata-item\"><span class=\"metadata-label\">Selection Mode</span>"
						+ "<span class=\"metadata-value\">Class-level (entire test classes included)</span></div>\n"
						: "")
				+ "  </div>\n"
				+ "</section>\n";
	}

	private String buildChartsSection(ReportData data)
	{
		int total = data.getTestMappings() != null ? data.getTestMappings().size() : 0;
		int selected = data.getSelectedTests() != null ? data.getSelectedTests().size() : 0;
		int added = 0;

		if (data.isFullSuite())
		{
			selected = total;
		}
		else if (data.isClassLevelSelection())
		{
			added = countClassLevelAddedTests(data);
		}

		return "<section class=\"charts\">\n"
				+ "  <div class=\"chart-container\">\n"
				+ "    <h2>Selection Overview</h2>\n"
				+ buildDonutChart(selected, added, total)
				+ "  </div>\n"
				+ "  <div class=\"chart-container\">\n"
				+ "    <h2>Top Changed Classes by Coverage</h2>\n"
				+ buildBarChart(data)
				+ "  </div>\n"
				+ "</section>\n";
	}

	/**
	 * Counts test methods added by class-level expansion (in coverage map but not directly selected).
	 */
	private int countClassLevelAddedTests(ReportData data)
	{
		Set<String> selectedTests = data.getSelectedTests() != null ? data.getSelectedTests() : Set.of();
		Map<String, Map<String, List<String>>> mappings = data.getTestMappings();
		if (selectedTests.isEmpty() || mappings == null)
		{
			return 0;
		}

		Set<String> selectedClasses = new LinkedHashSet<>();
		for (String test : selectedTests)
		{
			int hash = test.indexOf('#');
			if (hash > 0)
			{
				selectedClasses.add(test.substring(0, hash));
			}
		}

		int added = 0;
		for (String testKey : mappings.keySet())
		{
			if (selectedTests.contains(testKey))
			{
				continue;
			}
			int hash = testKey.indexOf('#');
			if (hash > 0 && selectedClasses.contains(testKey.substring(0, hash)))
			{
				added++;
			}
		}
		return added;
	}

	private String buildDonutChart(int selected, int added, int total)
	{
		if (total == 0)
		{
			return "<p class=\"empty-note\">No test data available.</p>\n";
		}

		int skipped = total - selected - added;
		double r = 54;
		double circumference = 2 * Math.PI * r;
		double selectedDash = (double) selected / total * circumference;
		double addedDash = (double) added / total * circumference;

		StringBuilder sb = new StringBuilder();
		sb.append("<svg viewBox=\"0 0 140 140\" class=\"donut-chart\">\n");
		// Background circle (skipped - amber)
		sb.append("  <circle cx=\"70\" cy=\"70\" r=\"" + r + "\" fill=\"none\" stroke=\"#f39c12\" stroke-width=\"18\""
				+ " stroke-dasharray=\"" + fmt(circumference) + "\" stroke-dashoffset=\"0\""
				+ " transform=\"rotate(-90 70 70)\"/>\n");

		if (added > 0)
		{
			// Middle arc (selected + added - blue covers the first two segments)
			double selectedPlusAdded = selectedDash + addedDash;
			sb.append("  <circle cx=\"70\" cy=\"70\" r=\"" + r + "\" fill=\"none\" stroke=\"#3498db\" stroke-width=\"18\""
					+ " stroke-dasharray=\"" + fmt(selectedPlusAdded) + " " + fmt(circumference - selectedPlusAdded) + "\""
					+ " stroke-dashoffset=\"0\""
					+ " transform=\"rotate(-90 70 70)\"/>\n");
		}

		// Foreground arc (selected - green, drawn on top)
		sb.append("  <circle cx=\"70\" cy=\"70\" r=\"" + r + "\" fill=\"none\" stroke=\"#2ecc71\" stroke-width=\"18\""
				+ " stroke-dasharray=\"" + fmt(selectedDash) + " " + fmt(circumference - selectedDash) + "\""
				+ " stroke-dashoffset=\"0\""
				+ " transform=\"rotate(-90 70 70)\"/>\n");

		// Center text
		int centerNumber = selected + added;
		sb.append("  <text x=\"70\" y=\"66\" text-anchor=\"middle\" class=\"donut-number\">" + centerNumber + "</text>\n");
		sb.append("  <text x=\"70\" y=\"82\" text-anchor=\"middle\" class=\"donut-label\">of " + total + "</text>\n");
		sb.append("</svg>\n");

		// Legend
		sb.append("<div class=\"chart-legend\">\n");
		sb.append("  <span class=\"legend-item\"><span class=\"legend-dot\" style=\"background:#2ecc71\"></span> Selected (" + selected + ")</span>\n");
		if (added > 0)
		{
			sb.append("  <span class=\"legend-item\"><span class=\"legend-dot\" style=\"background:#3498db\"></span> Added by class (" + added + ")</span>\n");
		}
		sb.append("  <span class=\"legend-item\"><span class=\"legend-dot\" style=\"background:#f39c12\"></span> Skipped (" + skipped + ")</span>\n");
		sb.append("</div>\n");
		return sb.toString();
	}

	private String buildBarChart(ReportData data)
	{
		if (data.getTestMappings() == null || data.getTestMappings().isEmpty())
		{
			return "<p class=\"empty-note\">No coverage data available.</p>\n";
		}

		// Count how many tests cover each changed class
		Set<String> changedClasses = data.getChangedClasses() != null ? data.getChangedClasses() : Set.of();
		Set<String> changedMethods = data.getChangedMethods() != null ? data.getChangedMethods() : Set.of();

		// Collect all relevant classes (from changed classes + classes of changed methods)
		Set<String> relevantClasses = new LinkedHashSet<>(changedClasses);
		for (String method : changedMethods)
		{
			int hash = method.indexOf('#');
			if (hash > 0)
			{
				relevantClasses.add(method.substring(0, hash));
			}
		}

		if (relevantClasses.isEmpty())
		{
			return "<p class=\"empty-note\">No changed classes to display.</p>\n";
		}

		Map<String, Integer> classCoverageCounts = new TreeMap<>();
		for (String cls : relevantClasses)
		{
			int count = 0;
			for (Map.Entry<String, Map<String, List<String>>> entry : data.getTestMappings().entrySet())
			{
				List<String> coveredClasses = entry.getValue().get("classes");
				if (coveredClasses != null && coveredClasses.contains(cls))
				{
					count++;
				}
			}
			classCoverageCounts.put(cls, count);
		}

		// Sort by count descending, take top 10
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(classCoverageCounts.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		if (sorted.size() > 10)
		{
			sorted = sorted.subList(0, 10);
		}

		int maxCount = sorted.isEmpty() ? 1 : sorted.get(0).getValue();
		int barHeight = 22;
		int gap = 6;
		int labelWidth = 200;
		int chartWidth = 500;
		int svgHeight = sorted.size() * (barHeight + gap) + 10;

		StringBuilder svg = new StringBuilder();
		svg.append("<svg viewBox=\"0 0 " + (labelWidth + chartWidth + 40) + " " + svgHeight + "\" class=\"bar-chart\">\n");

		for (int i = 0; i < sorted.size(); i++)
		{
			Map.Entry<String, Integer> entry = sorted.get(i);
			String shortName = shortClassName(entry.getKey());
			int count = entry.getValue();
			double barWidth = maxCount > 0 ? (double) count / maxCount * chartWidth : 0;
			int y = i * (barHeight + gap) + 5;

			svg.append("  <text x=\"" + (labelWidth - 5) + "\" y=\"" + (y + barHeight / 2 + 4)
					+ "\" text-anchor=\"end\" class=\"bar-label\">"
					+ esc(shortName) + "</text>\n");
			svg.append("  <rect x=\"" + labelWidth + "\" y=\"" + y
					+ "\" width=\"" + fmt(barWidth) + "\" height=\"" + barHeight
					+ "\" rx=\"3\" class=\"bar-rect\"/>\n");
			svg.append("  <text x=\"" + fmt(labelWidth + barWidth + 5) + "\" y=\"" + (y + barHeight / 2 + 4)
					+ "\" class=\"bar-count\">" + count + "</text>\n");
		}

		svg.append("</svg>\n");
		svg.append("<div class=\"chart-legend\" style=\"margin-top:4px\">\n");
		svg.append("  <span class=\"legend-item\" style=\"font-size:0.85em;color:#888\">"
				+ "Number = test methods covering this class</span>\n");
		svg.append("</div>\n");
		return svg.toString();
	}

	private String buildChangedCodeSection(ReportData data)
	{
		Set<String> classes = data.getChangedClasses() != null ? data.getChangedClasses() : Set.of();
		Set<String> methods = data.getChangedMethods() != null ? data.getChangedMethods() : Set.of();

		if (classes.isEmpty() && methods.isEmpty())
		{
			return "<section class=\"section\">\n"
					+ "  <h2>Changed Code</h2>\n"
					+ "  <p class=\"empty-note\">No production code changes detected.</p>\n"
					+ "</section>\n";
		}

		Map<String, List<String>> classToMethods = new TreeMap<>();
		for (String cls : classes)
		{
			classToMethods.put(cls, new ArrayList<>());
		}
		for (String method : methods)
		{
			int hash = method.indexOf('#');
			if (hash > 0)
			{
				String cls = method.substring(0, hash);
				classToMethods.computeIfAbsent(cls, k -> new ArrayList<>()).add(method.substring(hash + 1));
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\">\n");
		sb.append("  <h2>Changed Code (" + classToMethods.size() + " classes, " + methods.size() + " methods)</h2>\n");

		Map<String, Double> covPct = data.getClassCoveragePercentages();
		Map<String, String> sourceLinks = data.getSourceLinks();

		sb.append("  <table class=\"data-table\">\n");
		sb.append("    <thead><tr><th>Class</th><th>Line Coverage</th></tr></thead>\n");
		sb.append("    <tbody>\n");
		for (Map.Entry<String, List<String>> entry : classToMethods.entrySet())
		{
			String cls = entry.getKey();
			List<String> clsMethods = entry.getValue();
			Collections.sort(clsMethods);

			String pkg = packageOf(cls);
			sb.append("    <tr><td><span class=\"pkg\">" + esc(pkg) + "</span><strong>"
					+ linkedClassName(cls, sourceLinks) + "</strong>\n");
			if (clsMethods.isEmpty())
			{
				sb.append("      <div class=\"changed-method no-info\">no method-level info</div>\n");
			}
			else
			{
				for (String m : clsMethods)
				{
					sb.append("      <div class=\"changed-method\">" + esc(m) + "</div>\n");
				}
			}
			sb.append("    </td>");
			sb.append("<td class=\"cov-cell\">" + coverageBar(cls, covPct) + "</td>");
			sb.append("</tr>\n");
		}
		sb.append("    </tbody>\n");
		sb.append("  </table>\n");

		sb.append("</section>\n");
		return sb.toString();
	}

	private String buildCoverageMatrix(ReportData data)
	{
		if (data.getTestMappings() == null || data.getTestMappings().isEmpty())
		{
			return "";
		}

		Set<String> selectedTests = data.getSelectedTests() != null ? data.getSelectedTests() : Set.of();

		// Only show selected tests that are in the coverage map
		List<String> testsToShow = new ArrayList<>();
		for (String test : selectedTests)
		{
			if (data.getTestMappings().containsKey(test))
			{
				testsToShow.add(test);
			}
		}
		Collections.sort(testsToShow);

		if (testsToShow.isEmpty())
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\" id=\"matrix-section\">\n");
		sb.append("  <h2>Coverage Matrix</h2>\n");
		sb.append(buildSearchBar("matrix"));
		sb.append("  <div class=\"expand-all-bar\">\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"expandAllMatrix()\">Expand All</button>\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"collapseAllMatrix()\">Collapse All</button>\n");
		sb.append("  </div>\n");
		sb.append(buildPagingControls("matrix", "top"));
		sb.append("  <div class=\"matrix-wrapper\">\n");
		sb.append("    <table class=\"matrix-table\">\n");

		// Header row — Test, Covered Classes, Covered Methods
		sb.append("      <thead><tr><th>Test</th><th>Covered Classes</th><th>Covered Methods</th></tr></thead>\n");

		// Data rows — only selected tests
		sb.append("      <tbody>\n");
		for (String test : testsToShow)
		{
			Map<String, List<String>> coverage = data.getTestMappings().get(test);
			List<String> coveredClasses = coverage != null ? coverage.get("classes") : List.of();
			List<String> coveredMethods = coverage != null ? coverage.get("methods") : List.of();
			if (coveredClasses == null) coveredClasses = List.of();
			if (coveredMethods == null) coveredMethods = List.of();

			// Build display strings
			Map<String, String> sourceLinks = data.getSourceLinks();
			List<String> sortedClasses = new ArrayList<>(coveredClasses);
			Collections.sort(sortedClasses);

			List<String> sortedMethods = new ArrayList<>(coveredMethods);
			Collections.sort(sortedMethods);

			// Build linked class names with coverage badges
			Map<String, Double> covPct = data.getClassCoveragePercentages();
			StringBuilder classesHtml = new StringBuilder();
			for (int i = 0; i < sortedClasses.size(); i++)
			{
				if (i > 0) classesHtml.append(", ");
				classesHtml.append(linkedClassName(sortedClasses.get(i), sourceLinks));
				classesHtml.append(coverageBadge(sortedClasses.get(i), covPct));
			}

			// Build method names with linked class prefix
			StringBuilder methodsHtml = new StringBuilder();
			for (int i = 0; i < sortedMethods.size(); i++)
			{
				if (i > 0) methodsHtml.append(", ");
				String method = sortedMethods.get(i);
				int hash = method.indexOf('#');
				if (hash > 0)
				{
					String classPart = method.substring(0, hash);
					String methodPart = method.substring(hash + 1);
					methodsHtml.append(linkedClassName(classPart, sourceLinks))
							.append("#").append(esc(methodPart));
				}
				else
				{
					methodsHtml.append(esc(method));
				}
			}

			boolean classesCollapsible = sortedClasses.size() > 3;
			boolean methodsCollapsible = sortedMethods.size() > 3;

			sb.append("        <tr class=\"matrix-row\""
					+ " data-search-test=\"" + esc(test) + "\""
					+ " data-search-classes=\"" + esc(searchData(coveredClasses)) + "\""
					+ " data-search-methods=\"" + esc(searchData(coveredMethods)) + "\">");
			sb.append("<td class=\"test-name\" title=\"" + esc(test) + "\">" + shortTestName(test) + "</td>");

			if (classesCollapsible)
			{
				sb.append("<td class=\"coverage-list\"><div class=\"matrix-cell-collapsed\">" + classesHtml
						+ "</div><span class=\"matrix-toggle\" onclick=\"toggleMatrixCell(this)\">show all ("
						+ sortedClasses.size() + ")</span></td>");
			}
			else
			{
				sb.append("<td class=\"coverage-list\">" + classesHtml + "</td>");
			}

			if (methodsCollapsible)
			{
				sb.append("<td class=\"coverage-list\"><div class=\"matrix-cell-collapsed\">" + methodsHtml
						+ "</div><span class=\"matrix-toggle\" onclick=\"toggleMatrixCell(this)\">show all ("
						+ sortedMethods.size() + ")</span></td>");
			}
			else
			{
				sb.append("<td class=\"coverage-list\">" + methodsHtml + "</td>");
			}

			sb.append("</tr>\n");
		}

		sb.append("      </tbody>\n");
		sb.append("    </table>\n");
		sb.append("  </div>\n");
		sb.append(buildPagingControls("matrix", "bottom"));
		sb.append("</section>\n");
		return sb.toString();
	}

	/**
	 * Builds a section showing which test methods will actually execute
	 * when class-level selection is enabled. Groups by test class.
	 */
	private String buildClassLevelExpansionSection(ReportData data)
	{
		Set<String> selectedTests = data.getSelectedTests() != null ? data.getSelectedTests() : Set.of();
		Map<String, Map<String, List<String>>> mappings = data.getTestMappings();

		if (selectedTests.isEmpty() || mappings == null || mappings.isEmpty())
		{
			return "";
		}

		// Extract class names from selected tests
		Set<String> selectedClasses = new LinkedHashSet<>();
		for (String test : selectedTests)
		{
			int hash = test.indexOf('#');
			if (hash > 0)
			{
				selectedClasses.add(test.substring(0, hash));
			}
		}

		if (selectedClasses.isEmpty())
		{
			return "";
		}

		// Find all methods in the coverage map belonging to those classes
		// Group: class -> list of methods (marking which are directly selected)
		Map<String, List<String>> classToAllMethods = new TreeMap<>();
		for (String testKey : mappings.keySet())
		{
			int hash = testKey.indexOf('#');
			if (hash > 0)
			{
				String cls = testKey.substring(0, hash);
				if (selectedClasses.contains(cls))
				{
					classToAllMethods.computeIfAbsent(cls, k -> new ArrayList<>()).add(testKey);
				}
			}
		}

		// Count totals
		int totalExpanded = 0;
		for (List<String> methods : classToAllMethods.values())
		{
			totalExpanded += methods.size();
		}

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\">\n");
		sb.append("  <h2>Class-Level Expansion</h2>\n");
		sb.append("  <div class=\"expansion-summary\">\n");
		sb.append("    <span class=\"expansion-selected\">" + selectedTests.size() + " methods selected</span>\n");
		sb.append("    <span class=\"expansion-arrow\">&rarr;</span>\n");
		sb.append("    <span class=\"expansion-total\">" + totalExpanded + " methods will execute</span>\n");
		sb.append("    <span class=\"expansion-classes\">(" + selectedClasses.size()
				+ " test class" + (selectedClasses.size() != 1 ? "es" : "") + ")</span>\n");
		sb.append("  </div>\n");

		// Per-class breakdown
		for (Map.Entry<String, List<String>> entry : classToAllMethods.entrySet())
		{
			String className = entry.getKey();
			List<String> allMethods = entry.getValue();
			Collections.sort(allMethods);

			sb.append("  <div class=\"expansion-class\">\n");
			sb.append("    <div class=\"expansion-class-header\">\n");
			sb.append("      <span class=\"expansion-class-name\">" + esc(shortClassName(className)) + "</span>\n");
			sb.append("      <span class=\"expansion-class-count\">" + allMethods.size() + " method"
					+ (allMethods.size() != 1 ? "s" : "") + "</span>\n");
			sb.append("    </div>\n");
			sb.append("    <div class=\"expansion-methods\">\n");

			for (String method : allMethods)
			{
				int hash = method.indexOf('#');
				String methodName = hash > 0 ? method.substring(hash + 1) : method;
				boolean directlySelected = selectedTests.contains(method);

				sb.append("      <span class=\"expansion-method"
						+ (directlySelected ? " expansion-method-selected" : " expansion-method-added")
						+ "\">" + esc(methodName));
				if (directlySelected)
				{
					sb.append("<span class=\"expansion-tag expansion-tag-selected\">selected</span>");
				}
				else
				{
					sb.append("<span class=\"expansion-tag expansion-tag-added\">added</span>");
				}
				sb.append("</span>\n");
			}

			sb.append("    </div>\n");
			sb.append("  </div>\n");
		}

		sb.append("</section>\n");
		return sb.toString();
	}

	private String buildUnmappedTestsSection(ReportData data)
	{
		Map<String, String> unmappedTests = data.getUnmappedTests() != null ? data.getUnmappedTests() : Map.of();

		if (unmappedTests.isEmpty())
		{
			return "";
		}

		List<String> sorted = new ArrayList<>(unmappedTests.keySet());
		Collections.sort(sorted);

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section unmapped-section\">\n");
		sb.append("  <h2>Tests Without Coverage Data</h2>\n");
		sb.append("  <div class=\"unmapped-info\">\n");
		sb.append("    <span class=\"banner-icon\">&#9888;</span>\n");
		sb.append("    <span>These test classes exist on disk but have no entry in the coverage map. "
				+ "They may be new tests, tests that were not instrumented during coverage collection, "
				+ "or classes that do not contain actual test methods and should be reviewed.</span>\n");
		sb.append("  </div>\n");
		sb.append("  <table class=\"data-table sortable-table\" id=\"unmapped-table\">\n");
		sb.append("    <thead><tr>"
				+ "<th class=\"sortable\" onclick=\"sortTable('unmapped-table',0)\">Package <span class=\"sort-arrow\"></span></th>"
				+ "<th class=\"sortable\" onclick=\"sortTable('unmapped-table',1)\">Class <span class=\"sort-arrow\"></span></th>"
				+ "<th class=\"sortable\" onclick=\"sortTable('unmapped-table',2)\">Reason <span class=\"sort-arrow\"></span></th>"
				+ "</tr></thead>\n");
		sb.append("    <tbody>\n");
		for (String fqn : sorted)
		{
			String pkg = packageOf(fqn);
			String name = shortClassName(fqn);
			String reason = unmappedTests.get(fqn);
			sb.append("    <tr><td class=\"pkg\">" + esc(pkg) + "</td>"
					+ "<td><strong>" + esc(name) + "</strong></td>"
					+ "<td class=\"reason\">" + esc(reason != null ? reason : "") + "</td></tr>\n");
		}
		sb.append("    </tbody>\n");
		sb.append("  </table>\n");
		sb.append("</section>\n");
		return sb.toString();
	}

	private String buildTestListSection(ReportData data)
	{
		if (data.getTestMappings() == null || data.getTestMappings().isEmpty())
		{
			return "";
		}

		Set<String> selectedTests = data.getSelectedTests() != null ? data.getSelectedTests() : Set.of();
		Set<String> changedClasses = data.getChangedClasses() != null ? data.getChangedClasses() : Set.of();
		Set<String> changedMethods = data.getChangedMethods() != null ? data.getChangedMethods() : Set.of();

		// Determine classes with method-level info (same logic as TestSelector)
		Set<String> classesWithMethodInfo = new LinkedHashSet<>();
		for (String method : changedMethods)
		{
			int hash = method.indexOf('#');
			if (hash > 0)
			{
				classesWithMethodInfo.add(method.substring(0, hash));
			}
		}

		// Only show selected tests
		List<String> displayTests = new ArrayList<>(selectedTests);
		displayTests.sort(String::compareTo);

		if (displayTests.isEmpty())
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\" id=\"test-details-section\">\n");
		sb.append("  <h2>Per-Test Selection Details</h2>\n");
		sb.append("  <p class=\"truncation-note\">" + displayTests.size() + " selected tests</p>\n");
		sb.append("  <table class=\"data-table sortable-table\" id=\"selection-details-table\">\n");
		sb.append("    <thead><tr>"
				+ "<th class=\"sortable\" style=\"width:65%\" onclick=\"sortTable('selection-details-table',0)\">Test <span class=\"sort-arrow\"></span></th>"
				+ "<th class=\"sortable\" style=\"width:35%\" onclick=\"sortTable('selection-details-table',1)\">Reason <span class=\"sort-arrow\"></span></th>"
				+ "</tr></thead>\n");
		sb.append("    <tbody>\n");

		for (String test : displayTests)
		{
			String reason = computeSelectionReason(test, data.getTestMappings().get(test),
					changedClasses, changedMethods, classesWithMethodInfo, true);

			sb.append("    <tr>");
			sb.append("<td class=\"test-name\" title=\"" + esc(test) + "\">" + shortTestName(test) + "</td>");
			sb.append("<td class=\"reason\">" + esc(reason) + "</td>");
			sb.append("</tr>\n");
		}

		sb.append("    </tbody>\n");
		sb.append("  </table>\n");
		sb.append("</section>\n");
		return sb.toString();
	}

	/**
	 * Computes a human-readable reason for why a test was selected or skipped.
	 */
	private String computeSelectionReason(String testName, Map<String, List<String>> coverage,
			Set<String> changedClasses, Set<String> changedMethods,
			Set<String> classesWithMethodInfo, boolean isSelected)
	{
		if (!isSelected)
		{
			return "No overlap with changed code";
		}

		if (coverage == null)
		{
			return "Selected (no coverage detail available)";
		}

		List<String> reasons = new ArrayList<>();

		// Check method-level matches
		List<String> coveredMethods = coverage.get("methods");
		if (coveredMethods != null)
		{
			for (String coveredMethod : coveredMethods)
			{
				if (changedMethods.contains(coveredMethod))
				{
					int hash = coveredMethod.lastIndexOf('#');
					String methodShort = hash > 0 ? coveredMethod.substring(hash + 1) : coveredMethod;
					reasons.add("covers changed method " + methodShort);
				}
			}
		}

		// Check class-level matches (only for classes without method info)
		List<String> coveredClasses = coverage.get("classes");
		if (coveredClasses != null)
		{
			for (String coveredClass : coveredClasses)
			{
				if (changedClasses.contains(coveredClass) && !classesWithMethodInfo.contains(coveredClass))
				{
					reasons.add("covers changed class " + shortClassName(coveredClass));
				}
			}
		}

		if (reasons.isEmpty())
		{
			return "Selected by coverage match";
		}

		return String.join("; ", reasons);
	}

	private static final int MAX_COVERAGE_MAP_ENTRIES = 200;

	private String buildFullCoverageMap(ReportData data)
	{
		if (data.getTestMappings() == null || data.getTestMappings().isEmpty())
		{
			return "";
		}

		if (data.getChunkCount() > 0)
		{
			return buildChunkedCoverageMap(data);
		}

		return buildInlineCoverageMap(data);
	}

	private String buildChunkedCoverageMap(ReportData data)
	{
		int totalTests = data.getTotalTestCount();
		int chunkCount = data.getChunkCount();

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\" id=\"coverage-map-section\">\n");
		sb.append("  <h2>Complete Test Coverage Map</h2>\n");
		sb.append("  <p class=\"truncation-note\">" + totalTests + " tests across " + chunkCount + " pages.</p>\n");
		sb.append(buildSearchBar("coverage-map", true));
		sb.append("  <div class=\"expand-all-bar\">\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"expandAllEntries()\">Expand All</button>\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"collapseAllEntries()\">Collapse All</button>\n");
		sb.append("  </div>\n");
		sb.append(buildPagingControls("coverage-map", "top"));
		sb.append("  <div id=\"coverage-map-entries\" data-total=\"" + chunkCount + "\"><div class=\"chunk-loading\">Loading...</div></div>\n");
		sb.append(buildPagingControls("coverage-map", "bottom"));
		sb.append("</section>\n");
		return sb.toString();
	}

	private String buildInlineCoverageMap(ReportData data)
	{
		Set<String> selectedTests = data.getSelectedTests() != null ? data.getSelectedTests() : Set.of();

		List<String> allTests = new ArrayList<>(data.getTestMappings().keySet());
		Collections.sort(allTests);

		boolean truncated = allTests.size() > MAX_COVERAGE_MAP_ENTRIES;
		List<String> displayTests = truncated ? allTests.subList(0, MAX_COVERAGE_MAP_ENTRIES) : allTests;

		StringBuilder sb = new StringBuilder();
		sb.append("<section class=\"section\" id=\"coverage-map-section\">\n");
		sb.append("  <h2>Complete Test Coverage Map</h2>\n");
		if (truncated)
		{
			sb.append("  <p class=\"truncation-note\">Showing first " + MAX_COVERAGE_MAP_ENTRIES
					+ " of " + allTests.size() + " tests. Use the <code>query</code> command for full exploration.</p>\n");
		}
		sb.append(buildSearchBar("coverage-map", true));
		sb.append("  <div class=\"expand-all-bar\">\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"expandAllEntries()\">Expand All</button>\n");
		sb.append("    <button class=\"expand-all-btn\" onclick=\"collapseAllEntries()\">Collapse All</button>\n");
		sb.append("  </div>\n");
		sb.append(buildPagingControls("coverage-map", "top"));

		for (String test : displayTests)
		{
			Map<String, List<String>> coverage = data.getTestMappings().get(test);
			List<String> classes = coverage != null ? coverage.get("classes") : List.of();
			List<String> methods = coverage != null ? coverage.get("methods") : List.of();
			if (classes == null) classes = List.of();
			if (methods == null) methods = List.of();

			boolean isSelected = selectedTests.contains(test);

			sb.append(buildCoverageMapEntry(test, isSelected, classes, methods, data.getSourceLinks()));
		}

		sb.append(buildPagingControls("coverage-map", "bottom"));
		sb.append("</section>\n");
		return sb.toString();
	}

	private String buildCoverageMapEntry(String test, boolean isSelected,
			List<String> classes, List<String> methods, Map<String, String> sourceLinks)
	{
		String badge = isSelected ? "<span class=\"badge badge-selected\">SELECTED</span>"
				: "<span class=\"badge badge-skipped\">SKIPPED</span>";

		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"coverage-map-entry\""
				+ " data-search-test=\"" + esc(test) + "\""
				+ " data-selected=\"" + (isSelected ? "1" : "0") + "\""
				+ " data-search-classes=\"" + esc(searchData(classes)) + "\""
				+ " data-search-methods=\"" + esc(searchData(methods)) + "\">\n");
		sb.append("    <div class=\"coverage-map-header\" onclick=\"toggleEntry(this)\">\n");
		sb.append("      <span class=\"toggle-btn\">" + "&#9654;" + "</span>\n");
		sb.append("      <span class=\"coverage-map-test\">" + esc(test) + "</span>\n");
		sb.append("      " + badge + "\n");
		sb.append("      <span class=\"entry-counts\">" + classes.size() + " classes, " + methods.size() + " methods</span>\n");
		sb.append("    </div>\n");
		sb.append("    <div class=\"coverage-map-body\" style=\"display:none\">\n");

		if (!classes.isEmpty())
		{
			List<String> sortedClasses = new ArrayList<>(classes);
			Collections.sort(sortedClasses);
			sb.append("      <div class=\"coverage-map-detail\"><strong>Classes:</strong> ");
			for (int i = 0; i < sortedClasses.size(); i++)
			{
				if (i > 0) sb.append(", ");
				sb.append("<span class=\"coverage-map-class\">" + linkedClassName(sortedClasses.get(i), sourceLinks) + "</span>");
			}
			sb.append("</div>\n");
		}

		if (!methods.isEmpty())
		{
			List<String> sortedMethods = new ArrayList<>(methods);
			Collections.sort(sortedMethods);
			sb.append("      <div class=\"coverage-map-detail\"><strong>Methods:</strong> ");
			for (int i = 0; i < sortedMethods.size(); i++)
			{
				if (i > 0) sb.append(", ");
				String method = sortedMethods.get(i);
				int hash = method.indexOf('#');
				String display = hash > 0 ? shortClassName(method.substring(0, hash)) + "#" + method.substring(hash + 1) : method;
				sb.append("<span class=\"coverage-map-method\">" + esc(display) + "</span>");
			}
			sb.append("</div>\n");
		}

		sb.append("    </div>\n");
		sb.append("  </div>\n");
		return sb.toString();
	}

	private String buildFooter()
	{
		return "<footer class=\"footer\">\n"
				+ "  <p>Generated by Smart Test Picker &mdash; Lightweight Regression Test Selection via Per-Test Runtime Coverage</p>\n"
				+ "</footer>\n";
	}

	private String buildScript()
	{
		StringBuilder js = new StringBuilder();
		js.append("<script>\n");

		// Toggle expand/collapse for a single coverage map entry
		js.append("function toggleEntry(header) {\n");
		js.append("  var body = header.nextElementSibling;\n");
		js.append("  var btn = header.querySelector('.toggle-btn');\n");
		js.append("  if (body.style.display === 'none') {\n");
		js.append("    body.style.display = ''; btn.classList.add('expanded');\n");
		js.append("  } else {\n");
		js.append("    body.style.display = 'none'; btn.classList.remove('expanded');\n");
		js.append("  }\n");
		js.append("}\n");

		// Expand/Collapse all entries
		js.append("function expandAllEntries() {\n");
		js.append("  var entries = document.querySelectorAll('#coverage-map-section .coverage-map-body');\n");
		js.append("  for (var i = 0; i < entries.length; i++) {\n");
		js.append("    entries[i].style.display = '';\n");
		js.append("    var btn = entries[i].previousElementSibling.querySelector('.toggle-btn');\n");
		js.append("    if (btn) btn.classList.add('expanded');\n");
		js.append("  }\n");
		js.append("}\n");
		js.append("function collapseAllEntries() {\n");
		js.append("  var entries = document.querySelectorAll('#coverage-map-section .coverage-map-body');\n");
		js.append("  for (var i = 0; i < entries.length; i++) {\n");
		js.append("    entries[i].style.display = 'none';\n");
		js.append("    var btn = entries[i].previousElementSibling.querySelector('.toggle-btn');\n");
		js.append("    if (btn) btn.classList.remove('expanded');\n");
		js.append("  }\n");
		js.append("}\n\n");

		// Toggle a single matrix cell collapsed/expanded
		js.append("function toggleMatrixCell(toggle) {\n");
		js.append("  var cell = toggle.previousElementSibling;\n");
		js.append("  if (cell.classList.contains('matrix-cell-expanded')) {\n");
		js.append("    cell.classList.remove('matrix-cell-expanded');\n");
		js.append("    toggle.textContent = 'show all (' + cell.children.length + ')';\n");
		js.append("  } else {\n");
		js.append("    cell.classList.add('matrix-cell-expanded');\n");
		js.append("    toggle.textContent = 'collapse';\n");
		js.append("  }\n");
		js.append("}\n\n");

		// Expand/Collapse all matrix cells
		js.append("function expandAllMatrix() {\n");
		js.append("  var cells = document.querySelectorAll('#matrix-section .matrix-cell-collapsed');\n");
		js.append("  for (var i = 0; i < cells.length; i++) cells[i].classList.add('matrix-cell-expanded');\n");
		js.append("  var toggles = document.querySelectorAll('#matrix-section .matrix-toggle');\n");
		js.append("  for (var i = 0; i < toggles.length; i++) toggles[i].textContent = 'collapse';\n");
		js.append("}\n");
		js.append("function collapseAllMatrix() {\n");
		js.append("  var cells = document.querySelectorAll('#matrix-section .matrix-cell-collapsed');\n");
		js.append("  for (var i = 0; i < cells.length; i++) cells[i].classList.remove('matrix-cell-expanded');\n");
		js.append("  var toggles = document.querySelectorAll('#matrix-section .matrix-toggle');\n");
		js.append("  for (var i = 0; i < toggles.length; i++) toggles[i].textContent = 'show all';\n");
		js.append("}\n\n");

		// Generic table column sort
		js.append("var _sortState = {};\n");
		js.append("function sortTable(tableId, colIdx) {\n");
		js.append("  var table = document.getElementById(tableId);\n");
		js.append("  if (!table) return;\n");
		js.append("  var tbody = table.querySelector('tbody');\n");
		js.append("  var rows = Array.from(tbody.querySelectorAll('tr'));\n");
		js.append("  var key = tableId + '_' + colIdx;\n");
		js.append("  var asc = _sortState[key] === undefined ? true : !_sortState[key];\n");
		js.append("  _sortState[key] = asc;\n");
		js.append("  rows.sort(function(a, b) {\n");
		js.append("    var va = (a.cells[colIdx] || {}).textContent || '';\n");
		js.append("    var vb = (b.cells[colIdx] || {}).textContent || '';\n");
		js.append("    return asc ? va.localeCompare(vb) : vb.localeCompare(va);\n");
		js.append("  });\n");
		js.append("  for (var i = 0; i < rows.length; i++) tbody.appendChild(rows[i]);\n");
		js.append("  var ths = table.querySelectorAll('thead th');\n");
		js.append("  for (var i = 0; i < ths.length; i++) {\n");
		js.append("    var arrow = ths[i].querySelector('.sort-arrow');\n");
		js.append("    if (arrow) arrow.textContent = (i === colIdx) ? (asc ? ' \\u25B2' : ' \\u25BC') : '';\n");
		js.append("  }\n");
		js.append("}\n\n");

		// Tab switching
		js.append("function switchTab(tabId) {\n");
		js.append("  var tabs = document.querySelectorAll('.tab-content');\n");
		js.append("  for (var i = 0; i < tabs.length; i++) tabs[i].classList.remove('active');\n");
		js.append("  var items = document.querySelectorAll('.tab-item');\n");
		js.append("  for (var i = 0; i < items.length; i++) items[i].classList.remove('active');\n");
		js.append("  document.getElementById(tabId).classList.add('active');\n");
		js.append("  var clicked = document.querySelector('.tab-item[onclick*=\"' + tabId + '\"]');\n");
		js.append("  if (clicked) clicked.classList.add('active');\n");
		js.append("}\n\n");

		// Metrics filter
		js.append("function filterMetrics(filter) {\n");
		js.append("  var btns = document.querySelectorAll('.filter-btn');\n");
		js.append("  for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');\n");
		js.append("  event.target.classList.add('active');\n");
		js.append("  var rows = document.querySelectorAll('#metrics-table-body tr');\n");
		js.append("  for (var i = 0; i < rows.length; i++) {\n");
		js.append("    var pct = parseFloat(rows[i].getAttribute('data-line-pct'));\n");
		js.append("    var show = false;\n");
		js.append("    if (filter === 'all') show = true;\n");
		js.append("    else if (filter === 'uncovered') show = (pct === 0);\n");
		js.append("    else if (filter === 'poor') show = (pct < 50);\n");
		js.append("    else if (filter === 'good') show = (pct >= 75);\n");
		js.append("    rows[i].style.display = show ? '' : 'none';\n");
		js.append("  }\n");
		js.append("}\n\n");

		// Metrics sort
		js.append("var metricsSortCol = 'line';\n");
		js.append("var metricsSortAsc = true;\n");
		js.append("function sortMetrics(column) {\n");
		js.append("  if (metricsSortCol === column) { metricsSortAsc = !metricsSortAsc; }\n");
		js.append("  else { metricsSortCol = column; metricsSortAsc = (column === 'name'); }\n");
		js.append("  var tbody = document.getElementById('metrics-table-body');\n");
		js.append("  if (!tbody) return;\n");
		js.append("  var rows = Array.from(tbody.querySelectorAll('tr'));\n");
		js.append("  rows.sort(function(a, b) {\n");
		js.append("    var va, vb;\n");
		js.append("    if (column === 'name') { va = a.getAttribute('data-class'); vb = b.getAttribute('data-class'); return metricsSortAsc ? va.localeCompare(vb) : vb.localeCompare(va); }\n");
		js.append("    if (column === 'line') { va = parseFloat(a.getAttribute('data-line-pct')); vb = parseFloat(b.getAttribute('data-line-pct')); }\n");
		js.append("    else { va = parseFloat(a.getAttribute('data-branch-pct')); vb = parseFloat(b.getAttribute('data-branch-pct')); }\n");
		js.append("    return metricsSortAsc ? va - vb : vb - va;\n");
		js.append("  });\n");
		js.append("  for (var i = 0; i < rows.length; i++) tbody.appendChild(rows[i]);\n");
		js.append("  var headers = document.querySelectorAll('.metrics-table th.sortable');\n");
		js.append("  for (var i = 0; i < headers.length; i++) {\n");
		js.append("    var arrow = headers[i].querySelector('.sort-arrow');\n");
		js.append("    if (arrow) arrow.textContent = (headers[i].getAttribute('data-sort') === column) ? (metricsSortAsc ? ' \\u25B2' : ' \\u25BC') : '';\n");
		js.append("  }\n");
		js.append("}\n\n");

		// Unified init for sections with static DOM items (matrix, test-details)
		js.append("function initSection(prefix, itemSelector) {\n");
		js.append("  var items = Array.from(document.querySelectorAll(itemSelector));\n");
		js.append("  if (items.length === 0) return;\n");
		js.append("  var searchInput = document.getElementById(prefix + '-search');\n");
		js.append("  var countEl = document.getElementById(prefix + '-count');\n");
		js.append("  var sizeSelect = document.getElementById(prefix + '-page-size');\n");
		js.append("  var prevBtn = document.getElementById(prefix + '-prev');\n");
		js.append("  var nextBtn = document.getElementById(prefix + '-next');\n");
		js.append("  var infoEl = document.getElementById(prefix + '-page-info');\n");
		js.append("  var prevBtnB = document.getElementById(prefix + '-prev-bottom');\n");
		js.append("  var nextBtnB = document.getElementById(prefix + '-next-bottom');\n");
		js.append("  var infoBEl = document.getElementById(prefix + '-page-info-bottom');\n");
		js.append("  var cbTests = document.getElementById(prefix + '-filter-tests');\n");
		js.append("  var cbClasses = document.getElementById(prefix + '-filter-classes');\n");
		js.append("  var cbMethods = document.getElementById(prefix + '-filter-methods');\n");
		js.append("  var cbSelected = document.getElementById(prefix + '-filter-selected');\n");
		js.append("  var state = { page: 0, pageSize: sizeSelect ? parseInt(sizeSelect.value) : 50, visibleItems: items.slice() };\n");
		js.append("  function render() {\n");
		js.append("    var vis = state.visibleItems;\n");
		js.append("    var totalPages = Math.max(1, Math.ceil(vis.length / state.pageSize));\n");
		js.append("    if (state.page >= totalPages) state.page = totalPages - 1;\n");
		js.append("    if (state.page < 0) state.page = 0;\n");
		js.append("    var start = state.page * state.pageSize, end = start + state.pageSize;\n");
		js.append("    for (var i = 0; i < items.length; i++) items[i].style.display = 'none';\n");
		js.append("    for (var i = 0; i < vis.length; i++) vis[i].style.display = (i >= start && i < end) ? '' : 'none';\n");
		js.append("    var label = 'Page ' + (state.page + 1) + ' of ' + totalPages;\n");
		js.append("    if (infoEl) infoEl.textContent = label;\n");
		js.append("    if (infoBEl) infoBEl.textContent = label;\n");
		js.append("    if (prevBtn) prevBtn.disabled = state.page <= 0;\n");
		js.append("    if (nextBtn) nextBtn.disabled = state.page >= totalPages - 1;\n");
		js.append("    if (prevBtnB) prevBtnB.disabled = state.page <= 0;\n");
		js.append("    if (nextBtnB) nextBtnB.disabled = state.page >= totalPages - 1;\n");
		js.append("    if (countEl) countEl.textContent = 'Showing ' + vis.length + ' of ' + items.length + ' tests';\n");
		js.append("  }\n");
		js.append("  function doSearch() {\n");
		js.append("    var q = searchInput ? searchInput.value.toLowerCase() : '';\n");
		js.append("    var inTests = cbTests ? cbTests.checked : true;\n");
		js.append("    var inClasses = cbClasses ? cbClasses.checked : true;\n");
		js.append("    var inMethods = cbMethods ? cbMethods.checked : true;\n");
		js.append("    var selectedOnly = cbSelected ? cbSelected.checked : false;\n");
		js.append("    if (!inTests && !inClasses && !inMethods) { inTests = inClasses = inMethods = true; }\n");
		js.append("    state.visibleItems = items.filter(function(row) {\n");
		js.append("      if (selectedOnly && row.getAttribute('data-selected') !== '1') return false;\n");
		js.append("      if (!q) return true;\n");
		js.append("      if (inTests && (row.getAttribute('data-search-test') || '').toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("      if (inClasses && (row.getAttribute('data-search-classes') || '').toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("      if (inMethods && (row.getAttribute('data-search-methods') || '').toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("      return false;\n");
		js.append("    });\n");
		js.append("    state.page = 0; render();\n");
		js.append("  }\n");
		js.append("  function goPrev() { state.page--; render(); }\n");
		js.append("  function goNext() { state.page++; render(); }\n");
		js.append("  if (prevBtn) prevBtn.addEventListener('click', goPrev);\n");
		js.append("  if (nextBtn) nextBtn.addEventListener('click', goNext);\n");
		js.append("  if (prevBtnB) prevBtnB.addEventListener('click', goPrev);\n");
		js.append("  if (nextBtnB) nextBtnB.addEventListener('click', goNext);\n");
		js.append("  if (sizeSelect) sizeSelect.addEventListener('change', function() {\n");
		js.append("    state.pageSize = parseInt(this.value); state.page = 0; render();\n");
		js.append("  });\n");
		js.append("  if (searchInput) searchInput.addEventListener('input', doSearch);\n");
		js.append("  if (cbTests) cbTests.addEventListener('change', doSearch);\n");
		js.append("  if (cbClasses) cbClasses.addEventListener('change', doSearch);\n");
		js.append("  if (cbMethods) cbMethods.addEventListener('change', doSearch);\n");
		js.append("  if (cbSelected) cbSelected.addEventListener('change', doSearch);\n");
		js.append("  render();\n");
		js.append("}\n\n");

		// Initialize static sections
		js.append("initSection('matrix', '.matrix-row');\n\n");

		// Chunk-based coverage map section (or inline fallback)
		js.append("(function() {\n");
		js.append("  var container = document.getElementById('coverage-map-entries');\n");
		js.append("  if (!container) {\n");
		// Inline mode — init with static items
		js.append("    initSection('coverage-map', '.coverage-map-entry');\n");
		js.append("    return;\n");
		js.append("  }\n\n");

		// Chunk mode — dynamic loading
		js.append("  var searchInput = document.getElementById('coverage-map-search');\n");
		js.append("  var countEl = document.getElementById('coverage-map-count');\n");
		js.append("  var sizeSelect = document.getElementById('coverage-map-page-size');\n");
		js.append("  var prevBtn = document.getElementById('coverage-map-prev');\n");
		js.append("  var nextBtn = document.getElementById('coverage-map-next');\n");
		js.append("  var infoEl = document.getElementById('coverage-map-page-info');\n");
		js.append("  var prevBtnB = document.getElementById('coverage-map-prev-bottom');\n");
		js.append("  var nextBtnB = document.getElementById('coverage-map-next-bottom');\n");
		js.append("  var infoBEl = document.getElementById('coverage-map-page-info-bottom');\n");
		js.append("  var cbTests = document.getElementById('coverage-map-filter-tests');\n");
		js.append("  var cbClasses = document.getElementById('coverage-map-filter-classes');\n");
		js.append("  var cbMethods = document.getElementById('coverage-map-filter-methods');\n");
		js.append("  var cbSelected = document.getElementById('coverage-map-filter-selected');\n\n");

		js.append("  var chunkPageSize = sizeSelect ? parseInt(sizeSelect.value) : 50;\n");
		js.append("  var currentChunk = -1;\n");
		js.append("  var searchMode = false;\n");
		js.append("  var searchResults = [];\n");
		js.append("  var searchPage = 0;\n");
		js.append("  var searchIndexLoaded = false;\n");
		js.append("  var totalChunks = Math.ceil((container.dataset.total || 0) / 50);\n\n");

		// Compute totalChunks from data attribute set in HTML
		// We'll set it from chunk count passed via the HTML

		// Dynamic script loader
		js.append("  function loadScript(src, cb) {\n");
		js.append("    var s = document.createElement('script');\n");
		js.append("    s.src = src;\n");
		js.append("    s.onload = function() { cb(true); };\n");
		js.append("    s.onerror = function() { cb(false); };\n");
		js.append("    document.head.appendChild(s);\n");
		js.append("  }\n\n");

		// Load a chunk by index
		js.append("  function loadChunk(idx, cb) {\n");
		js.append("    if (window.__STP_CHUNKS && window.__STP_CHUNKS[idx] !== undefined) { cb(window.__STP_CHUNKS[idx]); return; }\n");
		js.append("    loadScript('data/chunk-' + idx + '.js', function(ok) {\n");
		js.append("      cb(ok && window.__STP_CHUNKS ? window.__STP_CHUNKS[idx] : null);\n");
		js.append("    });\n");
		js.append("  }\n\n");

		// Load search index
		js.append("  function loadSearchIndex(cb) {\n");
		js.append("    if (window.__STP_SEARCH_INDEX) { cb(window.__STP_SEARCH_INDEX); return; }\n");
		js.append("    loadScript('data/search-index.js', function(ok) {\n");
		js.append("      cb(ok ? window.__STP_SEARCH_INDEX : []);\n");
		js.append("    });\n");
		js.append("  }\n\n");

		// Escape HTML
		js.append("  function esc(s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }\n\n");

		// Short class name
		js.append("  function shortClass(fqn) { var i = fqn.lastIndexOf('.'); return i >= 0 ? fqn.substring(i+1) : fqn; }\n\n");

	js.append("  function shortTest(t) { var h = t.indexOf('#'); if (h < 0) return esc(t); var cp = t.substring(0,h); var mp = t.substring(h+1); var d = cp.lastIndexOf('.'); var pkg = d >= 0 ? cp.substring(0,d) : ''; var sc = d >= 0 ? cp.substring(d+1) : cp; return '<span class=\"test-pkg\">' + esc(pkg) + '</span><span class=\"test-class\">' + esc(sc) + '</span><span class=\"test-method\">#' + esc(mp) + '</span>'; }\n\n");

		// Render entries from chunk data into the container
		js.append("  function renderEntries(entries) {\n");
		js.append("    var html = '';\n");
		js.append("    for (var i = 0; i < entries.length; i++) {\n");
		js.append("      var e = entries[i];\n");
		js.append("      var badge = e.s ? '<span class=\"badge badge-selected\">SELECTED</span>' : '<span class=\"badge badge-skip' + 'ped\">SKIP' + 'PED</span>';\n");
		js.append("      var cls = e.classes || [], mth = e.methods || [];\n");
		js.append("      var totalCls = e.cc || cls.length, totalMth = e.mc || mth.length;\n");
		js.append("      html += '<div class=\"coverage-map-entry\">';\n");
		js.append("      html += '<div class=\"coverage-map-header\" onclick=\"toggleEntry(this)\">';\n");
		js.append("      html += '<span class=\"toggle-btn\">&#9654;</span>';\n");
		js.append("      html += '<span class=\"coverage-map-test\">' + shortTest(e.t) + '</span> ' + badge;\n");
		js.append("      html += '<span class=\"entry-counts\">' + totalCls + ' classes, ' + totalMth + ' methods</span>';\n");
		js.append("      html += '</div>';\n");
		js.append("      html += '<div class=\"coverage-map-body\" style=\"display:none\">';\n");
		// Classes
		js.append("      if (cls.length > 0) {\n");
		js.append("        var sc = cls.slice().sort();\n");
		js.append("        html += '<div class=\"coverage-map-detail\"><strong>Classes:</strong> ';\n");
		js.append("        for (var j = 0; j < sc.length; j++) {\n");
		js.append("          if (j > 0) html += ', ';\n");
		js.append("          var sn = shortClass(sc[j]);\n");
		js.append("          if (e.sl && e.sl[sc[j]]) {\n");
		js.append("            html += '<span class=\"coverage-map-class\"><a href=\"' + esc(e.sl[sc[j]]) + '\" class=\"source-link\" target=\"_blank\">' + esc(sn) + '</a></span>';\n");
		js.append("          } else {\n");
		js.append("            html += '<span class=\"coverage-map-class\">' + esc(sn) + '</span>';\n");
		js.append("          }\n");
		js.append("        }\n");
		js.append("        html += '</div>';\n");
		js.append("        if (totalCls > cls.length) html += '<div class=\"coverage-map-detail\" style=\"color:#95a5a6;font-style:italic\">... and ' + (totalCls - cls.length) + ' more classes</div>';\n");
		js.append("      }\n");
		// Methods
		js.append("      if (mth.length > 0) {\n");
		js.append("        var sm = mth.slice().sort();\n");
		js.append("        html += '<div class=\"coverage-map-detail\"><strong>Methods:</strong> ';\n");
		js.append("        for (var j = 0; j < sm.length; j++) {\n");
		js.append("          if (j > 0) html += ', ';\n");
		js.append("          var h = sm[j].indexOf('#');\n");
		js.append("          var disp = h > 0 ? shortClass(sm[j].substring(0,h)) + '#' + sm[j].substring(h+1) : sm[j];\n");
		js.append("          html += '<span class=\"coverage-map-method\">' + esc(disp) + '</span>';\n");
		js.append("        }\n");
		js.append("        html += '</div>';\n");
		js.append("        if (totalMth > mth.length) html += '<div class=\"coverage-map-detail\" style=\"color:#95a5a6;font-style:italic\">... and ' + (totalMth - mth.length) + ' more methods</div>';\n");
		js.append("      }\n");
		js.append("      html += '</div></div>';\n");
		js.append("    }\n");
		js.append("    container.innerHTML = html;\n");
		js.append("  }\n\n");

		// Update paging controls
		js.append("  function updatePaging(page, totalPages, totalItems) {\n");
		js.append("    var label = 'Page ' + (page + 1) + ' of ' + totalPages;\n");
		js.append("    if (infoEl) infoEl.textContent = label;\n");
		js.append("    if (infoBEl) infoBEl.textContent = label;\n");
		js.append("    if (prevBtn) prevBtn.disabled = page <= 0;\n");
		js.append("    if (nextBtn) nextBtn.disabled = page >= totalPages - 1;\n");
		js.append("    if (prevBtnB) prevBtnB.disabled = page <= 0;\n");
		js.append("    if (nextBtnB) nextBtnB.disabled = page >= totalPages - 1;\n");
		js.append("    if (countEl) countEl.textContent = 'Showing page ' + (page + 1) + ' of ' + totalItems + ' tests';\n");
		js.append("  }\n\n");

		// Show a chunk page (non-search mode)
		js.append("  function showChunkPage(chunkIdx) {\n");
		js.append("    container.innerHTML = '<div class=\"chunk-loading\">Loading...</div>';\n");
		js.append("    loadChunk(chunkIdx, function(data) {\n");
		js.append("      if (!data) { container.innerHTML = '<div class=\"chunk-loading\">Failed to load data.</div>'; return; }\n");
		js.append("      currentChunk = chunkIdx;\n");
		js.append("      renderEntries(data);\n");
		js.append("      updatePaging(chunkIdx, totalChunks, totalChunks * 50);\n");
		js.append("    });\n");
		js.append("  }\n\n");

		// Search mode: show a page of filtered results
		js.append("  function showSearchPage(page) {\n");
		js.append("    searchPage = page;\n");
		js.append("    var pageSize = chunkPageSize;\n");
		js.append("    var start = page * pageSize, end = Math.min(start + pageSize, searchResults.length);\n");
		js.append("    var totalPages = Math.max(1, Math.ceil(searchResults.length / pageSize));\n");
		// Collect which chunks we need
		js.append("    var neededChunks = {};\n");
		js.append("    for (var i = start; i < end; i++) neededChunks[searchResults[i].k] = true;\n");
		js.append("    var chunkIds = Object.keys(neededChunks).map(Number);\n");
		js.append("    var loaded = 0;\n");
		js.append("    if (chunkIds.length === 0) {\n");
		js.append("      container.innerHTML = '<div class=\"chunk-loading\">No results found.</div>';\n");
		js.append("      updatePaging(0, 1, 0); return;\n");
		js.append("    }\n");
		js.append("    container.innerHTML = '<div class=\"chunk-loading\">Loading...</div>';\n");
		js.append("    for (var c = 0; c < chunkIds.length; c++) {\n");
		js.append("      loadChunk(chunkIds[c], function() {\n");
		js.append("        loaded++;\n");
		js.append("        if (loaded === chunkIds.length) {\n");
		// All needed chunks loaded — build entries for this page
		js.append("          var entries = [];\n");
		js.append("          for (var i = start; i < end; i++) {\n");
		js.append("            var sr = searchResults[i];\n");
		js.append("            var chunk = window.__STP_CHUNKS[sr.k];\n");
		js.append("            if (chunk) {\n");
		js.append("              for (var j = 0; j < chunk.length; j++) {\n");
		js.append("                if (chunk[j].t === sr.t) { entries.push(chunk[j]); break; }\n");
		js.append("              }\n");
		js.append("            }\n");
		js.append("          }\n");
		js.append("          renderEntries(entries);\n");
		js.append("          updatePaging(page, totalPages, searchResults.length);\n");
		js.append("        }\n");
		js.append("      });\n");
		js.append("    }\n");
		js.append("  }\n\n");

		// Search handler
		js.append("  function doChunkSearch() {\n");
		js.append("    var q = searchInput ? searchInput.value.toLowerCase().trim() : '';\n");
		js.append("    var selectedOnly = cbSelected ? cbSelected.checked : false;\n");
		js.append("    if (!q && !selectedOnly) {\n");
		js.append("      searchMode = false;\n");
		js.append("      showChunkPage(currentChunk >= 0 ? currentChunk : 0);\n");
		js.append("      return;\n");
		js.append("    }\n");
		js.append("    var inTests = cbTests ? cbTests.checked : true;\n");
		js.append("    var inClasses = cbClasses ? cbClasses.checked : true;\n");
		js.append("    var inMethods = cbMethods ? cbMethods.checked : true;\n");
		js.append("    if (!inTests && !inClasses && !inMethods) { inTests = inClasses = inMethods = true; }\n");
		js.append("    container.innerHTML = '<div class=\"chunk-loading\">Searching...</div>';\n");
		js.append("    loadSearchIndex(function(index) {\n");
		js.append("      searchResults = index.filter(function(e) {\n");
		js.append("        if (selectedOnly && !e.s) return false;\n");
		js.append("        if (!q) return true;\n");
		js.append("        if (inTests && e.t.toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("        if (inClasses && e.c.toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("        if (inMethods && e.m.toLowerCase().indexOf(q) >= 0) return true;\n");
		js.append("        return false;\n");
		js.append("      });\n");
		js.append("      searchMode = true;\n");
		js.append("      showSearchPage(0);\n");
		js.append("    });\n");
		js.append("  }\n\n");

		// Paging event handlers
		js.append("  function goPrev() {\n");
		js.append("    if (searchMode) { if (searchPage > 0) showSearchPage(searchPage - 1); }\n");
		js.append("    else { if (currentChunk > 0) showChunkPage(currentChunk - 1); }\n");
		js.append("  }\n");
		js.append("  function goNext() {\n");
		js.append("    if (searchMode) {\n");
		js.append("      var totalPages = Math.ceil(searchResults.length / chunkPageSize);\n");
		js.append("      if (searchPage < totalPages - 1) showSearchPage(searchPage + 1);\n");
		js.append("    } else {\n");
		js.append("      if (currentChunk < totalChunks - 1) showChunkPage(currentChunk + 1);\n");
		js.append("    }\n");
		js.append("  }\n\n");

		// Wire up events
		js.append("  if (prevBtn) prevBtn.addEventListener('click', goPrev);\n");
		js.append("  if (nextBtn) nextBtn.addEventListener('click', goNext);\n");
		js.append("  if (prevBtnB) prevBtnB.addEventListener('click', goPrev);\n");
		js.append("  if (nextBtnB) nextBtnB.addEventListener('click', goNext);\n");
		js.append("  if (sizeSelect) sizeSelect.addEventListener('change', function() {\n");
		js.append("    chunkPageSize = parseInt(this.value);\n");
		js.append("    if (searchMode) showSearchPage(0); else showChunkPage(currentChunk >= 0 ? currentChunk : 0);\n");
		js.append("  });\n");

		// Debounced search
		js.append("  var searchTimer;\n");
		js.append("  function debouncedSearch() { clearTimeout(searchTimer); searchTimer = setTimeout(doChunkSearch, 300); }\n");
		js.append("  if (searchInput) searchInput.addEventListener('input', debouncedSearch);\n");
		js.append("  if (cbTests) cbTests.addEventListener('change', doChunkSearch);\n");
		js.append("  if (cbClasses) cbClasses.addEventListener('change', doChunkSearch);\n");
		js.append("  if (cbMethods) cbMethods.addEventListener('change', doChunkSearch);\n");
		js.append("  if (cbSelected) cbSelected.addEventListener('change', doChunkSearch);\n\n");

		// Set totalChunks from data attribute and load first chunk
		js.append("  totalChunks = parseInt(container.dataset.total || '0');\n");
		js.append("  if (totalChunks > 0) showChunkPage(0);\n");

		js.append("})();\n");
		js.append("</script>\n");
		return js.toString();
	}

	// --- Shared UI component builders ---

	/**
	 * Builds a search bar with filter checkboxes (Tests/Classes/Methods) and a showing count.
	 * @param prefix ID prefix for namespacing (e.g. "matrix", "test-details", "coverage-map")
	 * @param totalCount total number of items, for the "Showing X of Y" label
	 */
	private String buildSearchBar(String prefix)
	{
		return buildSearchBar(prefix, false);
	}

	private String buildSearchBar(String prefix, boolean showSelectedFilter)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"search-bar\">\n");
		sb.append("    <input type=\"text\" id=\"" + prefix + "-search\" class=\"coverage-map-search\""
				+ " placeholder=\"Search tests, classes, or methods...\">\n");
		sb.append("    <div class=\"search-filters\">\n");
		sb.append("      <span class=\"search-filter-label\">Search in:</span>\n");
		sb.append("      <label class=\"search-filter-option\"><input type=\"checkbox\" id=\"" + prefix + "-filter-tests\" checked> Tests</label>\n");
		sb.append("      <label class=\"search-filter-option\"><input type=\"checkbox\" id=\"" + prefix + "-filter-classes\" checked> Classes</label>\n");
		sb.append("      <label class=\"search-filter-option\"><input type=\"checkbox\" id=\"" + prefix + "-filter-methods\" checked> Methods</label>\n");
		if (showSelectedFilter)
		{
			sb.append("      <span class=\"search-filter-separator\">|</span>\n");
			sb.append("      <label class=\"search-filter-option\"><input type=\"checkbox\" id=\"" + prefix + "-filter-selected\"> Selected only</label>\n");
		}
		sb.append("    </div>\n");
		sb.append("  </div>\n");
		sb.append("  <div id=\"" + prefix + "-count\" class=\"coverage-map-count\"></div>\n");
		return sb.toString();
	}

	/**
	 * Builds paging controls (page size selector, prev/next buttons, page info).
	 * @param prefix ID prefix for namespacing
	 * @param position "top" or "bottom" — bottom controls omit the page-size selector
	 */
	private String buildPagingControls(String prefix, String position)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"paging-controls\" id=\"" + prefix + "-paging" + ("bottom".equals(position) ? "-bottom" : "") + "\">\n");
		if ("top".equals(position))
		{
			sb.append("    <span>Page size:</span>\n");
			sb.append("    <select id=\"" + prefix + "-page-size\"><option value=\"50\" selected>50</option>"
					+ "<option value=\"100\">100</option><option value=\"200\">200</option></select>\n");
		}
		sb.append("    <button id=\"" + prefix + "-prev" + ("bottom".equals(position) ? "-bottom" : "") + "\">Prev</button>\n");
		sb.append("    <span id=\"" + prefix + "-page-info" + ("bottom".equals(position) ? "-bottom" : "") + "\" class=\"paging-info\"></span>\n");
		sb.append("    <button id=\"" + prefix + "-next" + ("bottom".equals(position) ? "-bottom" : "") + "\">Next</button>\n");
		sb.append("  </div>\n");
		return sb.toString();
	}

	// --- Utility methods ---

	/** Extracts the short class name from a FQN (e.g. "org.example.Foo" -> "Foo"). */
	private String shortClassName(String fqn)
	{
		int lastDot = fqn.lastIndexOf('.');
		return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
	}

	/**
	 * Renders a short class name, wrapped in a link if source coverage page exists.
	 * @param fqn full class name
	 * @param sourceLinks map of classFQN → relative path (may be null)
	 */
	private String linkedClassName(String fqn, Map<String, String> sourceLinks)
	{
		String shortName = shortClassName(fqn);
		if (sourceLinks != null && sourceLinks.containsKey(fqn))
		{
			return "<a href=\"" + esc(sourceLinks.get(fqn)) + "\" class=\"source-link\" target=\"_blank\">"
					+ esc(shortName) + "</a>";
		}
		return esc(shortName);
	}

	/**
	 * Returns the CSS color class for a coverage percentage.
	 * Green > 75%, Yellow 50–75%, Red < 50%.
	 */
	private String coverageColorClass(double pct)
	{
		if (pct > 75.0) return "cov-green";
		if (pct >= 50.0) return "cov-yellow";
		return "cov-red";
	}

	/**
	 * Renders a coverage bar + percentage for a class in the Changed Code section.
	 */
	private String coverageBar(String classFqn, Map<String, Double> covPct)
	{
		if (covPct == null || !covPct.containsKey(classFqn))
		{
			return "<span class=\"cov-pct cov-na\">N/A</span>";
		}
		return coverageBar(classFqn, covPct.get(classFqn));
	}

	private String coverageBar(String id, double pct)
	{
		String colorClass = coverageColorClass(pct);
		return "<div class=\"cov-bar\"><div class=\"cov-bar-fill " + colorClass
				+ "\" style=\"width: " + fmt(pct) + "%\"></div></div>"
				+ "<span class=\"cov-pct " + colorClass + "\">" + fmt(pct) + "%</span>";
	}

	/**
	 * Renders a small colored coverage badge for inline display in coverage matrix.
	 */
	private String coverageBadge(String classFqn, Map<String, Double> covPct)
	{
		if (covPct == null || !covPct.containsKey(classFqn))
		{
			return "";
		}
		double pct = covPct.get(classFqn);
		String colorClass = coverageColorClass(pct);
		return "<span class=\"cov-badge " + colorClass + "\">"
				+ String.format(java.util.Locale.US, "%.0f", pct) + "%</span>";
	}

	/** Extracts the package from a FQN (e.g. "org.example.Foo" -> "org.example."). */
	private String packageOf(String fqn)
	{
		int lastDot = fqn.lastIndexOf('.');
		return lastDot >= 0 ? fqn.substring(0, lastDot + 1) : "";
	}

	/** Shortens a test name for display (e.g. "FooTest#testBar" -> "testBar"). */
	private String shortTestName(String test)
	{
		int hash = test.indexOf('#');
		if (hash < 0)
		{
			return esc(test);
		}
		String classPart = test.substring(0, hash);
		String methodPart = test.substring(hash + 1);
		int lastDot = classPart.lastIndexOf('.');
		String pkg = lastDot >= 0 ? classPart.substring(0, lastDot) : "";
		String shortClass = lastDot >= 0 ? classPart.substring(lastDot + 1) : classPart;
		return "<span class=\"test-pkg\">" + esc(pkg) + "</span>"
				+ "<span class=\"test-class\">" + esc(shortClass) + "</span>"
				+ "<span class=\"test-method\">#" + esc(methodPart) + "</span>";
	}

	/** HTML-escape special characters. */
	private String esc(String text)
	{
		if (text == null)
			return "";
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	private static final int MAX_SEARCH_DATA_LENGTH = 2000;

	private String searchData(List<String> items)
	{
		String joined = String.join(" ", items);
		if (joined.length() > MAX_SEARCH_DATA_LENGTH)
		{
			return joined.substring(0, MAX_SEARCH_DATA_LENGTH);
		}
		return joined;
	}

	/** Format a double to 1 decimal place (always uses '.' as decimal separator for SVG/HTML). */
	private String fmt(double value)
	{
		return String.format(java.util.Locale.US, "%.1f", value);
	}

	// --- CSS ---

	private String getCSS()
	{
		return "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
				+ "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
				+ "  background: #f8f9fa; color: #2c3e50; line-height: 1.6; }\n"
				+ "\n"
				+ ".header { background: #1a1a2e; color: #fff; padding: 20px 32px;\n"
				+ "  display: flex; justify-content: space-between; align-items: center; }\n"
				+ ".header h1 { font-size: 1.5rem; font-weight: 700; }\n"
				+ ".header-subtitle { font-size: 0.85rem; opacity: 0.7; margin-left: 12px; }\n"
				+ ".header-right { display: flex; align-items: center; gap: 12px; font-size: 0.85rem; }\n"
				+ ".header-badge { background: #3498db; padding: 3px 10px; border-radius: 12px; font-size: 0.8rem; }\n"
				+ ".header-commit { font-family: monospace; opacity: 0.8; }\n"
				+ ".header-time { opacity: 0.6; }\n"
				+ "\n"
				+ ".banner { margin: 16px 32px; padding: 16px 20px; border-radius: 8px;\n"
				+ "  display: flex; align-items: center; gap: 12px; font-size: 0.95rem; }\n"
				+ ".banner-warning { background: #fff3cd; border-left: 4px solid #f39c12; color: #856404; }\n"
				+ ".banner-success { background: #d4edda; border-left: 4px solid #2ecc71; color: #155724; }\n"
				+ ".banner-icon { font-size: 1.5rem; }\n"
				+ "\n"
				+ ".stats { display: grid; grid-template-columns: repeat(6, 1fr); gap: 16px;\n"
				+ "  padding: 24px 32px; }\n"
				+ ".stat-card { background: #fff; border-radius: 12px; padding: 20px;\n"
				+ "  box-shadow: 0 2px 8px rgba(0,0,0,0.08); border-left: 4px solid #ccc;\n"
				+ "  text-align: center; }\n"
				+ ".stat-value { font-size: 2rem; font-weight: 700; }\n"
				+ ".stat-label { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.5px;\n"
				+ "  color: #7f8c8d; margin-top: 4px; }\n"
				+ ".accent-green { border-left-color: #2ecc71; }\n"
				+ ".accent-green .stat-value { color: #2ecc71; }\n"
				+ ".accent-amber { border-left-color: #f39c12; }\n"
				+ ".accent-amber .stat-value { color: #f39c12; }\n"
				+ ".accent-blue { border-left-color: #3498db; }\n"
				+ ".accent-blue .stat-value { color: #3498db; }\n"
				+ ".accent-purple { border-left-color: #9b59b6; }\n"
				+ ".accent-purple .stat-value { color: #9b59b6; }\n"
				+ ".accent-gray { border-left-color: #95a5a6; }\n"
				+ ".accent-gray .stat-value { color: #95a5a6; }\n"
				+ ".accent-teal { border-left-color: #1abc9c; }\n"
				+ ".accent-teal .stat-value { color: #1abc9c; }\n"
				+ "\n"
				+ ".charts { display: grid; grid-template-columns: 1fr 1fr; gap: 24px;\n"
				+ "  padding: 0 32px 24px; }\n"
				+ ".chart-container { background: #fff; border-radius: 12px; padding: 24px;\n"
				+ "  box-shadow: 0 2px 8px rgba(0,0,0,0.08); }\n"
				+ ".chart-container h2 { font-size: 1rem; text-transform: uppercase;\n"
				+ "  letter-spacing: 0.5px; color: #7f8c8d; margin-bottom: 16px; }\n"
				+ ".donut-chart { width: 160px; height: 160px; display: block; margin: 0 auto; }\n"
				+ ".donut-number { font-size: 1.8rem; font-weight: 700; fill: #2c3e50; }\n"
				+ ".donut-label { font-size: 0.75rem; fill: #7f8c8d; }\n"
				+ ".chart-legend { text-align: center; margin-top: 12px; }\n"
				+ ".legend-item { margin: 0 10px; font-size: 0.85rem; }\n"
				+ ".legend-dot { display: inline-block; width: 10px; height: 10px;\n"
				+ "  border-radius: 50%; margin-right: 4px; vertical-align: middle; }\n"
				+ ".bar-chart { width: 100%; height: auto; }\n"
				+ ".bar-label { font-size: 15px; fill: #2c3e50; }\n"
				+ ".bar-rect { fill: #3498db; }\n"
				+ ".bar-count { font-size: 15px; fill: #7f8c8d; }\n"
				+ "\n"
				+ ".section { background: #fff; border-radius: 12px; margin: 0 32px 24px;\n"
				+ "  padding: 24px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }\n"
				+ ".section h2 { font-size: 1.1rem; text-transform: uppercase;\n"
				+ "  letter-spacing: 0.5px; color: #7f8c8d; margin-bottom: 16px; }\n"
				+ ".section h3 { font-size: 1rem; color: #2c3e50; margin-bottom: 8px; }\n"
				+ ".changed-method { font-size: 0.88rem; color: #555; font-family: monospace;\n"
				+ "  padding-left: 16px; margin-top: 2px; }\n"
				+ ".changed-method::before { content: '\\2514\\0020'; color: #bbb; }\n"
				+ ".changed-method.no-info { color: #b0b0b0; font-style: italic; font-family: inherit; }\n"
				+ ".changed-method.no-info::before { content: ''; }\n"
				+ ".empty-note { color: #95a5a6; font-style: italic; }\n"
				+ "\n"
				+ ".data-table { width: 100%; border-collapse: collapse; }\n"
				+ ".data-table th, .data-table td { padding: 10px 14px; border-bottom: 1px solid #ecf0f1;\n"
				+ "  text-align: left; font-size: 0.95rem; }\n"
				+ ".data-table th { background: #f8f9fa; font-weight: 600; color: #7f8c8d;\n"
				+ "  text-transform: uppercase; font-size: 0.8rem; letter-spacing: 0.5px; }\n"
				+ ".sortable-table th.sortable { cursor: pointer; user-select: none; }\n"
				+ ".sortable-table th.sortable:hover { background: #eef1f3; }\n"
				+ ".sort-arrow { font-size: 0.7rem; color: #3498db; }\n"
				+ ".sort-arrow:empty::after { content: '\\21C5'; opacity: 0.4; }\n"
				+ ".pkg { color: #95a5a6; }\n"
				+ "\n"
				+ ".matrix-wrapper { overflow-x: auto; }\n"
				+ ".matrix-table { border-collapse: collapse; width: 100%; font-size: 0.9rem; }\n"
				+ ".matrix-table th, .matrix-table td { padding: 8px 10px;\n"
				+ "  border: 1px solid #ecf0f1; text-align: center; }\n"
				+ ".matrix-table thead th { background: #f8f9fa; font-weight: 600; color: #7f8c8d;\n"
				+ "  font-size: 0.8rem; text-transform: uppercase; }\n"
				+ ".matrix-col-header { writing-mode: vertical-lr; transform: rotate(180deg);\n"
				+ "  min-width: 30px; max-width: 60px; white-space: nowrap; }\n"
				+ ".matrix-cell { width: 30px; }\n"
				+ ".test-name { text-align: left; white-space: normal; max-width: 400px; }\n"
				+ ".test-pkg { display: block; font-size: 0.75em; color: #95a5a6; line-height: 1.3; }\n"
				+ ".test-class { display: block; font-weight: 600; line-height: 1.3; }\n"
				+ ".test-method { display: block; color: #555; line-height: 1.3; }\n"
				+ ".row-selected { background: #f0faf4; }\n"
				+ ".row-skipped { background: #fafafa; }\n"
				+ ".dot-covered { color: #2ecc71; font-size: 1rem; }\n"
				+ ".dot-empty { color: #ddd; font-size: 0.8rem; }\n"
				+ ".coverage-list { font-size: 0.88rem; font-family: monospace; color: #555;\n"
				+ "  text-align: left; white-space: normal; max-width: 400px; }\n"
				+ ".matrix-cell-collapsed { max-height: 3.6em; overflow: hidden; }\n"
				+ ".matrix-cell-collapsed.matrix-cell-expanded { max-height: none; }\n"
				+ ".matrix-toggle { display: inline-block; margin-top: 4px; font-size: 0.82rem;\n"
				+ "  color: #3498db; cursor: pointer; font-family: -apple-system, sans-serif; }\n"
				+ ".matrix-toggle:hover { text-decoration: underline; }\n"
				+ "\n"
				+ ".metadata-section { }\n"
				+ ".metadata-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));\n"
				+ "  gap: 12px; }\n"
				+ ".metadata-item { display: flex; flex-direction: column; padding: 8px 12px;\n"
				+ "  background: #f8f9fa; border-radius: 6px; }\n"
				+ ".metadata-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.5px;\n"
				+ "  color: #7f8c8d; margin-bottom: 2px; }\n"
				+ ".metadata-value { font-size: 0.95rem; font-weight: 600; color: #2c3e50; }\n"
				+ ".metadata-value.mono { font-family: monospace; }\n"
				+ "\n"
				+ ".unmapped-section { }\n"
				+ ".unmapped-info { display: flex; align-items: flex-start; gap: 10px; padding: 12px 16px;\n"
				+ "  background: #fff8e1; border-left: 4px solid #f39c12; border-radius: 6px;\n"
				+ "  font-size: 0.9rem; color: #856404; margin-bottom: 16px; }\n"
				+ ".unmapped-list { list-style: none; padding: 0; }\n"
				+ ".unmapped-item { padding: 8px 12px; border-bottom: 1px solid #ecf0f1;\n"
				+ "  font-size: 0.95rem; }\n"
				+ ".unmapped-item:last-child { border-bottom: none; }\n"
				+ ".unmapped-item code { font-family: monospace; background: #f8f9fa;\n"
				+ "  padding: 2px 6px; border-radius: 4px; font-size: 0.9rem; }\n"
				+ "\n"
				+ ".badge { padding: 3px 10px; border-radius: 10px; font-size: 0.78rem;\n"
				+ "  font-weight: 600; text-transform: uppercase; }\n"
				+ ".badge-selected { background: #d4edda; color: #155724; }\n"
				+ ".badge-skipped { background: #f8f9fa; color: #6c757d; }\n"
				+ "\n"
				+ ".test-detail-table { font-size: 0.95rem; }\n"
				+ ".test-detail-table .reason { color: #7f8c8d; font-size: 0.9rem; }\n"
				+ "\n"
				+ ".footer { text-align: center; padding: 24px; color: #95a5a6; font-size: 0.8rem; }\n"
				+ "\n"
				+ ".coverage-map-search { width: 100%; padding: 10px 14px; font-size: 0.9rem;\n"
				+ "  border: 1px solid #ddd; border-radius: 8px; margin-bottom: 8px;\n"
				+ "  outline: none; transition: border-color 0.2s; }\n"
				+ ".coverage-map-search:focus { border-color: #3498db; }\n"
				+ ".search-bar { margin-bottom: 8px; }\n"
				+ ".search-filters { display: flex; align-items: center; gap: 14px; margin-top: 6px; }\n"
				+ ".search-filter-label { font-size: 0.9rem; color: #7f8c8d; }\n"
				+ ".search-filter-option { font-size: 0.9rem; color: #2c3e50; cursor: pointer;\n"
				+ "  display: flex; align-items: center; gap: 4px; }\n"
				+ ".search-filter-option input { cursor: pointer; }\n"
				+ ".search-filter-separator { color: #bdc3c7; margin: 0 4px; }\n"
				+ ".coverage-map-count { font-size: 0.9rem; color: #95a5a6; margin-bottom: 12px; }\n"
				+ ".coverage-map-entry { padding: 12px 0; border-bottom: 1px solid #ecf0f1; }\n"
				+ ".coverage-map-entry:last-child { border-bottom: none; }\n"
				+ ".coverage-map-header { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }\n"
				+ ".coverage-map-test { font-weight: 600; font-size: 0.95rem; font-family: monospace; }\n"
				+ ".coverage-map-detail { font-size: 0.88rem; color: #7f8c8d; margin-left: 12px; margin-top: 2px; }\n"
				+ ".coverage-map-class, .coverage-map-method { font-family: monospace; font-size: 0.85rem; }\n"
				+ "\n"
				+ ".paging-controls { display: flex; align-items: center; gap: 12px; margin: 12px 0;\n"
				+ "  font-size: 0.9rem; color: #7f8c8d; }\n"
				+ ".paging-controls button { padding: 5px 14px; border: 1px solid #ddd; border-radius: 6px;\n"
				+ "  background: #fff; cursor: pointer; font-size: 0.9rem; color: #2c3e50; }\n"
				+ ".paging-controls button:hover:not(:disabled) { background: #f0f0f0; }\n"
				+ ".paging-controls button:disabled { opacity: 0.4; cursor: default; }\n"
				+ ".paging-controls select { padding: 4px 8px; border: 1px solid #ddd; border-radius: 6px;\n"
				+ "  font-size: 0.9rem; background: #fff; }\n"
				+ ".paging-info { color: #95a5a6; font-size: 0.9rem; }\n"
				+ "\n"
				+ ".source-link { color: #3498db; text-decoration: none; }\n"
				+ ".source-link:hover { text-decoration: underline; }\n"
				+ "\n"
				+ ".cov-bar { width: 80px; height: 8px; background: #eee; border-radius: 4px;\n"
				+ "  display: inline-block; vertical-align: middle; }\n"
				+ ".cov-bar-fill { height: 100%; border-radius: 4px; }\n"
				+ ".cov-bar-fill.cov-green { background: #27ae60; }\n"
				+ ".cov-bar-fill.cov-yellow { background: #f39c12; }\n"
				+ ".cov-bar-fill.cov-red { background: #e74c3c; }\n"
				+ ".cov-pct { font-size: 0.85rem; font-weight: 600; margin-left: 6px; }\n"
				+ ".cov-pct.cov-green { color: #27ae60; }\n"
				+ ".cov-pct.cov-yellow { color: #f39c12; }\n"
				+ ".cov-pct.cov-red { color: #e74c3c; }\n"
				+ ".cov-pct.cov-na { color: #999; }\n"
				+ ".cov-cell { white-space: nowrap; }\n"
				+ ".cov-badge { font-size: 0.7rem; padding: 1px 5px; border-radius: 8px;\n"
				+ "  margin-left: 4px; color: #fff; display: inline-block; }\n"
				+ ".cov-badge.cov-green { background: #27ae60; }\n"
				+ ".cov-badge.cov-yellow { background: #f39c12; }\n"
				+ ".cov-badge.cov-red { background: #e74c3c; }\n"
				+ "\n"
				+ ".expansion-summary { display: flex; align-items: center; gap: 12px;\n"
				+ "  padding: 16px 20px; background: #eaf6ff; border-radius: 8px;\n"
				+ "  border-left: 4px solid #3498db; margin-bottom: 20px; font-size: 0.95rem; }\n"
				+ ".expansion-selected { font-weight: 700; color: #2c3e50; }\n"
				+ ".expansion-arrow { font-size: 1.3rem; color: #3498db; }\n"
				+ ".expansion-total { font-weight: 700; color: #3498db; font-size: 1.1rem; }\n"
				+ ".expansion-classes { color: #7f8c8d; font-size: 0.88rem; }\n"
				+ ".expansion-class { margin-bottom: 16px; }\n"
				+ ".expansion-class-header { display: flex; align-items: center; gap: 10px;\n"
				+ "  padding: 8px 12px; background: #f8f9fa; border-radius: 6px; margin-bottom: 6px; }\n"
				+ ".expansion-class-name { font-weight: 700; font-family: monospace; font-size: 0.95rem; }\n"
				+ ".expansion-class-count { font-size: 0.8rem; color: #7f8c8d; }\n"
				+ ".expansion-methods { display: flex; flex-wrap: wrap; gap: 8px; padding-left: 12px; }\n"
				+ ".expansion-method { display: inline-flex; align-items: center; gap: 6px;\n"
				+ "  padding: 4px 12px; border-radius: 6px; font-family: monospace; font-size: 0.88rem; }\n"
				+ ".expansion-method-selected { background: #d4edda; color: #155724; }\n"
				+ ".expansion-method-added { background: #fff3cd; color: #856404; }\n"
				+ ".expansion-tag { font-size: 0.65rem; padding: 1px 6px; border-radius: 8px;\n"
				+ "  text-transform: uppercase; font-weight: 700; font-family: -apple-system, sans-serif; }\n"
				+ ".expansion-tag-selected { background: #27ae60; color: #fff; }\n"
				+ ".expansion-tag-added { background: #f39c12; color: #fff; }\n"
				+ "\n"
				+ ".toggle-btn { cursor: pointer; display: inline-block; font-size: 0.7rem;\n"
				+ "  margin-right: 6px; color: #7f8c8d; transition: transform 0.2s; user-select: none; }\n"
				+ ".toggle-btn.expanded { transform: rotate(90deg); }\n"
				+ ".entry-counts { font-size: 0.82rem; color: #95a5a6; margin-left: auto; }\n"
				+ ".expand-all-bar { display: flex; gap: 8px; margin-bottom: 12px; }\n"
				+ ".expand-all-btn { padding: 5px 14px; border: 1px solid #ddd; border-radius: 6px;\n"
				+ "  background: #fff; cursor: pointer; font-size: 0.85rem; color: #2c3e50; }\n"
				+ ".expand-all-btn:hover { background: #f0f0f0; }\n"
				+ ".truncation-note { color: #7f8c8d; font-size: 0.9rem; margin-bottom: 12px;\n"
				+ "  padding: 8px 12px; background: #f8f9fa; border-radius: 6px; }\n"
				+ ".chunk-loading { color: #95a5a6; font-style: italic; padding: 20px; text-align: center; }\n"
				+ "\n"
				+ ".tab-bar { display: flex; gap: 0; background: #fff; border-bottom: 2px solid #e0e0e0;\n"
				+ "  padding: 0 32px; }\n"
				+ ".tab-item { padding: 12px 24px; cursor: pointer; font-weight: 600; font-size: 0.95rem;\n"
				+ "  color: #7f8c8d; border-bottom: 3px solid transparent; margin-bottom: -2px;\n"
				+ "  transition: color 0.2s, border-color 0.2s; user-select: none; }\n"
				+ ".tab-item:hover { color: #2c3e50; }\n"
				+ ".tab-item.active { color: #3498db; border-bottom-color: #3498db; }\n"
				+ ".tab-item.tab-disabled { color: #bdc3c7; cursor: default; }\n"
				+ ".tab-badge { font-size: 0.7rem; background: #ecf0f1; padding: 1px 6px;\n"
				+ "  border-radius: 8px; margin-left: 4px; vertical-align: middle; }\n"
				+ ".tab-content { display: none; }\n"
				+ ".tab-content.active { display: block; }\n"
				+ "\n"
				+ ".metrics-unavailable { color: #7f8c8d; font-size: 0.95rem; padding: 20px;\n"
				+ "  text-align: center; }\n"
				+ ".metrics-filters { display: flex; gap: 8px; margin-bottom: 16px; }\n"
				+ ".filter-btn { padding: 6px 16px; border: 1px solid #ddd; border-radius: 6px;\n"
				+ "  background: #fff; cursor: pointer; font-size: 0.85rem; color: #2c3e50; }\n"
				+ ".filter-btn:hover { background: #f0f0f0; }\n"
				+ ".filter-btn.active { background: #3498db; color: #fff; border-color: #3498db; }\n"
				+ ".metrics-table .cls-name { font-family: 'SF Mono', Monaco, 'Consolas', monospace;\n"
				+ "  font-size: 0.85rem; word-break: break-all; }\n"
				+ ".sortable { cursor: pointer; user-select: none; }\n"
				+ ".sortable:hover { background: #ecf0f1; }\n"
				+ "\n"
				+ "@media print {\n"
				+ "  body { background: #fff; }\n"
				+ "  .header { background: #1a1a2e; -webkit-print-color-adjust: exact; print-color-adjust: exact; }\n"
				+ "  .stat-card, .chart-container, .section { box-shadow: none; border: 1px solid #e0e0e0; }\n"
				+ "  .stats { grid-template-columns: repeat(3, 1fr); }\n"
				+ "}\n"
				+ "\n"
				+ "@media (max-width: 900px) {\n"
				+ "  .stats { grid-template-columns: repeat(3, 1fr); }\n"
				+ "  .charts { grid-template-columns: 1fr; }\n"
				+ "}\n";
	}
}
