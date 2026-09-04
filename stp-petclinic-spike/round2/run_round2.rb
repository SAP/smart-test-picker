# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

PINNED_COMMIT = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
TESTS = [
  'org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess',
  'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner',
  'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
].freeze

petclinic = File.expand_path(ENV.fetch('PETCLINIC_DIR'))
agent = File.expand_path(ENV.fetch('STP_AGENT_JAR'))
jacoco_agent = File.expand_path(ENV.fetch('JACOCO_AGENT_JAR'))
output = File.expand_path(ENV.fetch('ROUND2_OUTPUT_DIR', File.join(__dir__, 'raw')))
maven_repo = File.expand_path(ENV.fetch('MAVEN_REPO_LOCAL', File.join(output, 'm2-repository')))
maven_home = File.expand_path(ENV.fetch('MAVEN_USER_HOME', File.join(output, 'maven-home')))

head, status = Open3.capture2('git', '-C', petclinic, 'rev-parse', 'HEAD')
abort "PetClinic must be at #{PINNED_COMMIT}" unless status.success? && head.strip == PINNED_COMMIT
abort 'STP agent missing' unless File.file?(agent)
abort 'JaCoCo agent missing' unless File.file?(jacoco_agent)
FileUtils.mkdir_p([output, maven_repo, maven_home])

def invoke(petclinic, maven_home, command, log)
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  text, status = Open3.capture2e({ 'MAVEN_USER_HOME' => maven_home }, *command, chdir: petclinic)
  File.write(log, text)
  abort "command failed; see #{log}" unless status.success?
  Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
end

common = ['./mvnw', "-Dmaven.repo.local=#{maven_repo}", '-DskipTests', 'test-compile']
invoke(petclinic, maven_home, common, File.join(output, 'compile.log'))
records = []

run = lambda do |name, selector, asm:, jacoco:, order: nil|
  stp_file = File.join(output, "#{name}-asm.json")
  exec_file = File.join(petclinic, 'target', 'jacoco.exec')
  FileUtils.rm_f([stp_file, exec_file])
  agents = []
  agents << "-javaagent:#{jacoco_agent}=destfile=#{exec_file}" if jacoco
  if asm
    config = "output=#{stp_file};includes=org.springframework.samples.petclinic.;runId=#{name};debug=false;instrumentation=on"
    agents << "-javaagent:#{agent}=#{config}"
  end
  command = ['./mvnw', "-Dmaven.repo.local=#{maven_repo}", "-Dtest=#{selector}",
             "-DargLine=#{agents.join(' ')}"]
  command << "-Dsurefire.runOrder=#{order}" if order
  command << 'surefire:test'
  duration = invoke(petclinic, maven_home, command, File.join(output, "#{name}.log"))
  abort "#{name}: ASM output missing" if asm && !File.size?(stp_file)
  if jacoco
    abort "#{name}: JaCoCo output missing" unless File.size?(exec_file)
    invoke(petclinic, maven_home,
           ['./mvnw', "-Dmaven.repo.local=#{maven_repo}", '-DskipTests', 'jacoco:report'],
           File.join(output, "#{name}-jacoco-report.log"))
    xml = File.join(petclinic, 'target', 'site', 'jacoco', 'jacoco.xml')
    abort "#{name}: JaCoCo XML missing" unless File.size?(xml)
    FileUtils.cp(xml, File.join(output, "#{name}-jacoco.xml"))
  end
  records << { 'name' => name, 'selector' => selector, 'asm' => asm, 'jacoco' => jacoco,
               'order' => order, 'durationSeconds' => duration.round(6),
               'asmBytes' => asm ? File.size(stp_file) : nil,
               'jacocoBytes' => jacoco ? File.size(exec_file) : nil }
  puts "#{name}: #{format('%.3f', duration)}s"
end

TESTS.each_with_index { |test, index| run.call("dual-#{index + 1}", test, asm: true, jacoco: true) }
3.times { |index| run.call("fixed-#{index + 1}", TESTS.join(','), asm: true, jacoco: false, order: 'alphabetical') }
run.call('reverse-1', TESTS.join(','), asm: true, jacoco: false, order: 'reversealphabetical')
3.times { |index| run.call("baseline-#{index + 1}", TESTS.join(','), asm: false, jacoco: false, order: 'alphabetical') }

File.write(File.join(output, 'run-manifest.json'), JSON.pretty_generate({
  'schemaVersion' => 'stp-asm-round2-runs-1', 'petClinicCommit' => PINNED_COMMIT,
  'javaVersion' => `java -version 2>&1`.lines.first.strip, 'tests' => TESTS, 'runs' => records
}) + "\n")
