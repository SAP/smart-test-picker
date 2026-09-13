import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
def name = 'EI-10-Job-A'
def job = jenkins.getItem(name) ?: jenkins.createProject(WorkflowJob, name)
job.setDefinition(new CpsFlowDefinition(new File('/tmp/JobA.Jenkinsfile').getText('UTF-8'), true))
job.save()
println("configured ${name}")
