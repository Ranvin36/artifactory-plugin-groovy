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

        def repoOrg = packageData[0]
        def orgName = repoOrg.contains(':') ? repoOrg.split(':')[1] : repoOrg
        def pkgName = packageData[1]
        def pkgVersion = ""
        if (packageData.size() >= 3) {
            pkgVersion = packageData[2]
        }
        // Semantic version validation (only when version present)
        if (pkgVersion) {
            semverValidation(pkgVersion)
        }
        // Check existing versions for the package
        def modulePath = RepoPathFactory.create(repo, "${orgName}/${pkgName}/");
        def existingVersion = repositories.getChildren(modulePath)?.collect { it.name } ?: []
        log.warn("Existing versions for package $pkgName : $existingVersion")

        if (item.folder && packageData.size() == 3){
            if(existingVersion){
                def maxVersion = existingVersion.max {a,b -> compareSemver(a,b)}
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

        def folderPath = pathString.substring(0, pathString.lastIndexOf('/') + 1)
        def ballerinaFilePath = RepoPathFactory.create(repo, folderPath + fileName)
        log.warn("ballerinaFilePath: $path , Repo : $repo, folderPath: $folderPath, fileName: $fileName")


        if (repositoryExists(path)){
            log.warn("Folder $ballerinaFilePath already exists.")
            throw new Exception("Folder $ballerinaFilePath already exists.$item")
        }

        // Validating uploaded file is .bala
        if (!isBala(pathString) && !pathString.endsWith('metadata.json')){
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

        if (itemPathString.endsWith('metadata.json')) {
            return
        }

        def packageData = itemPathString.split('/');

        def repoOrg = packageData[0];
        def orgName = repoOrg.split(':')[1];
        def pkgName = packageData[1];
        def pkgVersion = packageData[2];

        def metadata = [
                "organization": orgName,
                "package": pkgName,
                "version": pkgVersion
        ];

        def jsonContent = JsonOutput.prettyPrint(JsonOutput.toJson(metadata))

        log.warn("Uploading Package Metadata to : $repoPath");
        def metaDataPath = RepoPathFactory.create(repoPath, "${orgName}/${pkgName}/${pkgVersion}/metadata.json");
        if (!repositories.exists(metaDataPath)) {
            repositories.deploy(metaDataPath, new ByteArrayInputStream(jsonContent.bytes))
        }

    }
}

download{
    beforeDownloadRequest{ request, repoPath->
        def befDownloadPath = repoPath.toString();
        def splitPath = befDownloadPath.split('/');
        def requestedFile = repoPath.name;
        def requestedPath = repoPath.path;
        def requestedRepo = befDownloadPath.split('/')[0].split(':')[0];

        // Break down the requested file name into parts
        def fileTokenize = requestedFile.tokenize('.');
        log.warn("Attempting to download item at path: $befDownloadPath from repo: $requestedRepo");
        // Check if the request is for a folder/module or a specific file/version
        if (requestedFile == null || requestedFile.trim() == '' || fileTokenize.size() < 3) {
            // Apply the retrieve highest compatible version logic

            log.warn("Folder/module requested (no specific file/version): $requestedPath")
            def requestModulePath = RepoPathFactory.create(requestedRepo, "/ranvin/hello_world/");
            def requestExistingVersions = repositories.getChildren(requestModulePath)?.collect { it.name } ?: []
            log.warn("Existing versions for module at path $requestModulePath : $requestExistingVersions");
            def highestVersion = requestExistingVersions.max {a,b -> compareSemver(a,b)}
            log.warn("Highest compatible version determined: $highestVersion")
            def newPath = "/ranvin/hello_world/${highestVersion}/hello_world-${highestVersion}.bala";
            def newDownloadPath = RepoPathFactory.create(requestedRepo, newPath);
            request.setRepoPath(newDownloadPath);
            log.warn("Redirecting download request to path: $newDownloadPath")
        } else {
            log.warn("Specific file requested: $requestedFile at path: $requestedPath")
        }
    }
}