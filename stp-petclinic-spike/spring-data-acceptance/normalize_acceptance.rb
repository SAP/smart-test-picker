# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'

root = File.expand_path(__dir__)
raw = File.join(root, 'raw')
names = %w[owner-individual vet-individual combined-alphabetical combined-reverse combined-stp-first combined-disabled]

def facts(document)
  document.fetch('runtimeEvents').fetch('tests').to_h do |test|
    id = "#{test['testClass']}##{test['testMethod']}"
    repositories = test.fetch('repositories').map do |fact|
      fact.slice('repositoryKind', 'repositoryInterface', 'beanName', 'methodName', 'jvmDescriptor',
        'domainType', 'outcome', 'evidenceSource', 'certainty', 'count')
    end.sort_by { |fact| [fact['repositoryInterface'], fact['methodName'], fact['jvmDescriptor']] }
    [id, repositories]
  end
end

runs = names.map do |name|
  path = File.join(raw, "#{name}-stp.json")
  abort "missing #{path}" unless File.file?(path)
  document = JSON.parse(File.read(path))
  global = document.fetch('runtimeEvents').fetch('unattributedEvents')
  {
    'name' => name,
    'tests' => facts(document),
    'unattributedRepositoryEvents' => global.select { |event| event['eventType'] == 'REPOSITORY' }
      .sum { |event| event.fetch('count') },
    'lateEvents' => global.select { |event| event['reason'] == 'LATE_EVENT' }.sum { |event| event.fetch('count') },
    'jacocoBytes' => File.size(File.join(raw, "#{name}-jacoco.exec")),
    'classesTransformed' => document.fetch('metrics').fetch('classesTransformed'),
    'methodsInstrumented' => document.fetch('metrics').fetch('methodsInstrumented')
  }
end

inner = JSON.parse(File.read(File.join(raw, 'vet-individual-inner.json')))
vet_executions = inner.fetch('records').count do |record|
  record['hookName'] == 'RepositoryMethodInvocationListener' && record['repositoryInterface'] ==
    'org.springframework.samples.petclinic.vet.VetRepository' && record['methodName'] == 'findAll'
end

audit = JSON.parse(File.read(File.join(raw, 'vet-individual-audit.json')))
audit_beans = audit.fetch('beans').select do |bean|
  %w[ownerRepository petTypeRepository vetRepository].include?(bean.fetch('beanName'))
end.map do |bean|
  {
    'beanName' => bean.fetch('beanName'),
    'sameLookupIdentity' => bean.fetch('sameLookupIdentity'),
    'proxyKind' => bean.fetch('proxyKind'),
    'stpAdvisorCount' => bean.fetch('stpAdvisorCount'),
    'stpAdvisorIndex' => bean.fetch('advisors').index do |advisor|
      advisor.start_with?('com.sap.oss.smarttestpicker.springdata.StpCallerAdvisor|')
    end,
    'cachePositions' => bean.fetch('cachePositions'),
    'proxyDepth' => bean.fetch('proxyDepth')
  }
end.sort_by { |bean| bean['beanName'] }

output = {
  'schemaVersion' => 'spring-data-petclinic-acceptance-1',
  'petClinicCommit' => '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272',
  'runs' => runs,
  'vetUnderlyingRepositoryExecutions' => vet_executions,
  'auditBeans' => audit_beans,
  'decision' => 'PROCEED_TO_STABILIZATION'
}
File.write(File.join(root, 'normalized-acceptance.json'), JSON.pretty_generate(output) + "\n")
puts File.join(root, 'normalized-acceptance.json')
