# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'

directory = File.expand_path(ARGV[0] || File.join(__dir__, 'task-6-outputs'))
names = %w[individual-controller individual-repository individual-integration combined-alphabetical
           combined-reverse combined-stp-first]
documents = names.to_h { |name| [name, JSON.parse(File.read(File.join(directory, "#{name}.json")))] }
known = [
  'org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess',
  'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner',
  'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
]

sets = lambda do |document|
  document.fetch('runtimeEvents').fetch('tests').to_h do |test|
    ["#{test['testClass']}##{test['testMethod']}", test.fetch('methods').map { |method| method.fetch('method') }.sort]
  end
end

documents.each do |name, document|
  catalog = document.fetch('methodCatalog').to_h { |entry| [entry.fetch('methodId'), entry.fetch('canonicalKeys')] }
  document.fetch('runtimeEvents').fetch('tests').each do |test|
    identity = "#{test['testClass']}##{test['testMethod']}"
    abort "#{name}: unknown test #{identity}" unless known.include?(identity)
    test.fetch('methods').each do |method|
      abort "#{name}: method ID/catalog mismatch" unless catalog.fetch(method.fetch('methodId')).include?(method.fetch('method'))
    end
    # Finished tests may contain explicit LATE_EVENT records, but never dependencies created after closure.
    abort "#{name}: unattributed event leaked into methods" if test.fetch('methods').any? { |method| method['reason'] }
  end
  attributed = document.fetch('runtimeEvents').fetch('tests').flat_map { |test| test.fetch('methods') }
  abort "#{name}: startup configuration was attributed to a leaf" if attributed.any? do |method|
    method.fetch('method').include?('.system.WebConfiguration#') ||
      method.fetch('method').include?('.system.CacheConfiguration#')
  end
  abort "#{name}: duplicate instrumentation" unless document.fetch('metrics').fetch('alreadyInstrumentedClasses').zero?
  abort "#{name}: agent error" unless document.fetch('agentErrors').empty?
  keys = document.fetch('methodCatalog').flat_map { |entry| entry.fetch('canonicalKeys') }
  abort "#{name}: generated class was instrumented" if keys.any? do |key|
    key.include?('$$') || key.include?('$MockitoMock$') || key.include?('$HibernateProxy') ||
      key.include?('$HibernateInstantiator') || key.include?('#$jacocoInit(')
  end
end

controller = sets.call(documents.fetch('individual-controller')).fetch(known[0])
%w[findOwner setAllowedFields processCreationForm].each do |method|
  abort "controller method missing: #{method}" unless controller.any? { |key| key.include?("OwnerController##{method}(") }
end
repository = sets.call(documents.fetch('individual-repository')).fetch(known[1])
abort 'repository implementation method invented' if repository.any? { |key| key.include?('Repository#') || key.include?('Repository$') }
integration = sets.call(documents.fetch('individual-integration')).fetch(known[2])
abort 'MVC handler attributed to integration test' if integration.any? { |key| key.include?('Controller#') }

alphabetical = sets.call(documents.fetch('combined-alphabetical'))
reverse = sets.call(documents.fetch('combined-reverse'))
stp_first = sets.call(documents.fetch('combined-stp-first'))
abort 'dependency sets differ by test order' unless alphabetical == reverse
abort 'dependency sets differ by agent order' unless alphabetical == stp_first
abort 'controller crossed into repository test' if alphabetical.fetch(known[1]).any? { |key| key.include?('OwnerController#') }
abort 'controller crossed into integration test' if alphabetical.fetch(known[2]).any? { |key| key.include?('OwnerController#') }
abort 'Vet method crossed into controller test' if alphabetical.fetch(known[0]).any? { |key| key.include?('.vet.Vet#') }
abort 'Vet method crossed into repository test' if alphabetical.fetch(known[1]).any? { |key| key.include?('.vet.Vet#') }

normalized = {
  'schemaVersion' => 'task-6-normalized-1',
  'dependencySets' => alphabetical,
  'lateEvents' => documents.transform_values do |document|
    document.fetch('runtimeEvents').fetch('tests').sum do |test|
      test.fetch('unattributedEvents').select { |event| event['reason'] == 'LATE_EVENT' }.sum { |event| event.fetch('count') }
    end
  end
}
serialized = JSON.pretty_generate(normalized) + "\n"
abort 'normalized JSON is not deterministic' unless serialized == JSON.pretty_generate(normalized) + "\n"
File.write(File.join(directory, 'task-6-normalized.json'), serialized)
puts 'Task 6 attribution validation passed'
