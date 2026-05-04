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
import java.util.regex.Pattern

@Field static final String UPSTREAM_SERVER = "http://10.100.1.29:8000/files/"
@Field static final int UPSTREAM_MAX_ATTEMPTS = 4
@Field static final long UPSTREAM_BASE_DELAY_MS = 500 // initial backoff
@Field static final long UPSTREAM_MAX_DELAY_MS = 8000 // cap backoff
@Field static final Pattern SAFE_SEGMENT = ~/^[A-Za-z0-9._-]+$/

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
    if (parts.size() != 4) {
        return null
    }

    def fileName = parts.last()
    def version = parts[parts.size() - 2]
    def pkgName = parts[parts.size() - 3]
    def org = parts[parts.size() - 4]

    def segments = [org, pkgName, version, fileName]
    if (segments.any { !it || !(it ==~ SAFE_SEGMENT) } || segments.any { it.contains('..') }) {
        return null
    }

    return [org: org, pkgName: pkgName, version: version, fileName: fileName, relativePath: requestPath]
}

def fetchVersionJsonFromUpstream(upstreamServer, relativePath) {
    def attempts = 0
    while (attempts < UPSTREAM_MAX_ATTEMPTS) {
        attempts++
        try {
            def fileUrl = upstreamServer + relativePath
            def connection = new URL(fileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            def code = connection.responseCode
            if (code == 200) {
                def content = connection.inputStream.text
                def jsonSlurper = new JsonSlurper()
                def versionData = jsonSlurper.parseText(content)
                log.warn("Successfully retrieved version.json from upstream on attempt ${attempts}: $versionData")
                return versionData
            } else {
                log.warn("Upstream responded HTTP ${code} on attempt ${attempts} for ${relativePath}")
                if (code >= 500 && attempts < UPSTREAM_MAX_ATTEMPTS) {
                    def backoff = Math.min(UPSTREAM_MAX_DELAY_MS, UPSTREAM_BASE_DELAY_MS * Math.pow(2, attempts - 1))
                    def jitter = Math.random() * backoff * 0.5
                    def sleepMs = (long)(backoff + jitter)
                    log.warn("Retrying after ${sleepMs}ms (attempt ${attempts}/${UPSTREAM_MAX_ATTEMPTS})")
                    Thread.sleep(sleepMs)
                    continue
                } else {
                    return null
                }
            }
        } catch (Exception e) {
            log.error("Error fetching version.json from upstream on attempt ${attempts}: ${e.message}")
            if (attempts < UPSTREAM_MAX_ATTEMPTS) {
                def backoff = Math.min(UPSTREAM_MAX_DELAY_MS, UPSTREAM_BASE_DELAY_MS * Math.pow(2, attempts - 1))
                def jitter = Math.random() * backoff * 0.5
                def sleepMs = (long)(backoff + jitter)
                log.warn("Retrying after ${sleepMs}ms due to exception (attempt ${attempts}/${UPSTREAM_MAX_ATTEMPTS})")
                Thread.sleep(sleepMs)
                continue
            } else {
                return null
            }
        }
    }
    return null
}

def deployVersionJsonToRepo(String targetRepoKey, String itemPath, String versionJsonContent) {
    def versionJsonPath = itemPath
    def versionJsonRepoPath = org.artifactory.repo.RepoPathFactory.create(targetRepoKey, versionJsonPath)
    
    def is = new ByteArrayInputStream(versionJsonContent.getBytes("UTF-8"))
    
    repositories.deploy(versionJsonRepoPath, is)
    log.warn("Successfully deployed version.json to ${targetRepoKey} at ${versionJsonPath}")
}

download {
    beforeDownloadRequest { request, repoPath ->
        def requestedRepoKey = repoPath?.repoKey
        def requestInfo = parseRequestedVersionPath(repoPath?.path)

        if (!requestInfo) {
            log.warn("Rejected request with invalid version path shape: ${repoPath?.path}")
            return
        }

        log.warn("Requested repo key: ${requestedRepoKey}, path: ${repoPath.path}")
        log.warn("Parsed version path org=${requestInfo.org}, pkgName=${requestInfo.pkgName}, version=${requestInfo.version}, file=${requestInfo.fileName}")

        log.warn("Before download: repoKey=${repoPath?.repoKey}, path=${repoPath?.path}")

        def metadataCheck = repoPath?.path?.endsWith("versions.json")
        if (!metadataCheck) {
            return
        }

        log.warn("Detected request for versions.json")
        def versionJsonData = fetchVersionJsonFromUpstream(UPSTREAM_SERVER, repoPath.path)
        if (!versionJsonData) {
            return
        }

        def requestedRepoConfiguration = requestedRepoKey ? repositories.getRepositoryConfiguration(requestedRepoKey) : null
        def isVirtualRequest = requestedRepoConfiguration?.type?.toString() == "virtual"

        if (!isVirtualRequest) {
            log.warn("Skipping deploy because repo is not virtual: ${requestedRepoKey}")
            return
        }

        log.warn("Detected virtual repo request for versions.json: ${requestedRepoKey}")
        def localMembers = getCombineRepositories(requestedRepoConfiguration, requestedRepoKey)
        if (localMembers.isEmpty()) {
            log.warn("No local members found for virtual repo: ${requestedRepoKey}")
            return
        }

        def versionJsonString = new groovy.json.JsonBuilder(versionJsonData).toString()
        localMembers.each { member ->
            deployVersionJsonToRepo(member.key, repoPath.path, versionJsonString)
        }
        log.warn("Versions.json deployed to ${localMembers.size()} local repositories")
    }
}