import jenkins.model.Jenkins
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMRevisionAction
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()
def name = 'EI-13-Job-B-R1-Selection'
def job = jenkins.getItemByFullName(name, WorkflowJob) ?: jenkins.createProject(WorkflowJob, name)
job.setDefinition(new CpsFlowDefinition(new File('/var/jenkins_home/Jenkinsfile.r1-selection.ei13').text, true))
job.save()

def head = new PullRequestSCMHead('PR-13-head', 'apache', 'commons-statistics', 'ei13-r1', 13,
  new BranchSCMHead('main'), SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD)
def revision = new PullRequestSCMRevision(head, '2937eb2e711483d8ea9dc216af45c16fd0066b77', '04e9e5d9d66da5dcbaa9a635674926ae71b4ea77')
def run = job.scheduleBuild2(0, new SCMRevisionAction(revision)).get()
println "Completed ${run.fullDisplayName} : ${run.result}"
if (run.result.toString() != 'SUCCESS') throw new IllegalStateException("Job B failed: ${run.absoluteUrl}")
