import groovy.json.JsonOutput;
import groovy.transform.Field;
import org.artifactory.repo.RepoPathFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import groovy.json.JsonBuilder
import java.util.concurrent.ConcurrentHashMap

@Field static final String UPSTREAM_SERVER = "http://10.100.1.29:8000/files/"

def getCombineRepositories(requestedConfig, requestedRepoKey) {
    def members = requestedConfig.repositories ?: []
    def localMembers = members.collect { memberKey ->
        def memberConfig = repositories.getRepositoryConfiguration(memberKey)
        [key: memberKey, type: memberConfig?.type?.toString()]
    }.findAll { it.type == "local" }

    log.warn("Virtual repo ${requestedRepoKey} local members: ${localMembers}")
    return localMembers
}

def parseRequestedVersionPath(String requestPath) {
    def parts = requestPath?.tokenize('/') ?: []
    if (parts.size() < 4) {
        return null
    }

    def fileName = parts.last()
    def version = parts[parts.size() - 2]
    def pkgName = parts[parts.size() - 3]
    def org = parts[parts.size() - 4]

    return [org: org, pkgName: pkgName, version: version, fileName: fileName, relativePath: requestPath]
}

def fetchVersionJsonFromUpstream(upstreamServer, relativePath) {
    try {
        def fileUrl = upstreamServer + relativePath
        def connection = new URL(fileUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        
        if(connection.responseCode == 200) {
            def content = connection.inputStream.text
            def jsonSlurper = new JsonSlurper()
            def versionData = jsonSlurper.parseText(content)
            log.warn("Successfully retrieved version.json from upstream: $versionData")
            return versionData
        } else {
            log.warn("Failed to fetch version.json from upstream: HTTP ${connection.responseCode}")
            return null
        }
    } catch(Exception e) {
        log.error("Error fetching version.json from upstream: ${e.message}")
        return null
    }
}

def deployVersionJsonToRepo(String targetRepoKey, String itemPath, String versionJsonContent) {
    def versionJsonPath = itemPath
    def versionJsonRepoPath = org.artifactory.repo.RepoPathFactory.create(targetRepoKey, versionJsonPath)
    
    def is = new ByteArrayInputStream(versionJsonContent.getBytes("UTF-8"))
    
    repositories.deploy(versionJsonRepoPath, is)
    log.warn("Successfully deployed version.json to ${targetRepoKey} at ${versionJsonPath}")
}

@Field def requestToVirtual = new ConcurrentHashMap<String, Map>()

download {
    beforeDownloadRequest { request, repoPath ->
        def requestedRepoKey = repoPath?.repoKey
        def requestInfo = parseRequestedVersionPath(repoPath?.path)

        if (requestedRepoKey) {
            def key = "${repoPath.path}"
            requestToVirtual[key] = [repoKey: requestedRepoKey, ts: System.currentTimeMillis(), requestInfo: requestInfo]
            log.warn("Requested repo key: ${requestedRepoKey}, path: ${repoPath.path}")
            if (requestInfo) {
                log.warn("Parsed version path org=${requestInfo.org}, pkgName=${requestInfo.pkgName}, version=${requestInfo.version}, file=${requestInfo.fileName}")
            }
        }

      log.warn("Before download: repoKey=${repoPath?.repoKey}, path=${repoPath?.path}")

      def metadataCheck = repoPath?.getPath()?.endsWith("versions.json")
      
      if(metadataCheck) {
        // Fetch versions.json from upstream
        log.warn("Detected request for versions.json")
                def versionJsonData = fetchVersionJsonFromUpstream(UPSTREAM_SERVER, repoPath.path)
        
        if (versionJsonData) {
            // Check if this was a virtual repository request
            def requestContext = requestToVirtual.get(repoPath.path)
            def requestedRepoKeyFromContext = requestContext?.repoKey ?: requestedRepoKey
            def requestedRepoConfiguration = requestedRepoKeyFromContext ? repositories.getRepositoryConfiguration(requestedRepoKeyFromContext) : null
            def isVirtualRequest = requestedRepoConfiguration?.type?.toString() == "virtual"
            
            if (isVirtualRequest) {
                log.warn("Detected virtual repo request for versions.json: " + requestedRepoKeyFromContext)
                def localMembers = getCombineRepositories(requestedRepoConfiguration, requestedRepoKeyFromContext)
                
                if (!localMembers.isEmpty()) {
                    // Deploy versions.json to each local member
                    def versionJsonString = new groovy.json.JsonBuilder(versionJsonData).toString()
                    localMembers.each { member ->
                        deployVersionJsonToRepo(member.key, repoPath.path, versionJsonString)
                    }
                    log.warn("Versions.json deployed to ${localMembers.size()} local repositories")
                }
            }
        }
      }

    }
}