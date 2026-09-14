import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
def name = 'EI-14-R0-Publication'
def job = jenkins.getItemByFullName(name, WorkflowJob) ?: jenkins.createProject(WorkflowJob, name)
job.setDefinition(new CpsFlowDefinition(new File('/var/jenkins_home/Jenkinsfile.publish-r0.ei14').text, true))
job.save()
def run = job.scheduleBuild2(0).get()
println "Completed ${run.fullDisplayName} : ${run.result}"
if (run.result.toString() != 'SUCCESS') throw new IllegalStateException("Publication failed: ${run.absoluteUrl}")
