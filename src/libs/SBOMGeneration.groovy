import groovy.transform.Field
import java.util.zip.ZipInputStream
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap


def deploySbom(item, String sbomJson) {
    def basePath = item.repoPath.path.replaceAll(/\/[^\/]*$/, '')
    def sbomPath = "${basePath}/bom.cdx.json"
    def sbomRepoPath = org.artifactory.repo.RepoPathFactory.create(item.repoKey, sbomPath)
    def is = new ByteArrayInputStream(sbomJson.getBytes("UTF-8"))
    repositories.deploy(sbomRepoPath, is)
    log.warn("Deployed embedded SBOM for ${item.name} at ${sbomPath}")
}

def deploySbomToRepo(String targetRepoKey, String itemPath, String sbomJson) {
    def basePath = itemPath.replaceAll(/\/[^\/]*$/, '')
    def sbomPath = "${basePath}/bom.cdx.json"
    def sbomRepoPath = org.artifactory.repo.RepoPathFactory.create(targetRepoKey, sbomPath)
    def is = new ByteArrayInputStream(sbomJson.getBytes("UTF-8"))
    repositories.deploy(sbomRepoPath, is)
    log.warn("Deployed embedded SBOM to ${targetRepoKey} at ${sbomPath}")
}

def extractEmbeddedSbomFromBala(inputStream) {
    def entry
    def bomContent
    def zipInputStream = new ZipInputStream(inputStream)
    def buffer = new byte[8192]
    while ((entry = zipInputStream.getNextEntry()) != null) {
        def entryNameLower = entry.getName().toLowerCase()
        if (entryNameLower.endsWith("bom.cdx.json") || entryNameLower.contains(".cdx.bom.json") || (entryNameLower.contains("bom") && entryNameLower.endsWith(".json"))) {
            def baos = new ByteArrayOutputStream()
            int readLen
            while ((readLen = zipInputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, readLen)
            }
            bomContent = new String(baos.toByteArray(), StandardCharsets.UTF_8)
            log.warn("Found embedded SBOM inside .bala: " + entry.getName())
            zipInputStream.closeEntry()
            break
        }
        zipInputStream.closeEntry()
    }
    return bomContent
}

def getCombineRepositories(requestedConfig, requestedRepoKey) {
    def members = requestedConfig.repositories ?: []
    def localMembers = members.collect { memberKey ->
        def memberConfig = repositories.getRepositoryConfiguration(memberKey)
        [key: memberKey, type: memberConfig?.type?.toString()]
    }.findAll { it.type == "local" }
    return localMembers
}

@Field def requestToVirtual = new ConcurrentHashMap<String, Map>()

download {
    beforeDownloadRequest { request, repoPath ->
        def requestedRepoKey = repoPath?.repoKey
        if (requestedRepoKey) {
            def key = "${repoPath.path}"
            requestToVirtual[key] = [repoKey: requestedRepoKey, ts: System.currentTimeMillis()]
        }
    }
}

storage {
    afterCreate { item ->
        if (item.isFolder()) {
            return
        }
        log.warn("Artifact Created: " + item.repoPath.toString())
        def repoKey = item.repoKey
        def itemPath = item.repoPath
        def requestContext = requestToVirtual.remove(itemPath.path)
        def requestedRepoKey = requestContext?.repoKey
        def requestedRepoConfiguration = requestedRepoKey ? repositories.getRepositoryConfiguration(requestedRepoKey) : null
        def isVirtualRequest = requestedRepoConfiguration?.type?.toString() == "virtual"

        boolean isUpstream = repoKey.endsWith("-cache")
        def localMembers = []
        if (isVirtualRequest) {
            localMembers = getCombineRepositories(requestedRepoConfiguration, requestedRepoKey)
        }

        // Skip SBOM files themselves to prevent recursive deployment
        if (itemPath.path.endsWith("bom.cdx.json")) {
            log.warn("Skipping SBOM file: " + itemPath.path)
            return
        }

        if (!itemPath.path.endsWith(".bala")) {
            log.debug("Uploaded artifact ${item.name} to ${repoKey} is not a .bala; ignoring")
            return
        }

        log.warn("Processing .bala ${item.name} in ${repoKey} (upstream cache: ${isUpstream})")
        def balaInputStream = repositories.getContent(itemPath).inputStream
        def bom
        try {
            bom = extractEmbeddedSbomFromBala(balaInputStream)
        } finally {
            balaInputStream.close()
        }
        if (bom) {
            if (isVirtualRequest && !localMembers.isEmpty()) {
                localMembers.each { member ->
                    deploySbomToRepo(member.key, itemPath.path, bom)
                }
            } else {
                deploySbom(item, bom)
            }
        } else {
            log.warn("No embedded SBOM found inside ${item.name}; skipping")
        }
    }
}
