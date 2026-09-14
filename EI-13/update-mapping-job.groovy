import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

[
  'EI-13-Job-A-R0-Mapping': 'Jenkinsfile.r0-mapping.ei13',
  'EI-13-Job-B-R1-Selection': 'Jenkinsfile.r1-selection.ei13',
  'EI-13-R1-Full-Coverage': 'Jenkinsfile.r1-coverage.ei13',
  'EI-13-Runtime-Negatives': 'Jenkinsfile.negatives.ei13'
].each { name, pipeline ->
  def job = Jenkins.get().getItemByFullName(name, WorkflowJob)
  assert job != null
  job.setDefinition(new CpsFlowDefinition(new File('/var/jenkins_home/' + pipeline).text, true))
  job.save()
  println "updated ${name} without deleting build history"
}
