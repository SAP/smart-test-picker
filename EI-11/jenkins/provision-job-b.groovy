import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def script = new File('/var/jenkins_home/ei11/JobB.Jenkinsfile').text
def jenkins = Jenkins.get()
def job = jenkins.getItem('EI-11-Job-B') ?: jenkins.createProject(WorkflowJob, 'EI-11-Job-B')
job.definition = new CpsFlowDefinition(script, true)
job.description = 'EI-11 real SonarJava explicit R0-to-R1 selection and Maven execution'
job.save()
println('EI-11-Job-B provisioned')
