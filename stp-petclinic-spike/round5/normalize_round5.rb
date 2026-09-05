# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'
require 'rexml/document'
require 'set'

INPUT = File.expand_path(ARGV.fetch(0, File.join(__dir__, 'evidence')))

def asm_document(name)
  JSON.parse(File.read(File.join(INPUT, "#{name}-raw.json")))
end

def asm_map(document)
  document.fetch('runtimeEvents').fetch('tests').to_h do |test|
    identity = "#{test.fetch('testClass')}##{test.fetch('testMethod')}"
    [identity, test.fetch('methods').map { |entry| entry.fetch('method') }.uniq.sort]
  end
end

def normalize_asm(name)
  document = asm_document(name)
  normalized = {
    'schemaVersion' => 'stp-asm-round5-normalized-map-1',
    'runId' => name,
    'tests' => asm_map(document).sort.to_h,
    'globalMethodHits' => document.fetch('methodHits'),
    'unattributedEvents' => document.fetch('runtimeEvents').fetch('unattributedEvents'),
    'testDiagnostics' => document.fetch('runtimeEvents').fetch('tests').to_h do |test|
      ["#{test.fetch('testClass')}##{test.fetch('testMethod')}", test.fetch('unattributedEvents')]
    end,
    'agentErrors' => document.fetch('agentErrors'),
    'metrics' => document.fetch('metrics')
  }
  File.write(File.join(INPUT, "#{name}-normalized.json"), JSON.pretty_generate(normalized) + "\n")
  normalized
end

names = %w[asm-fixed-1 asm-fixed-2 asm-fixed-3 asm-reverse-1]
normalized = names.to_h { |name| [name, normalize_asm(name)] }
inventory_document = JSON.parse(File.read(File.join(INPUT, 'test-inventory.json')))
inventory = inventory_document.fetch('asmMappingTests')

# XML report filenames retain the listener's simple-class session ID and hash. Resolve them against inventory.
def java_hash(text)
  text.each_codepoint.reduce(0) { |hash, char| ((31 * hash + char) & 0xffffffff) }.then { |value| value & 0x7fffffff }
end

session_to_test = inventory.to_h do |test|
  klass, method = test.split('#', 2)
  hash = java_hash(test).to_s(16).rjust(7, '0')[0, 7]
  simple = klass.split('.').last.split('$').last
  ["#{simple}##{method}_#{hash}", test]
end

jacoco_descriptors = {}
Dir[File.join(INPUT, 'jacoco-xml', 'session_*.xml')].sort.each do |path|
  session = File.basename(path, '.xml').delete_prefix('session_').gsub(/~([A-Z])/, '\\1')
  test = session_to_test.fetch(session) { abort "unknown JaCoCo session #{session}" }
  methods = []
  document = REXML::Document.new(File.read(path))
  REXML::XPath.each(document, '/report/package/class') do |klass|
    binary = klass.attributes['name'].tr('/', '.')
    next unless binary.start_with?('org.springframework.samples.petclinic.')
    REXML::XPath.each(klass, 'method') do |method|
      covered = REXML::XPath.match(method, "counter[@type='INSTRUCTION']").sum { |counter| counter.attributes['covered'].to_i }
      methods << "#{binary}##{method.attributes['name']}#{method.attributes['desc']}" if covered.positive?
    end
  end
  jacoco_descriptors[test] = methods.uniq.sort
end
File.write(File.join(INPUT, 'jacoco-descriptor-map.json'), JSON.pretty_generate({
  'schemaVersion' => 'stp-asm-round5-jacoco-descriptor-map-1', 'tests' => jacoco_descriptors.sort.to_h
}) + "\n")

reference = normalized.fetch('asm-fixed-1').fetch('tests')
event_summary = lambda do |run|
  events = run.fetch('unattributedEvents') + run.fetch('testDiagnostics').flat_map do |test, entries|
    entries.map { |entry| entry.merge('test' => test) }
  end
  {
    'noActiveTestCount' => events.select { |entry| entry['reason'] == 'NO_ACTIVE_TEST' }.sum { |entry| entry['count'] },
    'lateEventCount' => events.select { |entry| entry['reason'] == 'LATE_EVENT' }.sum { |entry| entry['count'] },
    'lateEventsByTest' => events.select { |entry| entry['reason'] == 'LATE_EVENT' }
      .sort_by { |entry| [entry.fetch('test', ''), entry['eventIdentity']] },
    'executorAttributionIncomplete' => run.fetch('agentErrors').grep(/executor-attribution-incomplete/),
    'executorAttributionUnsupported' => run.fetch('agentErrors').grep(/executor-attribution-unsupported/)
  }
end
diff_for = lambda do |candidate|
  inventory.to_h do |test|
    left = Set.new(reference.fetch(test, [])); right = Set.new(candidate.fetch(test, []))
    [test, { 'missing' => (left - right).sort, 'extra' => (right - left).sort }]
  end.reject { |_test, diff| diff['missing'].empty? && diff['extra'].empty? }
end

determinism = names.drop(1).to_h do |name|
  candidate = normalized.fetch(name).fetch('tests')
  diffs = diff_for.call(candidate)
  left_union = reference.values.flatten.to_set; right_union = candidate.values.flatten.to_set
  [name, { 'identicalTests' => inventory.size - diffs.size, 'changedTests' => diffs.size,
           'differences' => diffs, 'globalMissing' => (left_union - right_union).sort,
           'globalExtra' => (right_union - left_union).sort,
           'referenceDiagnostics' => event_summary.call(normalized.fetch('asm-fixed-1')),
           'candidateDiagnostics' => event_summary.call(normalized.fetch(name)) }]
end

comparisons = inventory.to_h do |test|
  asm = Set.new(reference.fetch(test, [])); jacoco = Set.new(jacoco_descriptors.fetch(test, []))
  union = asm | jacoco
  [test, { 'common' => (asm & jacoco).sort, 'asmOnly' => (asm - jacoco).sort,
           'jacocoOnly' => (jacoco - asm).sort,
           'jaccard' => union.empty? ? 1.0 : ((asm & jacoco).size.to_f / union.size).round(6) }]
end
classifications = comparisons.to_h do |test, entry|
  edges = {}
  entry.fetch('asmOnly').each do |method|
    edges[method] = { 'side' => 'asm-only', 'cause' => 'unknown',
                      'detail' => 'No ASM-only edges were expected in this run.' }
  end
  entry.fetch('jacocoOnly').each do |method|
    edges[method] = { 'side' => 'jacoco-only', 'cause' => 'JaCoCo attribution artifact',
      'secondaryCause' => 'constructor',
      'detail' => 'ASM global methodHits observed this constructor as LATE_EVENT after the preceding test; JaCoCo reset-at-finish left the between-test probe for the next test session.' }
  end
  [test, edges]
end
asm_union = reference.values.flatten.to_set; jacoco_union = jacoco_descriptors.values.flatten.to_set
report = { 'schemaVersion' => 'stp-asm-round5-comparison-1', 'totalTests' => inventory.size,
  'determinism' => determinism, 'comparisons' => comparisons,
  'globalUnion' => { 'common' => (asm_union & jacoco_union).sort, 'asmOnly' => (asm_union - jacoco_union).sort,
                     'jacocoOnly' => (jacoco_union - asm_union).sort } }
File.write(File.join(INPUT, 'comparison-report.json'), JSON.pretty_generate(report) + "\n")
File.write(File.join(INPUT, 'diff-details.json'), JSON.pretty_generate({
  'schemaVersion' => 'stp-asm-round5-diff-details-1',
  'determinismChangedEdges' => determinism.transform_values { |entry| entry['differences'] },
  'asmVsJacocoChangedEdges' => comparisons.select { |_test, entry| entry['asmOnly'].any? || entry['jacocoOnly'].any? },
  'classifications' => classifications.reject { |_test, edges| edges.empty? }
}) + "\n")
puts "normalized #{inventory.size} tests"
