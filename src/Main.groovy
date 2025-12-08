import groovy.json.JsonOutput;
import org.artifactory.repo.RepoPathFactory;

// Validates the file being uploaded
def isBala(pathString){
    return pathString.endsWith('.bala');
}

def semverValidation(pkgVersion) {
    def semVerPattern = /^(\d+)\.(\d+)\.(\d+)$/
    if (!(pkgVersion ==~ semVerPattern)){
        throw new Exception("Version $pkgVersion does not follow MAJOR.MINOR.PATCH format.")
    }
}

def compareSemver(a1,a2){
    def (m1,n1,p1) = a1.tokenize('.').collect { it.toInteger() }
    def (m2,n2,p2) = a2.tokenize('.').collect { it.toInteger() }
    return [m1 <=> m2,n1 <=>n2, p1 <=> p2].find { it != 0 } ?: 0
}

def repositoryExists(path) {
    return repositories.exists(path);
}

def digest(algo,bytes){
    def md = java.security.MessageDigest.getInstance(algo);
    def digestBytes = md.digest(bytes);
    digestBytes.collect { String.format("%02x", it) }.join()
}

def getOrgName(name){
    if(name == null) return null;
    def orgTrim = name.trim()
    return orgTrim.contains(':') ? orgTrim.split(':')[1] : orgTrim
}

// Build a normalized repository-relative path from segments (no leading/trailing slashes)
def repoRelativePath(Object... parts){
    def segments = parts.collect { it?.toString()?.trim() }
    def cleaned = segments.findAll { it && it != '' }
    return cleaned.join('/')
}

// Convenience wrapper to create RepoPath safely
def createRepoPath(repoKey, Object... parts){
    def rel = repoRelativePath(parts)
    return RepoPathFactory.create(repoKey, rel)
}

// Used to safely get path parts avoiding index errors
def safePathSplit(parts, index){
    return (parts != null && parts.size() > index) ? parts[index] : null
}

storage{
    beforeCreate { item ->

        def path = item.getRepoPath()
        def pathString = path.toString()
        def repo = item.getRepoKey()
        def fileName = path.getName()
        def packageData = pathString.split('/')

        log.warn("Validating upload for path: $pathString in repo: $repo")
        // If creating top-level org or package folder (first push), allow creation
        if (packageData.size() < 2) {
            log.warn("Creating top-level org entry or invalid path: $pathString. Allowing creation.")
            return
        }

        def repoOrg = safePathSplit(packageData,0)
        def orgName = getOrgName(repoOrg)
        def pkgName = safePathSplit(packageData,1)
        def pkgVersion = ""
        if (packageData.size() >= 3) {
            pkgVersion = safePathSplit(packageData,2)
        }

        if(!pkgName) return;

        // Semantic version validation (only when version present)
        if (pkgVersion) {
            semverValidation(pkgVersion)
        }
        // Check existing versions for the package
        def modulePath = createRepoPath(repo, orgName, pkgName);
        def existingVersion = repositories.getChildren(modulePath)?.collect { it.name } ?: []
        log.warn("Existing versions for package $pkgName : $existingVersion")
        // Prevent uploading same or lower version
        if (item.folder && packageData.size() == 3){
            if(existingVersion){
                def maxVersion = existingVersion.max {a,b -> compareSemver(a,b)}
                if(pkgVersion == maxVersion){
                    throw new Exception("Uploaded version $pkgVersion already exists for package $pkgName.")
                }
                if(compareSemver(pkgVersion, maxVersion) <= 0){
                    throw new Exception("Uploaded version $pkgVersion is not greater than existing version $maxVersion for package $pkgName.")
                }
            }
            return
        }

        // Allow creating top-level package folder (org/package) on first push
        if (item.folder && packageData.size() == 2) {
            log.warn("Creating package folder for $pkgName under $orgName. Allowing creation.")
            return
        }

        // Build a normalized repo-relative file path for the uploaded file
        def fileRepoPath = pkgVersion ? createRepoPath(repo, orgName, pkgName, pkgVersion, fileName) : createRepoPath(repo, orgName, pkgName, fileName)
        log.warn("ballerinaFilePath: $fileRepoPath , Repo : $repo, fileName: $fileName")

        if (repositoryExists(fileRepoPath)){
            log.warn("Resource $fileRepoPath already exists.")
            throw new Exception("Resource $fileRepoPath already exists.$item")
        }

        // Validating uploaded file is .bala
        if (!isBala(pathString) && !pathString.endsWith('metadata.json') && !pathString.endsWith('.sha256-file') && !pathString.endsWith('.sha1-file') && !pathString.endsWith('.md5-file')) {
            log.warn("Uploading files other than .bala is not allowed: $pathString")
            throw new Exception("Uploading files other than .bala is not allowed.$item")
        } else {
            log.warn("Uploading .bala file to repo: $repo at path: $path")
        }
    }

    afterCreate{item ->
        if (item.folder) return;
        def itemPath = item.getRepoPath();
        def repoPath = item.getRepoKey();
        def itemPathString = itemPath.toString()

        if (itemPathString.endsWith('metadata.json')) return;
        if (itemPathString.endsWith('.sha256') || itemPathString.endsWith('.sha256-file') || itemPathString.endsWith('.sha1-file') || itemPathString.endsWith('.md5-file') || itemPathString == 'metadata.json') return


        def packageData = itemPathString.split('/');

        def repoOrg = safePathSplit(packageData,0);
        def orgName = getOrgName(repoOrg)
        def pkgName = safePathSplit(packageData,1);
        def pkgVersion = safePathSplit(packageData,2);

        // Creating metadata json file (package data)
        def metadata = [
                "organization": orgName,
                "package": pkgName,
                "version": pkgVersion
        ];

        def jsonContent = JsonOutput.prettyPrint(JsonOutput.toJson(metadata))

        log.warn("Uploading Package Metadata to : $repoPath");
        def metaDataPath = RepoPathFactory.create(repoPath, "${orgName}/${pkgName}/${pkgVersion}/metadata.json");
        if (!repositories.exists(metaDataPath)) {
            log.warn("Creating new metadata file at: $metaDataPath");
            repositories.deploy(metaDataPath, new ByteArrayInputStream(jsonContent.bytes));
        }

        // Creating checksum files(SHA-256) for uploaded .bala files
        if(!item.folder){
            try {
                def contentStream = repositories.getContent(itemPath);
                def fileBytes = contentStream.inputStream.bytes;
                def checksums = [
                        "sha256": digest("sha256", fileBytes),
                        "sha1": digest("sha1", fileBytes),
                        "md5": digest("md5", fileBytes)
                ]
                log.warn("Uploading SHA-256 checksum for file at path: $itemPath.name");
                checksums.each { algo, hashBytes ->
                    def checksumFilePath = RepoPathFactory.create(repoPath, itemPath.path + ".${algo}-file");
                    repositories.deploy(checksumFilePath, new ByteArrayInputStream(hashBytes.getBytes('UTF-8')));
                    log.warn("Creating checksum file at: $checksumFilePath with $algo: $hashBytes");
                }
            } catch (MissingPropertyException e) {
                // defensive fallback: if stream not available, skip checksum creation and log
                log.error("Could not create checksum - no input stream available for item: $item - ${e.message}")
            }
        }

    }
}

download{
    beforeDownloadRequest{ request, repoPath->
        def befDownloadPath = repoPath.toString();
        def splitPath = befDownloadPath.split('/');
        def orgRepo = safePathSplit(splitPath,0);
        def orgNameRequest = getOrgName(orgRepo)
        def pkgNameRequest = safePathSplit(splitPath,1);
        def requestedFile = repoPath.name;
        def requestedPath = repoPath.path;
        def requestedRepo = repoPath.getRepoKey();

        // Break down the requested file name into parts
        def fileTokenize = requestedFile.tokenize('.');
        log.warn("Attempting to download item at path: $befDownloadPath from repo: $requestedRepo requestedPath: $requestedPath requestedFile: $requestedFile");
        // Check if the request is for a folder/module or a specific file/version
        if (requestedFile == null || requestedFile.trim() == '' || fileTokenize.size() < 3) {
            // Apply the retrieve highest compatible version logic

            log.warn("Folder/module requested (no specific file/version): $requestedPath")
            def requestModulePath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest)
            def requestExistingVersions = repositories.getChildren(requestModulePath)?.collect { it.name } ?: []
            log.warn("Existing versions for module at path $requestModulePath : $requestExistingVersions");
            // filter only valid semver entries
            def semvers = requestExistingVersions.findAll { it ==~ /^\d+\.\d+\.\d+$/ }
            if (!semvers) {
                log.warn("No semver versions found for module at $requestModulePath; cannot redirect")
                return
            }
            def highestVersion = semvers.max {a,b -> compareSemver(a,b)}
            log.warn("Highest compatible version determined: $highestVersion")
            def newDownloadPath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, "${pkgNameRequest}-${highestVersion}.bala")
            request.setRepoPath(newDownloadPath);
            log.warn("Redirecting download request to path: $newDownloadPath")
        } else {
            log.warn("Specific file requested: $requestedFile at path: $requestedPath")
        }
    }
}