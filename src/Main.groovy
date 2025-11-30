import groovy.json.JsonOutput;
import org.artifactory.repo.RepoPathFactory;


storage{
    beforeCreate{item ->
        def path = item.getRepoPath();
        def pathString = item.getRepoPath().toString();
        def repo = item.getRepoKey();
        def fileName = path.getName();
//        log.warn(JsonOutput.prettyPrint(JsonOutput.toJson(item.properties)))

        def folderPath = pathString.substring(0, pathString.lastIndexOf('/') + 1);

        def ballerinaFilePath = RepoPathFactory.create(repo, folderPath + fileName);
        if(repositories.exists(ballerinaFilePath)){
            throw new Exception("Folder $ballerinaFilePath already exists.$item");
        }

//        if(!path.toString().endsWith('.bala')){
//            throw new Exception("Uploading files other than .bala is not allowed.$item");
//        }
//        else{
//            log.info("Uploading .bala file to repo: $repo at path: $path");
//            return;
//        }
    }
}