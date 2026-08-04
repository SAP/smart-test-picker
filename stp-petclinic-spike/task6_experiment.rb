# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'fileutils'
require 'json'
require 'open3'

PETCLINIC_COMMIT = '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
CONTROLLER = 'org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess'
REPOSITORY = 'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner'
INTEGRATION = 'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
ALL_TESTS = [CONTROLLER, REPOSITORY, INTEGRATION].join(',')
KNOWN_TESTS = [
  'org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess',
  'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner',
  'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
].freeze

petclinic = File.expand_path(ENV.fetch('PETCLINIC_DIR'))
agent_jar = File.expand_path(ENV.fetch('STP_AGENT_JAR'))
jacoco_agent = File.expand_path(ENV.fetch('JACOCO_AGENT_JAR'))
output_dir = File.expand_path(ENV.fetch('TASK6_OUTPUT_DIR'))
maven_home = ENV.fetch('MAVEN_USER_HOME')
maven_repo = ENV.fetch('MAVEN_REPO_LOCAL')

head, git_status = Open3.capture2('git', '-C', petclinic, 'rev-parse', 'HEAD')
unless git_status.success? && head.strip == PETCLINIC_COMMIT
  abort "PetClinic must be checked out at #{PETCLINIC_COMMIT}"
end

FileUtils.mkdir_p(output_dir)
records = []

def percentile(samples, fraction)
  sorted = samples.sort
  sorted[[(fraction * sorted.length).ceil - 1, 0].max]
end

def agent_data(record)
  path = record['stpOutput']
  path && File.file?(path) ? JSON.parse(File.read(path)) : nil
end

def dependency_sets(data)
  data.fetch('runtimeEvents').fetch('tests').to_h do |test|
    readable = "#{test['testClass']}##{test['testMethod']}"
    [readable, test.fetch('methods').map { |method| method.fetch('method') }.sort]
  end
end

run_case = lambda do |name:, selector:, mode:, measured:, order: nil, agent_order: 'jacoco-first'|
  stp_output = mode == 'baseline' ? nil : File.join(output_dir, "#{name}.json")
  jacoco_output = File.join(output_dir, "#{name}-jacoco.exec")
  FileUtils.rm_f(stp_output) if stp_output
  FileUtils.rm_f(jacoco_output)

  jacoco = "-javaagent:#{jacoco_agent}=destfile=#{jacoco_output}"
  stp = if stp_output
    args = "output=#{stp_output};includes=org.springframework.samples.petclinic.;runId=#{name};debug=false;instrumentation=#{mode}"
    "-javaagent:#{agent_jar}=#{args}"
  end
  arg_line = if stp.nil?
    jacoco
  elsif agent_order == 'stp-first'
    "#{stp} #{jacoco}"
  else
    "#{jacoco} #{stp}"
  end

  command = [
    './mvnw', "-Dmaven.repo.local=#{maven_repo}", '-o', "-Dtest=#{selector}",
    "-DargLine=#{arg_line}"
  ]
  command << "-Dsurefire.runOrder=#{order}" if order
  command << 'surefire:test'

  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  stdout, status = Open3.capture2e({ 'MAVEN_USER_HOME' => maven_home }, *command, chdir: petclinic)
  duration = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  unless status.success?
    File.write(File.join(output_dir, "#{name}-failure.log"), stdout)
    abort "#{name} failed; see #{name}-failure.log"
  end
  abort "#{name} did not run the requested tests" unless stdout.include?('Failures: 0, Errors: 0')
  abort "#{name} did not produce JaCoCo data" unless File.size?(jacoco_output)
  abort "#{name} did not produce STP output" if stp_output && !File.size?(stp_output)

  record = {
    'name' => name,
    'configuration' => mode,
    'measured' => measured,
    'order' => order,
    'agentOrder' => agent_order,
    'durationSeconds' => duration.round(6),
    'jacocoBytes' => File.size(jacoco_output),
    'stpOutput' => stp_output,
    'stpOutputBytes' => stp_output ? File.size(stp_output) : nil
  }
  if (data = agent_data(record))
    metrics = data.fetch('metrics')
    record['metrics'] = metrics.slice('transformerTotalNanos', 'classesTransformed', 'methodsInstrumented',
                                      'rawMethodHits', 'uniqueMethodHits')
    global = data.fetch('runtimeEvents').fetch('unattributedEvents')
    tests = data.fetch('runtimeEvents').fetch('tests')
    record['unattributedEvents'] = global.sum { |event| event.fetch('count') }
    record['lateEvents'] = global.select { |event| event['reason'] == 'LATE_EVENT' }.sum { |event| event['count'] } +
      tests.sum { |test| test.fetch('unattributedEvents').select { |event| event['reason'] == 'LATE_EVENT' }
                       .sum { |event| event.fetch('count') } }
  end
  records << record
  puts "#{name}: #{format('%.3f', duration)}s"
  record
end

# Individual attribution experiments.
controller = run_case.call(name: 'individual-controller', selector: CONTROLLER, mode: 'on', measured: false)
repository = run_case.call(name: 'individual-repository', selector: REPOSITORY, mode: 'on', measured: false)
integration = run_case.call(name: 'individual-integration', selector: INTEGRATION, mode: 'on', measured: false)

# Deterministic test orders and both Java-agent orders.
alphabetical = run_case.call(name: 'combined-alphabetical', selector: ALL_TESTS, mode: 'on', measured: false,
                             order: 'alphabetical')
reverse = run_case.call(name: 'combined-reverse', selector: ALL_TESTS, mode: 'on', measured: false,
                        order: 'reversealphabetical')
stp_first = run_case.call(name: 'combined-stp-first', selector: ALL_TESTS, mode: 'on', measured: false,
                          order: 'alphabetical', agent_order: 'stp-first')

# One warm-up plus five measured fresh Maven/Surefire JVMs per configuration.
%w[baseline off on].each do |mode|
  run_case.call(name: "perf-#{mode}-warmup", selector: ALL_TESTS, mode: mode, measured: false,
                order: 'alphabetical')
  5.times do |index|
    run_case.call(name: "perf-#{mode}-#{index + 1}", selector: ALL_TESTS, mode: mode, measured: true,
                  order: 'alphabetical')
  end
end

# Contract validation.
controller_methods = dependency_sets(agent_data(controller)).fetch(KNOWN_TESTS[0])
%w[findOwner setAllowedFields processCreationForm].each do |method|
  abort "missing controller method #{method}" unless controller_methods.any? { |key| key.include?("OwnerController##{method}(") }
end
abort 'controller output contains a generated repository implementation' if controller_methods.any? { |key| key.include?('Repository$') }

repository_methods = dependency_sets(agent_data(repository)).fetch(KNOWN_TESTS[1])
abort 'repository test invented a repository method body' if repository_methods.any? { |key| key.include?('Repository#') || key.include?('Repository$') }

integration_methods = dependency_sets(agent_data(integration)).fetch(KNOWN_TESTS[2])
abort 'integration test contains an Owner controller' if integration_methods.any? { |key| key.include?('OwnerController#') }

[controller, repository, integration, alphabetical, reverse, stp_first].each do |record|
  data = agent_data(record)
  catalog = data.fetch('methodCatalog').to_h { |entry| [entry.fetch('methodId'), entry.fetch('canonicalKeys')] }
  data.fetch('runtimeEvents').fetch('tests').each do |test|
    readable = "#{test['testClass']}##{test['testMethod']}"
    abort "unknown attributed test #{readable}" unless KNOWN_TESTS.include?(readable)
    test.fetch('methods').each do |method|
      abort "method ID missing from catalog" unless catalog.fetch(method.fetch('methodId')).include?(method.fetch('method'))
    end
  end
  attributed = data.fetch('runtimeEvents').fetch('tests').flat_map { |test| test.fetch('methods') }
  abort "#{record['name']} attributed startup configuration to a leaf" if attributed.any? do |method|
    method.fetch('method').include?('.system.WebConfiguration#') ||
      method.fetch('method').include?('.system.CacheConfiguration#')
  end
  catalog_keys = data.fetch('methodCatalog').flat_map { |entry| entry.fetch('canonicalKeys') }
  abort "#{record['name']} transformed a generated class" if catalog_keys
    .any? { |key| key.include?('$$') || key.include?('$MockitoMock$') || key.include?('$HibernateProxy') ||
      key.include?('$HibernateInstantiator') }
  abort "#{record['name']} injected duplicate hooks" unless data.fetch('metrics').fetch('alreadyInstrumentedClasses').zero?
end

alpha_sets = dependency_sets(agent_data(alphabetical))
reverse_sets = dependency_sets(agent_data(reverse))
abort 'dependency sets changed with test order' unless alpha_sets == reverse_sets
abort 'agent order changed dependency sets' unless alpha_sets == dependency_sets(agent_data(stp_first))

controller_set = alpha_sets.fetch(KNOWN_TESTS[0])
repository_set = alpha_sets.fetch(KNOWN_TESTS[1])
integration_set = alpha_sets.fetch(KNOWN_TESTS[2])
abort 'controller sentinel crossed into repository test' if repository_set.any? { |key| key.include?('OwnerController#') }
abort 'controller sentinel crossed into integration test' if integration_set.any? { |key| key.include?('OwnerController#') }
abort 'Vet sentinel crossed into controller test' if controller_set.any? { |key| key.include?('.vet.Vet#') }
abort 'Vet sentinel crossed into repository test' if repository_set.any? { |key| key.include?('.vet.Vet#') }

performance = %w[baseline off on].to_h do |mode|
  samples = records.select { |record| record['configuration'] == mode && record['measured'] }
                   .map { |record| record.fetch('durationSeconds') }
  [mode, {
    'samplesSeconds' => samples,
    'medianSeconds' => percentile(samples, 0.5),
    'p95Seconds' => percentile(samples, 0.95)
  }]
end
baseline_median = performance.fetch('baseline').fetch('medianSeconds')
%w[off on].each do |mode|
  median = performance.fetch(mode).fetch('medianSeconds')
  performance.fetch(mode)['medianOverheadPercent'] = (((median / baseline_median) - 1) * 100).round(3)
end

summary = {
  'schemaVersion' => 'task-6-summary-1',
  'petClinicCommit' => PETCLINIC_COMMIT,
  'validation' => {
    'controllerExpectedMethods' => true,
    'repositoryBodiesNotInvented' => true,
    'noCrossTestContamination' => true,
    'orderStable' => true,
    'agentOrderStable' => true,
    'methodIdsMatchCatalog' => true,
    'knownTestIdentitiesOnly' => true,
    'startupUnattributed' => true,
    'jacocoCoexists' => true
  },
  'dependencySets' => alpha_sets,
  'performance' => performance,
  'runs' => records.map { |record| record.reject { |key, _| key == 'stpOutput' } }
}
File.write(File.join(output_dir, 'task-6-summary.json'), JSON.pretty_generate(summary) + "\n")
puts JSON.pretty_generate(performance)
