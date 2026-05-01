// import groovy.json.JsonOutput;
// import groovy.transform.Field;
// import org.artifactory.repo.RepoPathFactory;
// import java.util.concurrent.locks.ReentrantLock;
// import java.util.zip.ZipInputStream
// import java.util.zip.ZipEntry

// @Field
// def SEMVER_PATTERN_EXT = ~/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/

// @Field
// def VERSIONS_JSON_LOCKS = [:].asSynchronized()

// @Field
// ThreadLocal<Boolean> DEPLOYMENT_IN_PROGRESS = new ThreadLocal<Boolean>() {
//     @Override
//     protected Boolean initialValue() {
//         return false
//     }
// }

// // Validates the file being uploaded
// def isBala(pathString){
//     return pathString.endsWith('.bala');
// }

// // Files generated/managed by this plugin that should bypass normal processing
// def isSystemManagedFile(pathOrName) {
//     if (!pathOrName) return false
//     return pathOrName.endsWith('versions.json') ||
//            pathOrName.endsWith('metadata.json') ||
//            pathOrName.endsWith('package.json') ||
//            pathOrName.endsWith('bom.cdx.json') ||
//            pathOrName.endsWith('.sha256') ||
//            pathOrName.endsWith('.sha1') ||
//            pathOrName.endsWith('.md5') ||
//            pathOrName.endsWith('.sha256-file') ||
//            pathOrName.endsWith('.sha1-file') ||
//            pathOrName.endsWith('.md5-file')
// }

// // Non-bala sidecar files that are allowed during upload
// def isAllowedSidecarUpload(pathString) {
//     if (!pathString) return false
//     return pathString.endsWith('metadata.json') ||
//            pathString.endsWith('package.json') ||
//            pathString.endsWith('bom.cdx.json') ||
//            pathString.endsWith('.sha256-file') ||
//            pathString.endsWith('.sha1-file') ||
//            pathString.endsWith('.md5-file')
// }

// // Validate semantic version format (extended SemVer)
// def semverValidation(pkgVersion) {
//     if (!(pkgVersion ==~ SEMVER_PATTERN_EXT)){
//         throw new Exception("Version $pkgVersion does not follow MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD] format.")
//     }
// }

// // Parse a SemVer string into components; returns null if not matched
// def parseSemver(ver) {
//     def m = ver =~ SEMVER_PATTERN_EXT
//     if (!m.matches()) return null
//     def groups = m[0]
//     return [
//         major: groups[1].toInteger(),
//         minor: groups[2].toInteger(),
//         patch: groups[3].toInteger(),
//         prerelease: groups[4] ? groups[4].split(/\./) : [],
//         build: groups[5]
//     ]
// }

// // Compare prerelease identifiers per SemVer rules
// def comparePrerelease(aList, bList) {
//     def maxLen = Math.max(aList.size(), bList.size())
//     for (int i = 0; i < maxLen; i++) {
//         def a = i < aList.size() ? aList[i] : null
//         def b = i < bList.size() ? bList[i] : null
//         if (a == null && b == null) return 0
//         if (a == null) return -1 // a has fewer identifiers -> lower precedence
//         if (b == null) return 1  // b has fewer identifiers -> lower precedence

//         def aNum = a.isInteger()
//         def bNum = b.isInteger()
//         if (aNum && bNum) {
//             def cmp = a.toInteger() <=> b.toInteger()
//             if (cmp != 0) return cmp
//         } else if (aNum && !bNum) {
//             return -1 // numeric < alphanumeric
//         } else if (!aNum && bNum) {
//             return 1  // alphanumeric > numeric
//         } else {
//             def cmp = a <=> b
//             if (cmp != 0) return cmp
//         }
//     }
//     return 0
// }

// // Compare two semantic version strings (extended SemVer)
// def compareSemver(a1,a2){
//     def pa = parseSemver(a1)
//     def pb = parseSemver(a2)
//     if (!pa || !pb) {
//         throw new Exception("Invalid semantic version comparison: ${a1} vs ${a2}")
//     }

//     def coreCmp = [pa.major <=> pb.major, pa.minor <=> pb.minor, pa.patch <=> pb.patch].find { it != 0 } ?: 0
//     if (coreCmp != 0) return coreCmp

//     def aPre = pa.prerelease
//     def bPre = pb.prerelease

//     if (!aPre && !bPre) return 0
//     if (!aPre && bPre) return 1   // release > prerelease
//     if (aPre && !bPre) return -1  // prerelease < release

//     return comparePrerelease(aPre, bPre)
// }

// def repositoryExists(path) {
//     return repositories.exists(path);
// }
// // Generate hex digest from input stream using buffered reading (memory-safe)
// def digestFromStream(algo, inputStream) {
//     def md = java.security.MessageDigest.getInstance(algo)
//     def buffer = new byte[8192]
//     def bytesRead
    
//     inputStream.withStream { stream ->
//         while ((bytesRead = stream.read(buffer)) != -1) {
//             md.update(buffer, 0, bytesRead)
//         }
//     }
    
//     def digestBytes = md.digest()
//     return digestBytes.collect { String.format("%02x", it) }.join()
// }

// // Legacy method for backward compatibility (avoid using for large files)
// def digest(algo, bytes) {
//     def md = java.security.MessageDigest.getInstance(algo)
//     def digestBytes = md.digest(bytes)
//     digestBytes.collect { String.format("%02x", it) }.join()
// }

// // Read only the current ZIP entry bytes without consuming/closing subsequent entries
// def readCurrentZipEntryBytes(ZipInputStream zipStream) {
//     def out = new ByteArrayOutputStream()
//     def buffer = new byte[8192]
//     int bytesRead
//     while ((bytesRead = zipStream.read(buffer)) != -1) {
//         out.write(buffer, 0, bytesRead)
//     }
//     return out.toByteArray()
// }

// def getOrgName(name){
//     if(name == null) return null;
//     def orgTrim = name.trim()
//     return orgTrim.contains(':') ? orgTrim.split(':')[1] : orgTrim
// }

// // Build a normalized repository-relative path from segments (no leading/trailing slashes)
// def repoRelativePath(Object... parts){
//     def segments = parts.collect { it?.toString()?.trim() }
//     def cleaned = segments.findAll { it && it != '' }
//     return cleaned.join('/')
// }

// // Convenience wrapper to create RepoPath safely
// def createRepoPath(repoKey, Object... parts){
//     def rel = repoRelativePath(parts)
//     return RepoPathFactory.create(repoKey, rel)
// }

// // Used to safely get path parts avoiding index errors
// def safePathSplit(parts, index){
//    return (parts != null && parts.size() > index) ? parts[index] : null
// }

// // Find a .bala file within a version folder, preferring platform-specific variants
// def findBalaFile(repoKey, org, pkg, version, preferredPlatform = null) {
//     def versionPath = createRepoPath(repoKey, org, pkg, version)
//     def children = repositories.getChildren(versionPath)?.collect { it.name } ?: []
//     def balaFiles = children.findAll { it.toLowerCase().endsWith('.bala') }
//     if (!balaFiles) return null
    
//     // If a preferred platform is specified, try to find matching file
//     if (preferredPlatform) {
//         def platformMatch = balaFiles.find { it.contains("-${preferredPlatform}-") }
//         if (platformMatch) return platformMatch
//     }
    
//     // Fallback: prefer 'any' platform, then 'java17', then 'java11', then first available
//     def anyPlatform = balaFiles.find { it.contains('-any-') }
//     if (anyPlatform) return anyPlatform
    
//     def java17Platform = balaFiles.find { it.contains('-java17-') }
//     if (java17Platform) return java17Platform
    
//     def java11Platform = balaFiles.find { it.contains('-java11-') }
//     if (java11Platform) return java11Platform
    
//     return balaFiles[0]
// }

// // Extract platform from a .bala filename (e.g., 'org-pkg-java11-1.0.0.bala' -> 'java11')
// def extractPlatform(filename) {
//     if (!filename) return null
//     def parts = filename.tokenize('-')
//     // Expected format: org-pkg-platform-version.bala
//     if (parts.size() >= 4) {
//         def possiblePlatform = parts[-2]
//         if (possiblePlatform in ['any', 'java11', 'java17', 'java21']) {
//             return possiblePlatform
//         }
//     }
//     return null
// }

// // Get or create a lock for a specific package to prevent concurrent versions.json updates
// def getVersionsJsonLock(repoKey, org, pkg) {
//     def lockKey = "${repoKey}:${org}:${pkg}"
//     synchronized(VERSIONS_JSON_LOCKS) {
//         if (!VERSIONS_JSON_LOCKS.containsKey(lockKey)) {
//             VERSIONS_JSON_LOCKS[lockKey] = new ReentrantLock()
//         }
//         return VERSIONS_JSON_LOCKS[lockKey]
//     }
// }

// // Deploy versions.json with retry mechanism to handle concurrent updates
// def deployVersionsJson(repoKey, org, pkg, maxRetries = 3) {
//     def lock = getVersionsJsonLock(repoKey, org, pkg)
//     def retryCount = 0
//     def success = false
    
//     while (retryCount < maxRetries && !success) {
//         try {
//             lock.lock()
            
//             def pkgPath = createRepoPath(repoKey, org, pkg)
//             def allVersions = repositories.getChildren(pkgPath)?.collect { it.name } ?: []
//             def semversOnly = allVersions.findAll { it ==~ SEMVER_PATTERN_EXT }
//             def sortedVersions = semversOnly.sort { a, b -> compareSemver(b, a) }
            
//             def versionsData = [
//                 organization: org,
//                 package: pkg,
//                 versions: sortedVersions
//             ]
//             def versionsJson = JsonOutput.prettyPrint(JsonOutput.toJson(versionsData))
//             def versionsJsonPath = createRepoPath(repoKey, org, pkg, "versions.json")
            
//             log.warn("Deploying versions.json with ${sortedVersions.size()} versions to: $versionsJsonPath (attempt ${retryCount + 1})")
//             repositories.deploy(versionsJsonPath, new ByteArrayInputStream(versionsJson.getBytes('UTF-8')))
//             success = true
//         } catch (Exception e) {
//             retryCount++
//             log.error("Failed to deploy versions.json (attempt ${retryCount}/${maxRetries}): ${e.message}")
//             if (retryCount < maxRetries) {
//                 Thread.sleep(100 * retryCount) // exponential backoff
//             } else {
//                 throw e
//             }
//         } finally {
//             lock.unlock()
//         }
//     }
// }

// storage({
//     beforeCreate({ item ->

//         def path = item.getRepoPath()
//         def pathString = path.toString()
//         def repo = item.getRepoKey()
//         def fileName = path.getName()
//         def packageData = pathString.split('/')

//         log.warn("Validating upload for path: $pathString in repo: $repo")
        
//         // Skip validation for system/plugin files
//         if (isSystemManagedFile(fileName)) {
//             log.debug("Skipping validation for system file: $fileName")
//             return
//         }

//         // If creating top-level org or package folder (first push), allow creation
//         if (packageData.size() < 2) {
//             log.warn("Creating top-level org entry or invalid path: $pathString. Allowing creation.")
//             return
//         }

//         def repoOrg = safePathSplit(packageData,0)
//         def orgName = getOrgName(repoOrg)
//         def pkgName = safePathSplit(packageData,1)
//         def pkgVersion = ""
//         if (packageData.size() >= 3) {
//             pkgVersion = safePathSplit(packageData,2)
//         }

//         if(!pkgName) return;

//         // Semantic version validation (only when version present)
//         if (pkgVersion) {
//             semverValidation(pkgVersion)
//         }
//         // Check existing versions for the package
//         def modulePath = createRepoPath(repo, orgName, pkgName);
//         def existingVersion = repositories.getChildren(modulePath)?.collect { it.name } ?: []
//         def existingSemvers = existingVersion.findAll { it ==~ SEMVER_PATTERN_EXT }
//         log.warn("Existing versions for package $pkgName : $existingVersion (semver: $existingSemvers)")
//         // Prevent uploading same or lower version
//         if (item.folder && packageData.size() == 3){
//             if(existingSemvers){
//                 def maxVersion = existingSemvers.max {a,b -> compareSemver(a,b)}
//                 if(pkgVersion == maxVersion){
//                     throw new Exception("Uploaded version $pkgVersion already exists for package $pkgName.")
//                 }
//                 if(compareSemver(pkgVersion, maxVersion) <= 0){
//                     throw new Exception("Uploaded version $pkgVersion is not greater than existing version $maxVersion for package $pkgName.")
//                 }
//             }
//             return
//         }

//         // Allow creating top-level package folder (org/package) on first push
//         if (item.folder && packageData.size() == 2) {
//             log.warn("Creating package folder for $pkgName under $orgName. Allowing creation.")
//             return
//         }

//         // Build a normalized repo-relative file path for the uploaded file
//         def fileRepoPath = pkgVersion ? createRepoPath(repo, orgName, pkgName, pkgVersion, fileName) : createRepoPath(repo, orgName, pkgName, fileName)
//         log.warn("ballerinaFilePath: $fileRepoPath , Repo : $repo, fileName: $fileName")

//         if (repositoryExists(fileRepoPath)){
//             log.warn("Resource $fileRepoPath already exists.")
//             throw new Exception("Resource $fileRepoPath already exists.$item")
//         }

//         // Validating uploaded file is .bala
//         if (!isBala(pathString) && !isAllowedSidecarUpload(pathString)) {
//             log.warn("Uploading files other than .bala is not allowed: $pathString")
//             throw new Exception("Uploading files other than .bala is not allowed.$item")
//         } else {
//             log.warn("Uploading .bala file to repo: $repo at path: $path")
//         }
//     })

//     afterCreate({item ->
//         if (item.folder) return;
//         def itemPath = item.getRepoPath();
//         def repoPath = item.getRepoKey();
//         def itemPathString = itemPath.toString()

//         // SECURITY FIX: Prevent infinite loops from plugin's own deployments
//         if (DEPLOYMENT_IN_PROGRESS.get()) {
//             log.debug("Skipping afterCreate - deployment already in progress for this thread")
//             return
//         }

//         // Skip system/plugin files to avoid recursive processing
//         if (isSystemManagedFile(itemPathString)) {
//             log.debug("Skipping afterCreate for system file: $itemPathString")
//             return
//         }

//         def packageData = itemPathString.split('/');
//         def repoOrg = safePathSplit(packageData,0);
//         def orgName = getOrgName(repoOrg)
//         def pkgName = safePathSplit(packageData,1);
//         def pkgVersion = safePathSplit(packageData,2);

//         // Guard: ensure we have enough path segments for processing
//         if (!pkgName || !pkgVersion) {
//             log.warn("afterCreate: insufficient path segments (need org/pkg/version), skipping for: $itemPathString")
//             return
//         }

//         if (itemPathString.endsWith('.bala')) {
//             InputStream balaStream = null
//             ZipInputStream zipStream = null
//             try {
//                 balaStream = repositories.getContent(itemPath).inputStream
//                 zipStream = new ZipInputStream(balaStream)

//                 boolean foundPackageJson = false
//                 boolean foundBomCdxJson = false
//                 ZipEntry entry
//                 while ((entry = zipStream.nextEntry) != null) {
//                     if (entry.name == 'package.json') {
//                         foundPackageJson = true
//                         byte[] packageJsonContent = readCurrentZipEntryBytes(zipStream)
//                         RepoPathFactory.create(repoPath, "${orgName}/${pkgName}/${pkgVersion}/package.json").with { packageJsonPath ->
//                             repositories.deploy(packageJsonPath, new ByteArrayInputStream(packageJsonContent))
//                         }

//                         log.warn("Found package.json in uploaded .bala file: $itemPathString")
//                     }

//                     if (entry.name == 'bom.cdx.json') {
//                         foundBomCdxJson = true
//                         byte[] bomCdxJsonContent = readCurrentZipEntryBytes(zipStream)
//                          RepoPathFactory.create(repoPath, "${orgName}/${pkgName}/${pkgVersion}/bom.cdx.json").with { bomCdxJsonPath ->
//                             repositories.deploy(bomCdxJsonPath, new ByteArrayInputStream(bomCdxJsonContent))
//                         }
//                         log.warn("Found bom.cdx.json in uploaded .bala file: $itemPathString. This file will be ignored as it's not needed in Artifactory.")
//                     }

//                     if (foundPackageJson && foundBomCdxJson) {
//                         break;
//                     }
//                 }

//                 if (!foundPackageJson) {
//                     throw new Exception("Uploaded .bala file does not contain package.json: $itemPathString")
//                 }
//             } finally {
//                 if (zipStream != null) {
//                     zipStream.close()
//                 } else if (balaStream != null) {
//                     balaStream.close()
//                 }
//             }
//         }
        
//         // Set flag to prevent recursive calls
//         DEPLOYMENT_IN_PROGRESS.set(true)

//         try {
//             try {

//                 // Set Artifactory properties for better searchability
//                 repositories.setProperty(itemPath, 'ballerina.org', orgName)
//                 repositories.setProperty(itemPath, 'ballerina.package', pkgName)
//                 repositories.setProperty(itemPath, 'ballerina.version', pkgVersion)
                
//                 // Extract and set platform if it's a .bala file
//                 if (itemPathString.endsWith('.bala')) {
//                     def platform = extractPlatform(itemPath.name)
//                     if (platform) {
//                         repositories.setProperty(itemPath, 'ballerina.platform', platform)
//                         log.warn("Set platform property: $platform for $itemPath")
//                     }
//                 }
                
//                 log.warn("Set Artifactory properties for: $itemPath")

//                 // Create versions.json at package level with all available versions (with concurrency protection)
//                 deployVersionsJson(repoPath, orgName, pkgName)
//             } catch (Exception e) {
//                 log.error("Failed to create metadata or properties: ${e.class.simpleName}: ${e.message}")
//             }

//             // Creating checksum files for uploaded .bala files (PERFORMANCE FIX: stream-based)
//             if(!item.folder){
//                 try {
//                     log.warn("Generating checksums for file at path: $itemPath.name");
                    
//                     // Use stream-based approach to avoid loading entire file into memory
//                     def checksums = [:]
//                     ['sha256', 'sha1', 'md5'].each { algo ->
//                         def contentStream = repositories.getContent(itemPath)
//                         checksums[algo] = digestFromStream(algo, contentStream.inputStream)
//                     }
                    
//                     log.warn("Uploading checksum files for: $itemPath.name");
//                     checksums.each { algo, hashValue ->
//                         def checksumFilePath = RepoPathFactory.create(repoPath, itemPath.path + ".${algo}");
//                         repositories.deploy(checksumFilePath, new ByteArrayInputStream(hashValue.getBytes('UTF-8')));
//                         log.warn("Created checksum file at: $checksumFilePath with $algo: $hashValue");
//                     }
//                 } catch (Exception e) {
//                     log.error("Could not create checksum for item: $item - ${e.class.simpleName}: ${e.message}")
//                 }
//             }
//         } finally {
//             // Always clear the flag when done
//             DEPLOYMENT_IN_PROGRESS.set(false)
//         }
//     })

//     beforeDelete({ items ->
//         items.each { item ->
//             if (item.folder) return
            
//             def itemPath = item.getRepoPath()
//             def repoKey = item.getRepoKey()
//             def itemPathString = itemPath.toString()
            
//             // Skip system/plugin files
//             if (isSystemManagedFile(itemPathString)) {
//                 log.debug("Skipping beforeDelete for system file: $itemPathString")
//                 return
//             }
            
//             def packageData = itemPathString.split('/')
//             def repoOrg = safePathSplit(packageData, 0)
//             def orgName = getOrgName(repoOrg)
//             def pkgName = safePathSplit(packageData, 1)
//             def pkgVersion = safePathSplit(packageData, 2)
            
//             // If a version folder is being deleted, update versions.json
//             if (pkgName && pkgVersion) {
//                 try {
//                     // Check if this is a version deletion
//                     def versionPath = createRepoPath(repoKey, orgName, pkgName, pkgVersion)
//                     if (repositories.exists(versionPath)) {
//                         log.warn("Version folder will be deleted: $pkgVersion from $pkgName. Scheduling versions.json update.")
//                     }
//                 } catch (Exception e) {
//                     log.error("Error in beforeDelete for path $itemPathString: ${e.message}")
//                 }
//             }
//         }
//     })
    
//     afterDelete({ items ->
//         items.each { item ->
//             def itemPath = item.getRepoPath()
//             def repoKey = item.getRepoKey()
//             def itemPathString = itemPath.toString()
            
//             // Skip system/plugin files
//             if (isSystemManagedFile(itemPathString)) {
//                 log.debug("Skipping afterDelete for system file: $itemPathString")
//                 return
//             }
            
//             def packageData = itemPathString.split('/')
//             def repoOrg = safePathSplit(packageData, 0)
//             def orgName = getOrgName(repoOrg)
//             def pkgName = safePathSplit(packageData, 1)
//             def pkgVersion = safePathSplit(packageData, 2)
            
//             // Update versions.json if a version was deleted
//             if (pkgName && pkgVersion) {
//                 try {
//                     log.warn("Deleted version: $pkgVersion. Updating versions.json with concurrency protection")
//                     deployVersionsJson(repoKey, orgName, pkgName)
//                 } catch (Exception e) {
//                     log.error("Failed to update versions.json after deletion: ${e.class.simpleName}: ${e.message}")
//                 }
//             }
//         }
//     })
// })

// download({
//     beforeDownloadRequest({ request, repoPath->
//         def befDownloadPath = repoPath.toString();
//         def splitPath = befDownloadPath.split('/');
//         def orgRepo = safePathSplit(splitPath,0);
//         def orgNameRequest = getOrgName(orgRepo)
//         def pkgNameRequest = safePathSplit(splitPath,1);
//         def requestedFile = repoPath.name;
//         def requestedPath = repoPath.path;
//         def requestedRepo = repoPath.getRepoKey();

//         if(!orgNameRequest || !pkgNameRequest){
//             log.warn("Invalid download request path: $befDownloadPath; missing org or package name.")
//             return
//         }
//         // Break down the requested file name into parts
//         def fileTokenize = requestedFile ? requestedFile.tokenize('.') : [];
//         log.warn("Attempting to download item at path: $befDownloadPath from repo: $requestedRepo requestedPath: $requestedPath requestedFile: $requestedFile");

//         // Check if the request is for a folder/module or a specific file/version
//         if (requestedFile == null || requestedFile.trim() == '' || fileTokenize.size() < 3) {
//             // Apply the retrieve highest compatible version logic

//             log.warn("Folder/module requested (no specific file/version): $requestedPath")
//             def requestModulePath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest)
//             def requestExistingVersions = repositories.getChildren(requestModulePath)?.collect { it.name } ?: []
//             log.warn("Existing versions for module at path $requestModulePath : $requestExistingVersions");
//                 // filter only valid semver entries (extended SemVer)
//                 def semvers = requestExistingVersions.findAll { it ==~ SEMVER_PATTERN_EXT }
//             if (!semvers) {
//                 log.warn("No semver versions found for module at $requestModulePath; cannot redirect")
//                 return
//             }
//             def highestVersion = semvers.max {a,b -> compareSemver(a,b)}
//             log.warn("Highest compatible version determined: $highestVersion")
            
//             // Extract platform preference from the original request if present
//             def requestedPlatform = null
//             if (requestedPath) {
//                 def pathParts = requestedPath.tokenize('/')
//                 if (pathParts.size() >= 3) {
//                     requestedPlatform = extractPlatform(pathParts[-1])
//                 }
//             }
            
//             def balaName = findBalaFile(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, requestedPlatform)
//             if (!balaName) {
//                 log.warn("No .bala file found under ${orgNameRequest}/${pkgNameRequest}/${highestVersion}; cannot redirect")
//                 return
//             }
//             // Create or serve versions.json file in the package folder
//             def versionsJsonPath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest, "versions.json")
//             if (repositories.exists(versionsJsonPath)) {
//                 log.warn("Serving versions.json for ${orgNameRequest}/${pkgNameRequest}")
//                 request.setRepoPath(versionsJsonPath)
//             } else {
//                 log.warn("versions.json not found; redirecting to highest version: $highestVersion")
//                 def newDownloadPath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, balaName)
//                 request.setRepoPath(newDownloadPath)
//             }
//             return
//         } else {
//                 def newDownloadPath = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest)
//                 def existingVersions = repositories.getChildren(newDownloadPath)?.collect { it.name } ?: []
//                 def findSemver = existingVersions.findAll { it ==~ SEMVER_PATTERN_EXT }
//                 if (!findSemver) {
//                     log.warn("No semver versions found for module at $newDownloadPath; cannot redirect")
//                     return
//                 }
//                 def highestVersion = findSemver.max {a,b -> compareSemver(a,b)}
//             // Extract platform from requested filename
//             def platformToken = extractPlatform(requestedFile)
//             log.warn("Extracted platform token: $platformToken from filename: $requestedFile")
            
//             def balaName = findBalaFile(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, platformToken)
//             if (!balaName) {
//                 log.warn("No .bala file found for ${orgNameRequest}/${pkgNameRequest}/${highestVersion}; cannot fulfill checksum request")
//                 return
//             }
//             if(requestedFile.endsWith('.sha1')){
//                 def sha1Path = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, balaName + ".sha1-file")
//                 log.warn("Requesting sha1 checksum:  $pkgNameRequest : $existingVersions");
//                 request.setRepoPath(sha1Path);

//             }
//             if(requestedFile.endsWith('.md5')){
//                 def md5Path = createRepoPath(requestedRepo, orgNameRequest, pkgNameRequest, highestVersion, balaName + ".md5-file")
//                 log.warn("Requesting md5 checksum:  $pkgNameRequest : $existingVersions");
//                 request.setRepoPath(md5Path);

//             }
//         }
//     })
// })