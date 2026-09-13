import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def script = new File('/var/jenkins_home/ei11/JobC.Jenkinsfile').text
def jenkins = Jenkins.get()
def job = jenkins.getItem('EI-11-Job-C') ?: jenkins.createProject(WorkflowJob, 'EI-11-Job-C')
job.definition = new CpsFlowDefinition(script, true)
job.description = 'EI-11 full SonarJava R1 Maven safety comparison'
job.save()
println('EI-11-Job-C provisioned')
