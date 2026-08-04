# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

PINNED = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
OWNER = 'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner'
VET = 'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'

root = File.expand_path('../..', __dir__)
petclinic = File.expand_path(ENV.fetch('PETCLINIC_DIR', File.join(root, '..', 'spring-petclinic-asm')))

unless Dir.exist?(petclinic)
  abort "git clone failed" unless system('git', 'clone', 'https://github.com/spring-projects/spring-petclinic.git', petclinic)
end

head, status = Open3.capture2('git', '-C', petclinic, 'rev-parse', 'HEAD')
unless status.success? && head.strip == PINNED
  dirty, dirty_status = Open3.capture2('git', '-C', petclinic, 'status', '--porcelain')
  abort "PetClinic checkout has local changes; refusing to switch revisions" unless dirty_status.success? && dirty.empty?
  unless system('git', '-C', petclinic, 'checkout', '--detach', PINNED)
    abort "PetClinic fetch failed" unless system('git', '-C', petclinic, 'fetch', 'origin', PINNED)
    abort "PetClinic checkout failed" unless system('git', '-C', petclinic, 'checkout', '--detach', PINNED)
  end
end

gradle = File.join(root, 'gradlew')
build_tasks = %w[:stp-agent:agentJar :stp-spring-data-adapter:jar
  :stp-spring-data-observability-spike:petclinicConfirmationJar
  :stp-spring-data-e2e-fixture:copyAcceptanceJacocoAgent]
abort "STP acceptance artifacts failed to build" unless system({'GRADLE_USER_HOME' => ENV.fetch('GRADLE_USER_HOME', '/tmp/stp-gradle-home')},
  gradle, *build_tasks, chdir: root)

agent = File.expand_path(ENV.fetch('STP_AGENT_JAR', File.join(root, 'stp-agent/build/libs/stp-agent-experimental.jar')))
adapter = File.expand_path(ENV.fetch('STP_ADAPTER_JAR', File.join(root, 'stp-spring-data-adapter/build/libs/stp-spring-data-adapter.jar')))
diagnostics = File.expand_path(ENV.fetch('CONFIRMATION_JAR', File.join(root,
  'stp-spring-data-observability-spike/build/libs/spring-data-petclinic-confirmation.jar')))
jacoco = File.expand_path(ENV.fetch('JACOCO_AGENT_JAR',
  File.join(root, 'stp-spring-data-e2e-fixture/build/acceptance-dependencies/jacocoagent.jar')))
raw = File.join(__dir__, 'raw')
FileUtils.mkdir_p(raw)

def run_case(name:, selector:, raw:, petclinic:, agent:, adapter:, diagnostics:, jacoco:, enabled:, order: nil,
    agent_order: 'jacoco-first')
  stp = File.join(raw, "#{name}-stp.json")
  jacoco_out = File.join(raw, "#{name}-jacoco.exec")
  audit = File.join(raw, "#{name}-audit.json")
  inner = File.join(raw, "#{name}-inner.json")
  [stp, jacoco_out, audit, inner].each { |file| FileUtils.rm_f(file) }
  stp_arg = "-javaagent:#{agent}=output=#{stp};includes=org.springframework.samples.petclinic.;runId=#{name};debug=false;instrumentation=on"
  jacoco_arg = "-javaagent:#{jacoco}=destfile=#{jacoco_out}"
  arg_line = agent_order == 'stp-first' ? "#{stp_arg} #{jacoco_arg}" : "#{jacoco_arg} #{stp_arg}"
  classpath = enabled ? "#{adapter},#{diagnostics}" : adapter
  command = [File.join(petclinic, 'mvnw'), "-Dmaven.repo.local=#{ENV.fetch('MAVEN_REPO_LOCAL', '/tmp/stp-petclinic-m2')}",
    "-Dtest=#{selector}", "-Dmaven.test.additionalClasspath=#{classpath}",
    "-Dstp.spring-data.enabled=#{enabled}", "-DargLine=#{arg_line}"]
  if enabled
    command.concat(["-Dstp.confirmation.mode=acceptance", "-Dstp.confirmation.output=#{inner}",
      "-Dstp.acceptance.audit.output=#{audit}"])
  end
  command << "-Dsurefire.runOrder=#{order}" if order
  command << 'surefire:test'
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  text, result = Open3.capture2e({'MAVEN_USER_HOME' => ENV.fetch('MAVEN_USER_HOME', '/tmp/stp-petclinic-maven-home')},
    *command, chdir: petclinic)
  duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  File.write(File.join(raw, "#{name}.log"), text) unless result.success?
  abort "#{name} failed" unless result.success?
  abort "#{name} produced no STP output" unless File.size?(stp)
  abort "#{name} produced no JaCoCo output" unless File.size?(jacoco_out)
  abort "#{name} produced no audit output" if enabled && !File.size?(audit)
  abort "#{name} produced no inner-listener output" if enabled && !File.size?(inner)
  puts "#{name}: PASS #{duration.round(3)}s"
  {'name' => name, 'selector' => selector, 'enabled' => enabled, 'order' => order,
   'agentOrder' => agent_order, 'durationSeconds' => duration.round(3), 'stp' => File.basename(stp),
   'audit' => enabled ? File.basename(audit) : nil, 'inner' => enabled ? File.basename(inner) : nil,
   'jacocoBytes' => File.size(jacoco_out), 'stpBytes' => File.size(stp)}
end

runs = []
runs << run_case(name: 'owner-individual', selector: OWNER, raw: raw, petclinic: petclinic, agent: agent,
  adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: true)
runs << run_case(name: 'vet-individual', selector: VET, raw: raw, petclinic: petclinic, agent: agent,
  adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: true)
runs << run_case(name: 'combined-alphabetical', selector: "#{OWNER},#{VET}", raw: raw, petclinic: petclinic,
  agent: agent, adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: true, order: 'alphabetical')
runs << run_case(name: 'combined-reverse', selector: "#{OWNER},#{VET}", raw: raw, petclinic: petclinic,
  agent: agent, adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: true,
  order: 'reversealphabetical')
runs << run_case(name: 'combined-stp-first', selector: "#{OWNER},#{VET}", raw: raw, petclinic: petclinic,
  agent: agent, adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: true,
  order: 'alphabetical', agent_order: 'stp-first')
runs << run_case(name: 'combined-disabled', selector: "#{OWNER},#{VET}", raw: raw, petclinic: petclinic,
  agent: agent, adapter: adapter, diagnostics: diagnostics, jacoco: jacoco, enabled: false,
  order: 'alphabetical')
File.write(File.join(raw, 'runs.json'), JSON.pretty_generate({'petClinicCommit' => PINNED, 'runs' => runs}) + "\n")

abort 'acceptance normalization failed' unless system('ruby', File.join(__dir__, 'normalize_acceptance.rb'))
abort 'acceptance validation failed' unless system('ruby', File.join(__dir__, 'validate_acceptance.rb'))
