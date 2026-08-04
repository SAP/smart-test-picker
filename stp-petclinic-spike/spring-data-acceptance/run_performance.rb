# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

OWNER = 'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner'
VET = 'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
root = File.expand_path('../..', __dir__)
petclinic = File.expand_path(ENV.fetch('PETCLINIC_DIR', File.join(root, '..', 'spring-petclinic-asm')))
agent = File.join(root, 'stp-agent/build/libs/stp-agent-experimental.jar')
adapter = File.join(root, 'stp-spring-data-adapter/build/libs/stp-spring-data-adapter.jar')
jacoco = ENV.fetch('JACOCO_AGENT_JAR',
  '/tmp/stp-petclinic-m2/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar')
raw = File.join(__dir__, 'performance-raw')
FileUtils.mkdir_p(raw)

configurations = {
  'baseline' => [nil, false],
  'asm-only' => ['on', false],
  'adapter-only' => ['off', true],
  'asm-plus-adapter' => ['on', true]
}
records = []
configurations.each do |configuration, (instrumentation, enabled)|
  6.times do |index|
    label = index.zero? ? 'warmup' : index.to_s
    name = "#{configuration}-#{label}"
    jacoco_out = File.join(raw, "#{name}-jacoco.exec")
    stp_out = File.join(raw, "#{name}-stp.json")
    FileUtils.rm_f(jacoco_out)
    FileUtils.rm_f(stp_out)
    arg_line = "-javaagent:#{jacoco}=destfile=#{jacoco_out}"
    if instrumentation
      arg_line += " -javaagent:#{agent}=output=#{stp_out};includes=org.springframework.samples.petclinic.;runId=#{name};debug=false;instrumentation=#{instrumentation}"
    end
    command = [File.join(petclinic, 'mvnw'), "-Dmaven.repo.local=#{ENV.fetch('MAVEN_REPO_LOCAL', '/tmp/stp-petclinic-m2')}",
      '-o', "-Dtest=#{OWNER},#{VET}", '-Dsurefire.runOrder=alphabetical', "-DargLine=#{arg_line}",
      "-Dstp.spring-data.enabled=#{enabled}"]
    command << "-Dmaven.test.additionalClasspath=#{adapter}" if enabled
    command << 'surefire:test'
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    text, status = Open3.capture2e({'MAVEN_USER_HOME' => ENV.fetch('MAVEN_USER_HOME', '/tmp/stp-petclinic-maven-home')},
      *command, chdir: petclinic)
    duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
    File.write(File.join(raw, "#{name}.log"), text) unless status.success?
    abort "#{name} failed" unless status.success?
    record = {'configuration' => configuration, 'sample' => label, 'measured' => !index.zero?,
      'durationSeconds' => duration.round(3)}
    if instrumentation
      data = JSON.parse(File.read(stp_out))
      metrics = data.fetch('metrics')
      record['transformerNanos'] = metrics.fetch('transformerTotalNanos')
      record['classesTransformed'] = metrics.fetch('classesTransformed')
      record['methodsInstrumented'] = metrics.fetch('methodsInstrumented')
      record['rawMethodHits'] = metrics.fetch('rawMethodHits')
      record['uniqueMethodHits'] = metrics.fetch('uniqueMethodHits')
      record['repositoryFacts'] = data.fetch('runtimeEvents').fetch('tests')
        .sum { |test| test.fetch('repositories').sum { |fact| fact.fetch('count') } }
      record['jsonBytes'] = File.size(stp_out)
    end
    records << record
    puts "#{name}: #{duration.round(3)}s"
  end
end

def percentile(values, fraction)
  sorted = values.sort
  sorted[[(fraction * sorted.length).ceil - 1, 0].max]
end

summary = configurations.keys.to_h do |configuration|
  samples = records.select { |record| record['configuration'] == configuration && record['measured'] }
    .map { |record| record.fetch('durationSeconds') }
  [configuration, {'samplesSeconds' => samples, 'medianSeconds' => percentile(samples, 0.5),
    'p95Seconds' => percentile(samples, 0.95)}]
end
baseline = summary.fetch('baseline').fetch('medianSeconds')
summary.each_value do |value|
  value['medianOverheadPercent'] = (((value.fetch('medianSeconds') / baseline) - 1) * 100).round(2)
end
File.write(File.join(__dir__, 'performance-summary.json'), JSON.pretty_generate({
  'schemaVersion' => 'spring-data-petclinic-performance-1', 'summary' => summary,
  'runs' => records
}) + "\n")
