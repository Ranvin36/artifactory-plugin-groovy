import groovy.json.JsonOutput;
import groovy.transform.Field;
import org.artifactory.repo.RepoPathFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import groovy.json.JsonBuilder

def parseJson(content) {
    def jsonSlurper = new JsonSlurper()
    return jsonSlurper.parseText(content)
}

def extractDependencies(jsonContent, ballerinaDep ) {
    def dependencies = []
    if (ballerinaDep) {
        (jsonContent?.packages ?: []).each{ dep->
            dependencies.add([groupId: dep.org, artifactId: dep.name, version: dep.version])
            log.warn("Added Ballerina dependency - GroupId: " + dep.org + ", ArtifactId: " + dep.name + ", Version: " + dep.version)
        }
    }
    else {
        (jsonContent?.platformDependencies ?: []).each { dep ->
            dependencies.add([groupId: dep.groupId, artifactId: dep.artifactId, version: dep.version])
            log.warn("Extracted dependency - GroupId: " + dep.groupId + ", ArtifactId: " + dep.artifactId + ", Version: " + dep.version)
        }
    }
    return dependencies

}

def buildBOM(dependencies) {
    def builder = new JsonBuilder()
    builder {
        bomFormat "CycloneDX"
        specVersion "1.4"
        version 1
        components dependencies.collect { dep ->
            [
                type: "library",
                group: dep.groupId,
                name: dep.artifactId,
                version: dep.version,
                purl: "pkg:maven/${dep.groupId}/${dep.artifactId}@${dep.version}"
            ]
        }
    }
    return builder.toString()
}

def deploySbom(item, String sbomJson) {
    // Create a path for the new file: my-package.bala.json
    def sbomPath = "${item.repoPath.path}.json"
    def sbomRepoPath = org.artifactory.repo.RepoPathFactory.create(item.repoKey, sbomPath)
    
    // Convert String to InputStream
    def is = new ByteArrayInputStream(sbomJson.getBytes("UTF-8"))
    
    // Deploy to Artifactory
    repositories.deploy(sbomRepoPath, is)
    log.warn("Successfully deployed SBOM for ${item.name} at ${sbomPath}")
}

def extractItemsFromZip(inputStream) {
    def entry
    def packageJsonContent
    def dependencyGraphContent
    def zipInputStream = new ZipInputStream(inputStream)
    def buffer = new byte[8192]
    while ((entry = zipInputStream.getNextEntry()) != null) {
        if (entry.getName().endsWith(".bala")) {
            log.warn("Found .bala file in zip: " + entry.getName())
        }

        if (entry.getName().contains("dependency-graph") || entry.getName().contains("package.json")) {
            def baos = new ByteArrayOutputStream()
            int readLen
            while ((readLen = zipInputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, readLen)
            }
            def entryText = new String(baos.toByteArray(), StandardCharsets.UTF_8)

            if (entry.getName().contains("dependency-graph")) {
                dependencyGraphContent = entryText
                log.warn("Dependency Graph Content: " + dependencyGraphContent)
            }

            if (entry.getName().contains("package.json")) {
                packageJsonContent = entryText
                log.warn("Package JSON Content: " + packageJsonContent)
            }
        }

        zipInputStream.closeEntry()
    }
    return [packageJson: packageJsonContent, dependencyGraph: dependencyGraphContent]
}

storage{
    afterCreate{item ->
        log.warn("Artifact Created: " + item.repoPath.toString())
        def repoKey = item.repoKey
        def repoConfiguration = repositories.getRepositoryConfiguration(repoKey)
        def itemPath = item.repoPath;

        boolean isUpstream = repoKey.endsWith("-cache")

        if (isUpstream) {
            def packageJson = null
            def dependencyGraph = null
            def dependencies = []
            def ballerinaDependencies = []

            def balaInputStream = repositories.getContent(itemPath).inputStream
            def balaStream = extractItemsFromZip(balaInputStream)
            if (balaStream?.packageJson) {
                packageJson = parseJson(balaStream.packageJson)
                def packageInfo = [name: packageJson.name, version: packageJson.version, org: packageJson.org]
                dependencies = extractDependencies(packageJson, false)
            }

            if (balaStream?.dependencyGraph) {
                dependencyGraph = parseJson(balaStream.dependencyGraph)
                ballerinaDependencies = extractDependencies(dependencyGraph, true)
            }

            def allDependencies = dependencies + ballerinaDependencies
            if (!allDependencies.isEmpty()) {
                deploySbom(item, buildBOM(allDependencies))
            } else {
                log.warn("No dependencies found in ${item.name}; skipping SBOM deployment")
            }

        }
        else {
            log.warn("Package ${item.name} was uploaded directly to a Local Repo. " + repoKey + " is not REMOTE, skipping proxying.")
        }
    }
}