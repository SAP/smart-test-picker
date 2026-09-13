import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def script = new File('/var/jenkins_home/ei10/R1FullSuite.Jenkinsfile').text
def jenkins = Jenkins.get()
def job = jenkins.getItem('EI-10-R1-Full-Suite') ?: jenkins.createProject(WorkflowJob, 'EI-10-R1-Full-Suite')
job.definition = new CpsFlowDefinition(script, true)
job.save()
println('EI-10-R1-Full-Suite provisioned')
