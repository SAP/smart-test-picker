# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'
require 'fileutils'

output_dir, golden_path = ARGV
abort 'usage: validate_e2e.rb OUTPUT_DIR GOLDEN' unless output_dir && golden_path
names = %w[stp-only stp-repeat jacoco-before-stp stp-before-jacoco adapter-disabled]
documents = names.to_h { |name| [name, JSON.parse(File.read(File.join(output_dir, "#{name}.json")))] }

normalize = lambda do |document|
  runtime = document.fetch('runtimeEvents')
  {
    'schemaVersion' => runtime.fetch('schemaVersion'),
    'tests' => runtime.fetch('tests').to_h do |test|
      [test.fetch('testMethod'), {
        'testId' => test.fetch('testId'),
        'methods' => test.fetch('methods').map { |item| item.slice('method', 'evidenceSource', 'certainty', 'count') },
        'repositories' => test.fetch('repositories').map do |item|
          item.slice('repositoryKind', 'repositoryInterface', 'beanName', 'methodName', 'jvmDescriptor',
                     'domainType', 'outcome', 'evidenceSource', 'certainty', 'count')
        end,
        'lateEvents' => test.fetch('unattributedEvents').select { |item| item['reason'] == 'LATE_EVENT' }
      }]
    end,
    'startupEvents' => runtime.fetch('unattributedEvents')
  }
end

normalized = names.to_h { |name| [name, normalize.call(documents.fetch(name))] }
enabled = normalized.fetch('stp-only')
%w[stp-repeat jacoco-before-stp stp-before-jacoco].each do |name|
  abort "normalized dependency graph differs for #{name}" unless normalized.fetch(name) == enabled
end
abort 'adapter-disabled method graph changed' unless normalized.fetch('adapter-disabled')['tests'].transform_values { |v| v['methods'] } ==
  enabled['tests'].transform_values { |v| v['methods'] }
abort 'adapter-disabled mode emitted repository events' unless normalized.fetch('adapter-disabled')['tests'].values
  .all? { |value| value['repositories'].empty? }

tests = enabled.fetch('tests')
cached = tests.fetch('cachedSuccessfulGraph')
abort 'service ASM method missing' unless cached['methods'].any? { |item| item['method'].include?('SampleService#cachedLookupTwice()V') }
cached_repository = cached['repositories'].find { |item| item['methodName'] == 'findCachedByName' }
abort 'cached repository event count is not two' unless cached_repository && cached_repository['count'] == 2
abort 'cached repository descriptor/domain incorrect' unless cached_repository['jvmDescriptor'] ==
  '(Ljava/lang/String;)Ljava/util/List;' && cached_repository['domainType'] == 'example.springdatae2e.app.SampleEntity'
failed = tests.fetch('failedRepositoryPreservesThrowable')['repositories']
abort 'FAILED repository event missing' unless failed.one? { |item| item['methodName'] == 'failExactly' && item['outcome'] == 'FAILED' }
alpha = tests.fetch('sequentialAlpha')
beta = tests.fetch('sequentialBeta')
abort 'sequential repository contamination' if alpha['repositories'].any? { |item| item['methodName'] == 'findBeta' } ||
  beta['repositories'].any? { |item| item['methodName'] == 'findAlpha' }
abort 'late event found' unless tests.values.all? { |value| value['lateEvents'].empty? }
abort 'startup repository event entered first test' if cached['repositories'].any? { |item| item['methodName'] == 'save' }
abort 'startup event was not globally unattributed' unless enabled['startupEvents'].any? do |item|
  item['reason'] == 'NO_ACTIVE_TEST' && item['eventIdentity'].include?('#save(Ljava/lang/Object;)Ljava/lang/Object;')
end

documents.each do |name, document|
  abort "#{name}: agent error" unless document.fetch('agentErrors').empty?
  abort "#{name}: duplicate instrumentation" unless document.fetch('metrics').fetch('alreadyInstrumentedClasses').zero?
end
%w[jacoco-before-stp stp-before-jacoco].each do |name|
  path = File.join(output_dir, "#{name}-jacoco.exec")
  abort "#{name}: JaCoCo output missing" unless File.size?(path)
end

metrics = %w[stp-only stp-repeat jacoco-before-stp stp-before-jacoco].to_h do |name|
  [name, JSON.parse(File.read(File.join(output_dir, "#{name}-adapter-metrics.json")))]
end
abort 'adapter callback metrics unexpected' unless metrics.values.all? do |item|
  item['eventsRecorded'] == 6 && item['invocationsObserved'] == 6
end
abort 'underlying cache execution was not one' unless metrics.values.all? do |item|
  item['cachedUnderlyingExecutions'] == 1
end

golden = {
  'schemaVersion' => 'spring-data-e2e-normalized-1',
  'runtimeSchemaVersion' => 'spike-2',
  'tests' => enabled.fetch('tests'),
  'startupEvents' => enabled.fetch('startupEvents')
}
serialized = JSON.pretty_generate(golden) + "\n"
if File.exist?(golden_path)
  abort 'normalized golden differs' unless File.read(golden_path) == serialized
else
  FileUtils.mkdir_p(File.dirname(golden_path))
  File.write(golden_path, serialized)
end
puts 'Spring Data end-to-end fixture validation passed'
