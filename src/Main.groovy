import groovy.json.JsonOutput;
import org.artifactory.repo.RepoPathFactory;

def isBala(pathString){
    return pathString.endsWith('.bala');
}

def repositoryExists(path) {
    return repositories.exists(path);
}

storage{
    beforeCreate{item ->
        def path = item.getRepoPath();
        def pathString = item.getRepoPath().toString();
        def repo = item.getRepoKey();
        def fileName = path.getName();

        def folderPath = pathString.substring(0, pathString.lastIndexOf('/') + 1);
        def ballerinaFilePath = RepoPathFactory.create(repo, folderPath + fileName);
        log.warn("ballerinaFilePath: $path , Repo : $repo, folderPath: $folderPath, fileName: $fileName");

        if (item.folder) return;

        if(repositoryExists(path)){
            log.warn("Folder $ballerinaFilePath already exists.");
            throw new Exception("Folder $ballerinaFilePath already exists.$item");
        }

        if(!isBala(pathString) && !pathString.endsWith('metadata.json')){
            log.warn("Uploading files other than .bala is not allowed: $pathString");
            throw new Exception("Uploading files other than .bala is not allowed.$item");
        }
        else{
            log.warn("Uploading .bala file to repo: $repo at path: $path");
            return;
        }
    }

    afterCreate{item ->
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