# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

PINNED_COMMIT = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
ROOT = File.expand_path('../..', __dir__)
PETCLINIC = File.expand_path(ENV.fetch('PETCLINIC_DIR', '/private/tmp/stp-round2-petclinic'))
OUTPUT = File.expand_path(ENV.fetch('ROUND8_OUTPUT_DIR', File.join(__dir__, 'evidence')))
AGENT = File.expand_path(ENV.fetch('STP_AGENT_JAR', File.join(ROOT, 'stp-agent/build/libs/stp-agent-experimental.jar')))
ROUND5 = File.join(ROOT, 'stp-petclinic-spike', 'round5', 'evidence')

head, status = Open3.capture2('git', '-C', PETCLINIC, 'rev-parse', 'HEAD')
abort "PetClinic must be at #{PINNED_COMMIT}" unless status.success? && head.strip == PINNED_COMMIT
abort "missing agent: #{AGENT}" unless File.file?(AGENT)

inventory = JSON.parse(File.read(File.join(ROUND5, 'test-inventory.json')))
tests = inventory.fetch('asmMappingTests')
selectors = tests.map { |test| test.split('#', 2).first }.uniq.sort
FileUtils.mkdir_p(OUTPUT)
FileUtils.cp(File.join(ROUND5, 'jacoco-stp-map-original.json'), File.join(OUTPUT, 'reference-map.json'))
FileUtils.cp(File.join(ROUND5, 'jacoco-descriptor-map.json'), File.join(OUTPUT, 'reference-method-map.json'))
FileUtils.cp(File.join(ROUND5, 'test-inventory.json'), File.join(OUTPUT, 'reference-inventory.json'))

runs = []
2.times do |index|
  name = "asm-run-#{index + 1}"
  raw = File.join(OUTPUT, "#{name}-map.json")
  log = File.join(OUTPUT, "#{name}-runtime.log")
  reports = File.join(OUTPUT, "#{name}-surefire-reports")
  FileUtils.rm_f(raw)
  FileUtils.rm_rf(reports)
  FileUtils.rm_rf(File.join(PETCLINIC, 'target', 'surefire-reports'))
  config = "output=#{raw};includes=org.springframework.samples.petclinic.;runId=round8-#{name};debug=true;instrumentation=on"
  command = ['./mvnw', '-o', "-Dtest=#{selectors.join(',')}",
             "-DargLine=-javaagent:#{AGENT}=#{config}", '-Dsurefire.runOrder=alphabetical', 'surefire:test']
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  text, result = Open3.capture2e(*command, chdir: PETCLINIC)
  duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  File.write(log, text)
  FileUtils.cp_r(File.join(PETCLINIC, 'target', 'surefire-reports'), reports) if Dir.exist?(File.join(PETCLINIC, 'target', 'surefire-reports'))
  abort "#{name} failed; see #{log}" unless result.success? && File.size?(raw)
  runs << { 'name' => name, 'durationSeconds' => duration.round(6), 'map' => File.basename(raw),
            'runtimeLog' => File.basename(log), 'surefireReports' => File.basename(reports) }
  puts "#{name}: #{format('%.3f', duration)}s"
end

manifest = {
  'schemaVersion' => 'stp-asm-round8-runs-1',
  'petClinicCommit' => PINNED_COMMIT,
  'inventorySource' => 'round5/evidence/test-inventory.json#asmMappingTests',
  'inventorySize' => tests.size,
  'selectors' => selectors,
  'agent' => AGENT,
  'configuration' => { 'includes' => 'org.springframework.samples.petclinic.', 'debug' => true,
                       'instrumentation' => 'on', 'surefireRunOrder' => 'alphabetical' },
  'javaVersion' => `java -version 2>&1`.lines.first.strip,
  'runs' => runs
}
File.write(File.join(OUTPUT, 'run-manifest.json'), JSON.pretty_generate(manifest) + "\n")
