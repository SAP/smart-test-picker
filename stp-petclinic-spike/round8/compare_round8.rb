# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'
require 'rexml/document'
require 'set'

DIR = File.expand_path(ARGV.fetch(0, File.join(__dir__, 'evidence')))
inventory_doc = JSON.parse(File.read(File.join(DIR, 'reference-inventory.json')))
inventory = inventory_doc.fetch('asmMappingTests')
reference_methods_present = JSON.parse(File.read(File.join(DIR, 'reference-method-map.json'))).fetch('tests')
reference = inventory.to_h { |test| [test, reference_methods_present.fetch(test, [])] }

def outcomes(report_dir, inventory)
  result = {}
  Dir[File.join(report_dir, 'TEST-*.xml')].sort.each do |path|
    doc = REXML::Document.new(File.read(path))
    doc.root.elements.each('testcase') do |testcase|
      id = "#{testcase.attributes['classname']}##{testcase.attributes['name']}"
      next unless inventory.include?(id)
      status = if testcase.elements['failure'] then 'FAILED'
               elsif testcase.elements['error'] then 'ERROR'
               elsif testcase.elements['skipped'] then 'SKIPPED'
               else 'PASSED' end
      result[id] = status
    end
  end
  inventory.to_h { |test| [test, result.fetch(test, 'MISSING')] }
end

def asm(path, inventory)
  doc = JSON.parse(File.read(path))
  tests = doc.fetch('runtimeEvents').fetch('tests').to_h do |test|
    id = "#{test.fetch('testClass')}##{test.fetch('testMethod')}"
    [id, test.fetch('methods').map { |entry| entry.fetch('method') }.uniq.sort]
  end
  events = doc.fetch('runtimeEvents').fetch('unattributedEvents').map { |e| e.merge('scope' => 'global') }
  doc.fetch('runtimeEvents').fetch('tests').each do |test|
    id = "#{test.fetch('testClass')}##{test.fetch('testMethod')}"
    test.fetch('unattributedEvents').each { |e| events << e.merge('scope' => id) }
  end
  { 'methods' => inventory.to_h { |id| [id, tests.fetch(id, [])] }, 'events' => events,
    'agentErrors' => doc.fetch('agentErrors'), 'metrics' => doc.fetch('metrics') }
end

def classes(methods)
  methods.map { |method| method.split('#', 2).first }.uniq.sort
end

def metrics(map, outcomes)
  method_edges = map.values.sum(&:size)
  class_map = map.transform_values { |methods| classes(methods) }
  { 'inventory' => map.size, 'mappedTests' => map.count { |_k, v| !v.empty? },
    'unmappedTests' => map.select { |_k, v| v.empty? }.keys.sort,
    'outcomes' => outcomes, 'classEdges' => class_map.values.sum(&:size), 'methodEdges' => method_edges,
    'globalClasses' => class_map.values.flatten.uniq.sort, 'globalMethods' => map.values.flatten.uniq.sort }
end

def compare(left, right, outcomes_left, outcomes_right)
  method_diffs = left.keys.to_h do |test|
    l = left.fetch(test).to_set; r = right.fetch(test).to_set
    [test, { 'leftOnly' => (l - r).sort, 'rightOnly' => (r - l).sort }]
  end
  class_diffs = left.keys.to_h do |test|
    l = classes(left.fetch(test)).to_set; r = classes(right.fetch(test)).to_set
    [test, { 'leftOnly' => (l - r).sort, 'rightOnly' => (r - l).sort }]
  end
  changed = left.keys.select do |test|
    method_diffs[test].values.any?(&:any?) || outcomes_left[test] != outcomes_right[test]
  end
  l_methods = left.values.flatten.to_set; r_methods = right.values.flatten.to_set
  l_classes = l_methods.map { |m| m.split('#', 2).first }.to_set
  r_classes = r_methods.map { |m| m.split('#', 2).first }.to_set
  { 'changedTests' => changed.sort, 'changedTestCount' => changed.size,
    'methodEdgeDiff' => { 'leftOnly' => method_diffs.values.sum { |d| d['leftOnly'].size },
                          'rightOnly' => method_diffs.values.sum { |d| d['rightOnly'].size } },
    'classEdgeDiff' => { 'leftOnly' => class_diffs.values.sum { |d| d['leftOnly'].size },
                         'rightOnly' => class_diffs.values.sum { |d| d['rightOnly'].size } },
    'setupDiff' => { 'leftOnly' => 0, 'rightOnly' => 0 },
    'outcomeDiff' => left.keys.select { |t| outcomes_left[t] != outcomes_right[t] }.sort,
    'methodDifferences' => method_diffs.select { |_t, d| d.values.any?(&:any?) },
    'classDifferences' => class_diffs.select { |_t, d| d.values.any?(&:any?) },
    'global' => { 'commonClasses' => (l_classes & r_classes).to_a.sort,
                  'leftOnlyClasses' => (l_classes - r_classes).to_a.sort,
                  'rightOnlyClasses' => (r_classes - l_classes).to_a.sort,
                  'commonMethods' => (l_methods & r_methods).to_a.sort,
                  'leftOnlyMethods' => (l_methods - r_methods).to_a.sort,
                  'rightOnlyMethods' => (r_methods - l_methods).to_a.sort } }
end

runs = (1..2).to_h do |number|
  name = "asm-run-#{number}"
  data = asm(File.join(DIR, "#{name}-map.json"), inventory)
  run_outcomes = outcomes(File.join(DIR, "#{name}-surefire-reports"), inventory)
  summary = metrics(data.fetch('methods'), run_outcomes)
  summary['unattributed'] = %w[LATE_EVENT NO_ACTIVE_TEST UNKNOWN_CONTEXT].to_h do |reason|
    [reason, data.fetch('events').select { |event| event['reason'] == reason }.sum { |event| event['count'] }]
  end
  summary['unattributedEvents'] = data.fetch('events')
  summary['agentErrors'] = data.fetch('agentErrors')
  summary['agentMetrics'] = data.fetch('metrics')
  [name, data.merge('summary' => summary, 'outcomes' => run_outcomes)]
end

reference_outcomes = inventory.to_h { |test| [test, 'PASSED'] }
reference_summary = metrics(reference, reference_outcomes)
ref_vs_asm = compare(reference, runs['asm-run-1']['methods'], reference_outcomes, runs['asm-run-1']['outcomes'])
repeatability = compare(runs['asm-run-1']['methods'], runs['asm-run-2']['methods'],
                        runs['asm-run-1']['outcomes'], runs['asm-run-2']['outcomes'])
mapped_denominator = [reference_summary['mappedTests'], runs['asm-run-1']['summary']['mappedTests']].max
ref_vs_asm['percentageOfMappedTestsChanged'] = mapped_denominator.zero? ? 0.0 :
  (100.0 * ref_vs_asm['changedTestCount'] / mapped_denominator).round(6)

report = { 'schemaVersion' => 'stp-asm-round8-semantic-diff-1',
  'setupSemantics' => 'No explicit setup relationships exist in either schema; beforeEach/afterEach execute inside the leaf test interval.',
  'reference' => reference_summary, 'asmRun1' => runs['asm-run-1']['summary'],
  'asmRun2' => runs['asm-run-2']['summary'], 'referenceVsAsmRun1' => ref_vs_asm,
  'asmRun1VsRun2' => repeatability }
File.write(File.join(DIR, 'semantic-diff-reference-vs-asm.json'), JSON.pretty_generate(report.slice(
  'schemaVersion', 'setupSemantics', 'reference', 'asmRun1', 'referenceVsAsmRun1')) + "\n")
File.write(File.join(DIR, 'semantic-diff-asm-run-1-vs-run-2.json'), JSON.pretty_generate(report.slice(
  'schemaVersion', 'setupSemantics', 'asmRun1', 'asmRun2', 'asmRun1VsRun2')) + "\n")
File.write(File.join(DIR, 'semantic-comparison-full.json'), JSON.pretty_generate(report) + "\n")
puts JSON.pretty_generate({ 'reference' => reference_summary.slice('inventory', 'mappedTests', 'classEdges', 'methodEdges'),
  'asmRun1' => runs['asm-run-1']['summary'].slice('inventory', 'mappedTests', 'classEdges', 'methodEdges', 'unattributed'),
  'asmRun2' => runs['asm-run-2']['summary'].slice('inventory', 'mappedTests', 'classEdges', 'methodEdges', 'unattributed'),
  'referenceVsAsm' => ref_vs_asm.slice('changedTestCount', 'classEdgeDiff', 'methodEdgeDiff', 'outcomeDiff'),
  'repeatability' => repeatability.slice('changedTestCount', 'classEdgeDiff', 'methodEdgeDiff', 'outcomeDiff') })
