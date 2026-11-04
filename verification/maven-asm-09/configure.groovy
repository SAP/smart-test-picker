import jenkins.model.Jenkins
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def pipeline = new File('/tmp/maven-asm-09.Jenkinsfile').getText('UTF-8')
// Approval applies only to this local validation Pipeline, not to arbitrary signatures.
org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval.get().preapprove(
    pipeline, org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage.get())
['asm', 'jacoco', 'qualified', 'mismatch-asm', 'mismatch-jacoco',
 'cli-mismatch-asm', 'cli-mismatch-jacoco', 'forks', 'zero-scope', 'missing-fragment', 'incomplete-fragment'].each { scenario ->
    def name = "maven-asm-09-${scenario}"
    def job = Jenkins.get().getItem(name) ?: Jenkins.get().createProject(WorkflowJob, name)
    job.setDefinition(new CpsFlowDefinition(pipeline, false))
    job.removeProperty(ParametersDefinitionProperty)
    job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition('CASE', scenario)))
    job.save()
    println name
}
