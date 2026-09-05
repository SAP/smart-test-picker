# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'
require 'rexml/document'

PINNED_COMMIT = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
ROOT = File.expand_path('../..', __dir__)
PETCLINIC = File.expand_path(ENV.fetch('PETCLINIC_DIR', '/private/tmp/stp-round2-petclinic'))
OUTPUT = File.expand_path(ENV.fetch('ROUND5_OUTPUT_DIR', File.join(__dir__, 'evidence')))
AGENT = File.expand_path(ENV.fetch('STP_AGENT_JAR', File.join(ROOT, 'stp-agent/build/libs/stp-agent-experimental.jar')))
LISTENER = File.expand_path(ENV.fetch('STP_LISTENER_JAR', File.join(ROOT, 'smart-test-picker-core/build/libs/smart-test-picker-core-0.1.0.jar')))
JACOCO = File.expand_path(ENV.fetch('JACOCO_AGENT_JAR', File.join(Dir.home, '.m2/repository/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar')))
PLUGIN = 'com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0'

head, status = Open3.capture2('git', '-C', PETCLINIC, 'rev-parse', 'HEAD')
abort "PetClinic must be at #{PINNED_COMMIT}" unless status.success? && head.strip == PINNED_COMMIT
[AGENT, LISTENER, JACOCO].each { |path| abort "missing required artifact: #{path}" unless File.file?(path) }
FileUtils.mkdir_p(OUTPUT)

def invoke(name, command, env = {})
  log = File.join(OUTPUT, "#{name}.log")
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  text, status = Open3.capture2e(env, *command, chdir: PETCLINIC)
  duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  File.write(log, text)
  abort "#{name} failed; see #{log}" unless status.success?
  puts "#{name}: #{format('%.3f', duration)}s"
  duration
end

def invoke_allow_failure(name, command, env = {})
  log = File.join(OUTPUT, "#{name}.log")
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  text, status = Open3.capture2e(env, *command, chdir: PETCLINIC)
  duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  File.write(log, text)
  puts "#{name}: #{format('%.3f', duration)}s (exit #{status.exitstatus})"
  [duration, status.success?]
end

def inventory_from_reports(report_dir)
  executed = []
  excluded = []
  Dir[File.join(report_dir, 'TEST-*.xml')].sort.each do |path|
    document = REXML::Document.new(File.read(path))
    suite = document.root
    suite.elements.each('testcase') do |testcase|
      identity = "#{testcase.attributes['classname']}##{testcase.attributes['name']}"
      if testcase.elements['skipped']
        excluded << { 'test' => identity, 'reason' => testcase.elements['skipped'].attributes['message'] || 'skipped' }
      else
        executed << identity
      end
    end
  end
  # Postgres uses a @BeforeAll assumption, so Surefire emits an empty suite rather than skipped testcases.
  excluded.concat(%w[findAll ownerDetails].map do |method|
    { 'test' => "org.springframework.samples.petclinic.PostgresIntegrationTests##{method}",
      'reason' => 'Docker unavailable; class-level @BeforeAll assumption aborted the container' }
  end)
  { 'schemaVersion' => 'stp-asm-round5-inventory-1', 'petClinicCommit' => PINNED_COMMIT,
    'executedTests' => executed.sort, 'excludedTests' => excluded.sort_by { |entry| entry['test'] } }
end

FileUtils.rm_rf(File.join(PETCLINIC, 'target', 'surefire-reports'))
baseline_time = invoke('inventory-baseline', ['./mvnw', '-o', '-Dsurefire.runOrder=alphabetical', 'test'])
inventory = inventory_from_reports(File.join(PETCLINIC, 'target', 'surefire-reports'))
environment_tests = inventory.fetch('executedTests')
environment_classes = environment_tests.map { |test| test.split('#', 2).first }.uniq.sort

records = [{ 'name' => 'inventory-baseline', 'kind' => 'no-agent', 'order' => 'alphabetical',
             'durationSeconds' => baseline_time.round(6) }]

# Prove whether the current collector can load the complete otherwise-executable inventory, then
# isolate failures by test class. This is validation evidence, not a collector workaround.
full_destination = File.join(OUTPUT, 'asm-full-inventory-failure-raw.json')
full_config = "output=#{full_destination};includes=org.springframework.samples.petclinic.;runId=asm-full-inventory;debug=false;instrumentation=on"
full_time, full_success = invoke_allow_failure('asm-full-inventory', ['./mvnw', '-o',
  "-Dtest=#{environment_classes.join(',')}", "-DargLine=-javaagent:#{AGENT}=#{full_config}",
  '-Dsurefire.runOrder=alphabetical', 'surefire:test'])
records << { 'name' => 'asm-full-inventory', 'kind' => 'asm-safety-probe', 'order' => 'alphabetical',
             'success' => full_success, 'durationSeconds' => full_time.round(6),
             'outputBytes' => File.size?(full_destination) || 0 }

compatible_classes = []
probe_results = environment_classes.map do |klass|
  slug = klass.gsub(/[^A-Za-z0-9]+/, '-')
  destination = File.join(OUTPUT, "probe-#{slug}.json")
  config = "output=#{destination};includes=org.springframework.samples.petclinic.;runId=probe-#{slug};debug=false;instrumentation=on"
  duration, success = invoke_allow_failure("probe-#{slug}", ['./mvnw', '-o', "-Dtest=#{klass}",
    "-DargLine=-javaagent:#{AGENT}=#{config}", '-Dsurefire.runOrder=alphabetical', 'surefire:test'])
  compatible_classes << klass if success
  FileUtils.rm_f(destination)
  FileUtils.rm_f(File.join(OUTPUT, "probe-#{slug}.log")) if success
  { 'class' => klass, 'success' => success, 'durationSeconds' => duration.round(6),
    'failureLog' => success ? nil : "probe-#{slug}.log" }
end
compatible_tests = environment_tests.select { |test| compatible_classes.include?(test.split('#', 2).first) }
asm_excluded = environment_tests.reject { |test| compatible_tests.include?(test) }.map do |test|
  { 'test' => test, 'reason' => 'Current ASM executor call-site transformation causes JVM VerifyError while loading Spring AsyncTaskExecutor' }
end
inventory['asmMappingTests'] = compatible_tests
inventory['asmExcludedTests'] = asm_excluded
File.write(File.join(OUTPUT, 'test-inventory.json'), JSON.pretty_generate(inventory) + "\n")
File.write(File.join(OUTPUT, 'asm-compatibility-probes.json'), JSON.pretty_generate({
  'schemaVersion' => 'stp-asm-round5-compatibility-probes-1', 'fullInventorySuccess' => full_success,
  'classResults' => probe_results
}) + "\n")
selectors = compatible_classes.join(',')
mapped_baseline_time = invoke('mapped-inventory-baseline', ['./mvnw', '-o', "-Dtest=#{selectors}",
  '-DargLine=', '-Dsurefire.runOrder=alphabetical', 'surefire:test'])
records << { 'name' => 'mapped-inventory-baseline', 'kind' => 'no-agent', 'order' => 'alphabetical',
             'durationSeconds' => mapped_baseline_time.round(6) }

run_asm = lambda do |name, order|
  destination = File.join(OUTPUT, "#{name}-raw.json")
  FileUtils.rm_f(destination)
  config = "output=#{destination};includes=org.springframework.samples.petclinic.;runId=#{name};debug=false;instrumentation=on"
  duration = invoke(name, ['./mvnw', '-o', "-Dtest=#{selectors}", "-DargLine=-javaagent:#{AGENT}=#{config}",
                           "-Dsurefire.runOrder=#{order}", 'surefire:test'])
  abort "#{name}: agent output missing" unless File.size?(destination)
  records << { 'name' => name, 'kind' => 'asm', 'order' => order,
               'durationSeconds' => duration.round(6), 'outputBytes' => File.size(destination) }
end

3.times { |index| run_asm.call("asm-fixed-#{index + 1}", 'alphabetical') }
run_asm.call('asm-reverse-1', 'reversealphabetical')

jacoco_exec = File.join(PETCLINIC, 'target', 'jacoco')
jacoco_xml = File.join(PETCLINIC, 'target', 'jacoco-xml')
retained_jacoco_xml = File.join(OUTPUT, 'jacoco-xml')
FileUtils.rm_rf([jacoco_exec, jacoco_xml, retained_jacoco_xml])
FileUtils.mkdir_p(jacoco_exec)
jacoco_args = "destfile=#{File.join(jacoco_exec, 'test.exec')},append=false"
jacoco_time = invoke('jacoco-stp', ['./mvnw', '-o', "-Dtest=#{selectors}",
  "-DargLine=-javaagent:#{JACOCO}=#{jacoco_args} -Dstp.exec.dir=#{jacoco_exec}",
  "-Dmaven.test.additionalClasspath=#{LISTENER}", '-Dsurefire.runOrder=alphabetical', 'surefire:test'])
invoke('jacoco-reports', ['./mvnw', '-o', '-DskipTests', "#{PLUGIN}:generate-reports"])
FileUtils.cp_r(jacoco_xml, retained_jacoco_xml)
original_map = File.join(OUTPUT, 'jacoco-stp-map-original.json')
generated_map = File.join(PETCLINIC, 'target', 'test-coverage-map.json')
FileUtils.rm_f(generated_map)
invoke('jacoco-map', ['./mvnw', '-o', '-DskipTests', '-DsmartTestPicker.baseBranch=main',
  "#{PLUGIN}:generate-coverage-map"])
FileUtils.cp(generated_map, original_map)
records << { 'name' => 'jacoco-stp', 'kind' => 'jacoco', 'order' => 'alphabetical',
             'durationSeconds' => jacoco_time.round(6), 'outputBytes' => File.size(original_map) }

manifest = { 'schemaVersion' => 'stp-asm-round5-runs-1', 'petClinicCommit' => PINNED_COMMIT,
  'javaVersion' => `java -version 2>&1`.lines.first.strip, 'selectors' => selectors.split(','), 'runs' => records }
File.write(File.join(OUTPUT, 'run-manifest.json'), JSON.pretty_generate(manifest) + "\n")
