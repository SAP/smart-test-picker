# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'
require 'rexml/document'
require 'set'

input = File.expand_path(ARGV.fetch(0, File.join(__dir__, 'raw')))
output = File.expand_path(ARGV.fetch(1, File.join(__dir__, 'normalized-round2.json')))

def asm_sets(path)
  document = JSON.parse(File.read(path))
  sets = document.fetch('runtimeEvents').fetch('tests').to_h do |test|
    name = "#{test.fetch('testClass')}##{test.fetch('testMethod')}"
    [name, test.fetch('methods').map { |method| method.fetch('method') }.to_set]
  end
  [sets, document.fetch('metrics')]
end

def jacoco_set(path)
  document = REXML::Document.new(File.read(path))
  result = Set.new
  REXML::XPath.each(document, '/report/package/class') do |klass|
    binary = klass.attributes['name'].to_s.tr('/', '.')
    next unless binary.start_with?('org.springframework.samples.petclinic.')
    REXML::XPath.each(klass, 'method') do |method|
      covered = REXML::XPath.match(method, "counter[@type='INSTRUCTION']")
                              .sum { |counter| counter.attributes['covered'].to_s.to_i }
      result << "#{binary}##{method.attributes['name']}#{method.attributes['desc']}" if covered.positive?
    end
  end
  result
end

def category(method)
  return 'constructor' if method.include?('#<init>')
  return 'static initializer' if method.include?('#<clinit>')
  return 'lambda body' if method.include?('#lambda$')
  return 'synthetic/compiler-generated method' if method.match?(/#(access\$|\$deserializeLambda\$)/)
  return 'bridge method' if method.match?(/\)Ljava\/lang\/Object;$/)
  'unclassified application method'
end

manifest = JSON.parse(File.read(File.join(input, 'run-manifest.json')))
comparisons = {}
3.times do |index|
  asm_path = File.join(input, "dual-#{index + 1}-asm.json")
  asm_document = JSON.parse(File.read(asm_path))
  asm, = asm_sets(asm_path)
  test = manifest.fetch('tests').fetch(index)
  left = asm.fetch(test, Set.new)
  right = jacoco_set(File.join(input, "dual-#{index + 1}-jacoco.xml"))
  both = left & right
  asm_only = left - right
  jacoco_only = right - left
  global_asm_hits = asm_document.fetch('methodHits').flat_map { |hit| hit.fetch('canonicalKeys') }.to_set
  union = left | right
  comparisons[test] = {
    'both' => both.sort, 'asmOnly' => asm_only.sort, 'jacocoOnly' => jacoco_only.sort,
    'asmOnlyClassifications' => asm_only.sort.to_h { |method| [method, category(method)] },
    'jacocoOnlyClassifications' => jacoco_only.sort.to_h do |method|
      classification = global_asm_hits.include?(method) ? 'lifecycle/class filtering attribution difference' : category(method)
      [method, classification]
    end,
    'classes' => {
      'both' => both.map { |method| method.split('#', 2).first }.uniq.sort,
      'asmOnly' => asm_only.map { |method| method.split('#', 2).first }.uniq.sort,
      'jacocoOnly' => jacoco_only.map { |method| method.split('#', 2).first }.uniq.sort
    },
    'counts' => { 'both' => both.size, 'asmOnly' => asm_only.size, 'jacocoOnly' => jacoco_only.size },
    'jaccard' => union.empty? ? 1.0 : (both.size.to_f / union.size).round(6)
  }
end

fixed = 3.times.map { |index| asm_sets(File.join(input, "fixed-#{index + 1}-asm.json")).first }
reverse = asm_sets(File.join(input, 'reverse-1-asm.json')).first
reference = fixed.first
determinism = {
  'fixedRunsIdentical' => fixed.all? { |sets| sets == reference },
  'fixedDifferences' => fixed.each_with_index.drop(1).to_h do |sets, index|
    ["fixed-#{index + 1}", (reference.keys | sets.keys).to_h do |test|
      [test, { 'missing' => (reference.fetch(test, Set.new) - sets.fetch(test, Set.new)).sort,
               'extra' => (sets.fetch(test, Set.new) - reference.fetch(test, Set.new)).sort }]
    end]
  end,
  'reverseOrderIdentical' => reverse == reference,
  'reverseDifferences' => (reference.keys | reverse.keys).to_h do |test|
    [test, { 'missing' => (reference.fetch(test, Set.new) - reverse.fetch(test, Set.new)).sort,
             'extra' => (reverse.fetch(test, Set.new) - reference.fetch(test, Set.new)).sort }]
  end
}

all_asm = comparisons.values.flat_map { |value| value['both'] + value['asmOnly'] }.to_set
all_jacoco = comparisons.values.flat_map { |value| value['both'] + value['jacocoOnly'] }.to_set
result = {
  'schemaVersion' => 'stp-asm-round2-comparison-1', 'petClinicCommit' => manifest.fetch('petClinicCommit'),
  'tests' => manifest.fetch('tests'), 'comparisons' => comparisons,
  'globalUnion' => { 'both' => (all_asm & all_jacoco).sort, 'asmOnly' => (all_asm - all_jacoco).sort,
                     'jacocoOnly' => (all_jacoco - all_asm).sort },
  'determinism' => determinism,
  'metrics' => asm_sets(File.join(input, 'fixed-1-asm.json')).last,
  'runs' => manifest.fetch('runs')
}
File.write(output, JSON.pretty_generate(result) + "\n")
puts "wrote #{output}"
