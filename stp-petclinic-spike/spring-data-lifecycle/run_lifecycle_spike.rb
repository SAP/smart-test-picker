# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

PINNED = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
TESTS = {
  'owner' => 'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner',
  'vet' => 'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
}.freeze
DEFAULT_MODES = %w[lifecycle-snapshots-only lifecycle-late-bpp lifecycle-smart-singletons lifecycle-context-refreshed].freeze

root = File.expand_path('../..', __dir__)
petclinic = File.expand_path(ENV.fetch('PETCLINIC_DIR', File.join(root, '..', 'spring-petclinic-asm')))
jar = File.expand_path(ENV.fetch('CONFIRMATION_JAR', File.join(root,
  'stp-spring-data-observability-spike/build/libs/spring-data-petclinic-confirmation.jar')))
raw = File.join(__dir__, 'raw')
FileUtils.mkdir_p(raw)

head, status = Open3.capture2('git', '-C', petclinic, 'rev-parse', 'HEAD')
abort "PetClinic must be pinned at #{PINNED}" unless status.success? && head.strip == PINNED

runs = []
modes = ENV.fetch('LIFECYCLE_MODES', DEFAULT_MODES.join(',')).split(',')
modes.each do |mode|
  TESTS.each do |short, selector|
    name = "#{short}-#{mode.delete_prefix('lifecycle-')}"
    json = File.join(raw, "#{name}.json")
    log = File.join(raw, "#{name}.log")
    command = [File.join(petclinic, 'mvnw'), "-Dmaven.repo.local=#{ENV.fetch('MAVEN_REPO_LOCAL', '/tmp/stp-petclinic-m2')}",
      '-o', "-Dtest=#{selector}", "-Dmaven.test.additionalClasspath=#{jar}",
      "-Dstp.confirmation.mode=#{mode}", "-Dstp.lifecycle.output=#{json}", 'surefire:test']
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    text, run_status = Open3.capture2e({'MAVEN_USER_HOME' => ENV.fetch('MAVEN_USER_HOME', '/tmp/stp-petclinic-maven-home')},
      *command, chdir: petclinic)
    duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
    File.write(log, text) unless run_status.success?
    abort "#{name} produced no lifecycle JSON" unless File.file?(json)
    runs << {'name' => name, 'mode' => mode, 'test' => short, 'passed' => run_status.success?,
      'durationSeconds' => duration.round(3), 'output' => File.basename(json)}
    puts "#{name}: #{run_status.success? ? 'PASS' : 'FAIL'} #{duration.round(3)}s"
  end
end

File.write(File.join(raw, 'runs.json'), JSON.pretty_generate({'petClinicCommit' => PINNED, 'runs' => runs}) + "\n")
