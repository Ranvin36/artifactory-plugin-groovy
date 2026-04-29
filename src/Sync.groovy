import groovy.json.JsonOutput;
import groovy.transform.Field;
import org.artifactory.repo.RepoPathFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import groovy.json.JsonBuilder

download {
    beforeDownload { request, repoPath ->
        // Target your specific generic remote repo
        def repoKey = 'bala_stuff'
        

        log.warn(request.repoKey + " - " + repoKey)
        if (request.repoKey == repoKey) {
            log.info "Intercepting request for ${repoPath}. Forcing upstream check."
            
            // This forces Artifactory to ignore the cached version 
            // and treat the remote as the source of truth for this request.
            expired=true
        }
    }
}