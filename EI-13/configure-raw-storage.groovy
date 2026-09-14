import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import io.github.smarttestpicker.jenkins.SmartTestPickerConfiguration

assert args.length == 1
def secret = args[0]
def provider = SystemCredentialsProvider.getInstance()
provider.credentials.findAll { it.id == 'ei13-nexus-storage' }.each { provider.credentials.remove(it) }
provider.credentials.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,
    'ei13-nexus-storage', 'Disposable EI-13 Nexus credential', 'stp-task70', secret))
provider.save()
secret = null
def configuration = SmartTestPickerConfiguration.get()
configuration.setStorageType(Enum.valueOf(configuration.storageType.class, 'RAW_HTTP'))
configuration.setRawHttpBaseUrl('http://nexus:8081')
configuration.setRawHttpRepositoryPath('repository/stp-coverage-maps')
configuration.setRawHttpCredentialId('ei13-nexus-storage')
configuration.setRawHttpAllowInsecureHttp(true)
configuration.setRawHttpConnectTimeoutMillis(10000)
configuration.setRawHttpReadTimeoutMillis(30000)
configuration.save()
println 'EI13 RAW_HTTP CONFIG PASS credentialId=ei13-nexus-storage secretPersistedInPluginConfiguration=false'
