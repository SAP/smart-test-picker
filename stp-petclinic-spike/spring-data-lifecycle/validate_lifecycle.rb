# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

require 'json'

path = ARGV.fetch(0, File.join(__dir__, 'normalized-lifecycle.json'))
document = JSON.parse(File.read(path))
abort 'wrong pinned PetClinic revision' unless document.fetch('petClinicCommit') == '88e37c15cf6fc8490b01bc3e8e2c800cec1ac272'
abort 'expected eight isolated runs' unless document.fetch('runs').length == 8

def phase(run, bean, name)
  run.fetch('phases').find { |value| value.fetch('bean') == bean && value.fetch('phase') == name } or
    abort "missing #{run['name']} #{bean} #{name}"
end

document.fetch('runs').select { |run| run.fetch('mode') == 'lifecycle-smart-singletons' }.each do |run|
  %w[ownerRepository vetRepository].each do |bean|
    inserted = phase(run, bean, 'SMART_INITIALIZING_SINGLETON_AFTER_INSERTION')
    refreshed = phase(run, bean, 'CONTEXT_REFRESHED_EVENT_BEFORE_INSERTION')
    prepared = phase(run, bean, 'TEST_CONTEXT_PREPARE_TEST_INSTANCE')
    abort "#{run['name']} #{bean} lacks one diagnostic advisor" unless inserted.fetch('stpAdvisorPositions') == [0]
    abort "#{run['name']} #{bean} changed after SmartInitializingSingleton" unless
      [inserted['advisors'], inserted['targetSourceIdentityToken'], inserted['proxyLayerCount']] ==
        [refreshed['advisors'], refreshed['targetSourceIdentityToken'], refreshed['proxyLayerCount']] &&
      [inserted['advisors'], inserted['targetSourceIdentityToken'], inserted['proxyLayerCount']] ==
        [prepared['advisors'], prepared['targetSourceIdentityToken'], prepared['proxyLayerCount']]
  end
end

vet_snapshot = document.fetch('runs').find { |run| run.fetch('name') == 'vet-snapshots-only' }
vet_product = phase(vet_snapshot, 'vetRepository', 'REPOSITORY_PRODUCT_POST_PROCESS_AFTER_INITIALIZATION')
abort 'Vet cache advisor was not final at product observation' unless vet_product.fetch('cacheAdvisorPositions') == [0]
abort 'Vet exposed bean is not the expected two-layer cache/repository proxy' unless vet_product.fetch('proxyLayerCount') == 2

owner_product = phase(vet_snapshot, 'ownerRepository', 'REPOSITORY_PRODUCT_POST_PROCESS_AFTER_INITIALIZATION')
translation = 'org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor|org.springframework.dao.support.PersistenceExceptionTranslationInterceptor'
abort 'missing final PersistenceExceptionTranslationAdvisor on Owner' unless owner_product.fetch('advisors').include?(translation)

findings = document.fetch('findings')
abort 'wrong failure cause' unless findings.dig('failureMutation', 'addedAdvisor') == translation
abort 'wrong responsible component' unless findings.dig('failureMutation', 'responsiblePublicComponent') ==
  'org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor'
abort 'first invocation is not proven after audit' unless findings.fetch('firstRelevantInvocationAfterAudit')
abort 'unexpected lifecycle decision' unless findings.fetch('decision') ==
  'MOVE_INSERTION_TO_PROVEN_STABLE_PUBLIC_LIFECYCLE_POINT'

puts 'Spring repository advisor lifecycle validation passed'
