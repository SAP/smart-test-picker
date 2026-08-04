# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'

root = File.expand_path(__dir__)
document = JSON.parse(File.read(File.join(root, 'normalized-acceptance.json')))
abort 'wrong PetClinic revision' unless document.fetch('petClinicCommit') == '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'

owner_id = 'org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner'
vet_id = 'org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll'
owner_expected = [
  ['org.springframework.samples.petclinic.owner.OwnerRepository', 'findByLastNameStartingWith',
   '(Ljava/lang/String;Lorg/springframework/data/domain/Pageable;)Lorg/springframework/data/domain/Page;', 2],
  ['org.springframework.samples.petclinic.owner.OwnerRepository', 'save', '(Ljava/lang/Object;)Ljava/lang/Object;', 1]
]
vet_expected = [['org.springframework.samples.petclinic.vet.VetRepository', 'findAll', '()Ljava/util/Collection;', 2]]

def compact(facts)
  facts.map { |fact| [fact.fetch('repositoryInterface'), fact.fetch('methodName'),
    fact.fetch('jvmDescriptor'), fact.fetch('count')] }
end

runs = document.fetch('runs').to_h { |run| [run.fetch('name'), run] }
owner = runs.fetch('owner-individual').fetch('tests').fetch(owner_id)
vet = runs.fetch('vet-individual').fetch('tests').fetch(vet_id)
abort 'Owner facts differ' unless compact(owner) == owner_expected
abort 'Vet facts differ' unless compact(vet) == vet_expected
owner.each { |fact| abort 'invalid Owner semantics' unless fact['repositoryKind'] == 'SPRING_DATA_PROXY' &&
  fact['domainType'] == 'org.springframework.samples.petclinic.owner.Owner' && fact['outcome'] == 'SUCCEEDED' &&
  fact['evidenceSource'] == 'SPRING_DATA' && fact['certainty'] == 'OBSERVED' }
vet.each { |fact| abort 'invalid Vet semantics' unless fact['domainType'] ==
  'org.springframework.samples.petclinic.vet.Vet' && fact['outcome'] == 'SUCCEEDED' }

expected_sets = {owner_id => owner, vet_id => vet}
%w[combined-alphabetical combined-reverse combined-stp-first].each do |name|
  abort "#{name} changed dependency sets" unless runs.fetch(name).fetch('tests') == expected_sets
end
abort 'adapter-disabled run contains repository facts' unless
  runs.fetch('combined-disabled').fetch('tests').values.all?(&:empty?)
document.fetch('runs').each do |run|
  abort "#{run['name']} has unattributed repository events" unless run.fetch('unattributedRepositoryEvents').zero?
  abort "#{run['name']} has late events" unless run.fetch('lateEvents').zero?
end
abort 'Vet cache did not reduce underlying execution to one' unless
  document.fetch('vetUnderlyingRepositoryExecutions') == 1

document.fetch('auditBeans').each do |bean|
  abort "#{bean['beanName']} identity changed" unless bean.fetch('sameLookupIdentity')
  abort "#{bean['beanName']} has duplicate/missing STP advisor" unless bean.fetch('stpAdvisorCount') == 1
  abort "#{bean['beanName']} STP advisor is not index zero" unless bean.fetch('stpAdvisorIndex') == 0
end
vet_audit = document.fetch('auditBeans').find { |bean| bean['beanName'] == 'vetRepository' }
abort 'Vet cache advisor is not behind STP' unless vet_audit.fetch('cachePositions') == [1]

document.fetch('runs').each do |run|
  abort "#{run['name']} lacks JaCoCo output" unless run.fetch('jacocoBytes').positive?
end
abort 'unexpected decision' unless document.fetch('decision') == 'PROCEED_TO_STABILIZATION'
puts 'Spring Data PetClinic acceptance validation passed'
