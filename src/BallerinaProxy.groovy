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
import java.net.URLEncoder

@Field static final String UPSTREAM_SERVER = "https://api.central.ballerina.io/2.0/registry/packages"
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
    if (parts.size() != 3 && parts.size() != 4) {
        return null
    }

    def fileName = parts.last()
    if (fileName != "versions.json") {
        return null
    }

    def org
    def pkgName
    def version = null

    if (parts.size() == 3) {
        org = parts[0]
        pkgName = parts[1]
    } else {
        version = parts[parts.size() - 2]
        pkgName = parts[parts.size() - 3]
        org = parts[parts.size() - 4]
    }

    def segments = version ? [org, pkgName, version, fileName] : [org, pkgName, fileName]
    if (segments.any { !it || !(it ==~ SAFE_SEGMENT) } || segments.any { it.contains('..') }) {
        return null
    }

    return [org: org, pkgName: pkgName, version: version, fileName: fileName, relativePath: requestPath]
}

def encodePathSegment(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

def fetchVersionJsonFromUpstream(upstreamServer, relativePath) {
    def attempts = 0
    while (attempts < UPSTREAM_MAX_ATTEMPTS) {
        attempts++
        try {
            def fileUrl = upstreamServer + "/" + relativePath
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
    def payloadBytes = versionJsonContent.getBytes("UTF-8")
    def finalRepoPath = org.artifactory.repo.RepoPathFactory.create(targetRepoKey, versionJsonPath)

    repositories.deploy(finalRepoPath, new ByteArrayInputStream(payloadBytes))
    log.warn("Successfully deployed version.json to ${targetRepoKey} at ${versionJsonPath}")
}

download {
    beforeDownloadRequest { request, repoPath ->
        // Extract the repo key from the request
        def requestedRepoKey = repoPath?.repoKey
        // Check if the request is for a metadata file
        def metadataCheck = repoPath?.path?.endsWith("versions.json")
        if (!metadataCheck) {
            return
        }

        // Get the package details from the path and validate it to prevent path traversal or malformed requests
        def requestInfo = parseRequestedVersionPath(repoPath?.path)

        if (!requestInfo) {
            log.warn("Rejected versions.json request with invalid version path shape: ${repoPath?.path}")
            return
        }

        log.warn("Requested repo key: ${requestedRepoKey}, path: ${repoPath.path}")
        log.warn("Parsed version path org=${requestInfo.org}, pkgName=${requestInfo.pkgName}, version=${requestInfo.version}, file=${requestInfo.fileName}")

        log.warn("Before download: repoKey=${repoPath?.repoKey}, path=${repoPath?.path}")

        // Map requested path to upstream package endpoint (org/pkg)
        def upstreamRel = "${encodePathSegment(requestInfo.org)}/${encodePathSegment(requestInfo.pkgName)}"
        def versionJsonData = fetchVersionJsonFromUpstream(UPSTREAM_SERVER, upstreamRel)
        if (!versionJsonData) {
            return
        }

        // Get the configuration details of the requested repo
        def requestedRepoConfiguration = requestedRepoKey ? repositories.getRepositoryConfiguration(requestedRepoKey) : null
        // Check if the request came through a virtual repo by examining the requested repo configuration
        def isVirtualRequest = requestedRepoConfiguration?.type?.toString() == "virtual"

        if (!isVirtualRequest) {
            log.warn("Skipping deploy because repo is not virtual: ${requestedRepoKey}")
            return
        }

        // Extract the local members of the virtual repo to deploy the metadata to each underlying local repo        
        log.warn("Detected virtual repo request for versions.json: ${requestedRepoKey}")
        def localMembers = getCombineRepositories(requestedRepoConfiguration, requestedRepoKey)
        if (localMembers.isEmpty()) {
            log.warn("No local members found for virtual repo: ${requestedRepoKey}")
            return
        }


        def versionJsonString = new groovy.json.JsonBuilder(versionJsonData).toString()
        // Push the file to the local repository
        localMembers.each { member ->
            deployVersionJsonToRepo(member.key, repoPath.path, versionJsonString)
        }
        log.warn("Versions.json deployed to ${localMembers.size()} local repositories")
    }
}