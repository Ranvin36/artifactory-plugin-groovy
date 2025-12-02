    import groovy.json.JsonOutput;
    import org.artifactory.repo.RepoPathFactory;

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
            def path = item.getRepoPath();
            def pathString = item.getRepoPath().toString();
            def repo = item.getRepoKey();
            def fileName = path.getName();

            def folderPath = pathString.substring(0, pathString.lastIndexOf('/') + 1);
            def ballerinaFilePath = RepoPathFactory.create(repo, folderPath + fileName);
            log.warn("ballerinaFilePath: $path , Repo : $repo, folderPath: $folderPath, fileName: $fileName");

            if (item.folder) return;
            def packageData = pathString.split('/');
            def repoOrg = packageData[0];
            def orgName = repoOrg.split(':')[1];
            def pkgName = packageData[1];
            def pkgVersion = packageData[2];

            // Applying semver validation
            def versionValidation = semverValidation(pkgVersion)

            if(existingVersion){
                def maxVersion = existingVersion.max {a,b -> compareSemver(a,b)};
                if(compareSemver(pkgVersion, maxVersion) <= 0){
                    throw new Exception("Uploaded version $pkgVersion is not greater than existing version $maxVersion for package $pkgName.");
                }
            }

            if (repositoryExists(path)) {
                log.warn("Folder $ballerinaFilePath already exists.");
                throw new Exception("Folder $ballerinaFilePath already exists.$item");
            }

            // Validating uploaded file is .bala
            if (!isBala(pathString) && !pathString.endsWith('metadata.json')) {
                log.warn("Uploading files other than .bala is not allowed: $pathString");
                throw new Exception("Uploading files other than .bala is not allowed.$item");
            } else {
                log.warn("Uploading .bala file to repo: $repo at path: $path");
    //            return;
            }

            def modulePath = RepoPathFactory.create(repo, "${orgName}/${pkgName}/");
            def existingVersion = repositories.getChildren(modulePath).collect { it.name };
            log.warn("Existing versions for package $pkgName : $existingVersion");
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