# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'

directory = File.expand_path(__dir__)
raw = File.join(directory, 'raw')
output = File.join(directory, 'normalized-lifecycle.json')
files = Dir.glob(File.join(raw, '{owner,vet}-{snapshots-only,late-bpp,smart-singletons,context-refreshed}.json')).sort
abort "expected eight lifecycle inputs, found #{files.length}" unless files.length == 8

def normalize(document)
  groups = document.fetch('snapshots').group_by { |snapshot| [snapshot.fetch('canonicalBeanName'), snapshot.fetch('phase')] }
  groups.sort.map do |(bean, phase), snapshots|
    first = snapshots.first
    # Invocation phases can repeat. Preserve occurrences, but express each chain once.
    chains = snapshots.group_by { |snapshot| snapshot.fetch('sequence') - snapshot.fetch('advisorIndex') }
    representative = chains.values.first.sort_by { |snapshot| snapshot.fetch('advisorIndex') }
    {
      'bean' => bean,
      'phase' => phase,
      'occurrences' => chains.length,
      'sameBeanIdentityAsPrevious' => first.fetch('sameBeanIdentityAsPrevious'),
      'proxyKind' => first.fetch('proxyKind'),
      'proxyLayerCount' => first.fetch('proxyLayerCount'),
      'exposedInterfaces' => first.fetch('exposedInterfaces'),
      'targetSourceIdentityToken' => first.fetch('targetSourceIdentityToken'),
      'advisors' => representative.map { |snapshot| snapshot['advisorType'] }.compact,
      'advisorIdentityTokens' => representative.map do |snapshot|
        snapshot['advisorType'] && snapshot.fetch('advisorObjectIdentityToken')
      end.compact,
      'cacheAdvisorPositions' => first.fetch('cacheAdvisorPositions'),
      'transactionAdvisorPositions' => first.fetch('transactionAdvisorPositions'),
      'stpAdvisorPositions' => first.fetch('stpAdvisorPositions'),
      'applicationContextIdentity' => first.fetch('applicationContextIdentity'),
      'threadName' => first.fetch('threadName')
    }
  end
end

runs = files.map do |file|
  document = JSON.parse(File.read(file))
  {'name' => File.basename(file, '.json'), 'mode' => document.fetch('mode'), 'phases' => normalize(document)}
end

findings = {
  'failureMutation' => {
    'bean' => 'ownerRepository',
    'addedAdvisor' => 'org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor|org.springframework.dao.support.PersistenceExceptionTranslationInterceptor',
    'responsiblePublicComponent' => 'org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor',
    'productionAuditReason' => 'EXISTING_ADVISORS_CHANGED'
  },
  'earliestStablePublicInsertionPoint' => 'SMART_INITIALIZING_SINGLETON',
  'stableThrough' => ['CONTEXT_REFRESHED_EVENT', 'TEST_CONTEXT_PREPARE_TEST_INSTANCE',
    'IMMEDIATELY_BEFORE_FIRST_REPOSITORY_INVOCATION', 'IMMEDIATELY_AFTER_REPOSITORY_INVOCATION', 'CONTEXT_SHUTDOWN'],
  'firstRelevantInvocationAfterAudit' => true,
  'sameStrategySupportsOwnerAndVet' => true,
  'additionalProxyRequired' => false,
  'internalApiRequired' => false,
  'decision' => 'MOVE_INSERTION_TO_PROVEN_STABLE_PUBLIC_LIFECYCLE_POINT'
}

File.write(output, JSON.pretty_generate({
  'schemaVersion' => 'spring-repository-lifecycle-normalized-1',
  'petClinicCommit' => '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272',
  'runs' => runs,
  'findings' => findings
}) + "\n")
puts output
