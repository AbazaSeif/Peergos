package peergos.shared.user.fs;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/** This class is used to read and modify files and directories and represents a single file or directory
 *
 */
public class FileWrapper {
	private static final Logger LOG = Logger.getGlobal();

    final static int THUMBNAIL_SIZE = 100;
    private final NativeJSThumbnail thumbnail;
    private final RetrievedCapability pointer;
    private final FileProperties props;
    private final String ownername;
    private final Optional<TrieNode> globalRoot;
    private final Set<String> readers;
    private final Set<String> writers;
    private final Optional<SecretSigningKey> entryWriterKey;
    private AtomicBoolean modified = new AtomicBoolean(); // This only used as a guard against concurrent modifications

    /**
     *
     * @param globalRoot This is only present if this is the global root
     * @param pointer
     * @param ownername
     * @param readers
     * @param writers
     * @param entryWriterKey
     */
    public FileWrapper(Optional<TrieNode> globalRoot, RetrievedCapability pointer, String ownername,
                       Set<String> readers, Set<String> writers, Optional<SecretSigningKey> entryWriterKey) {
        this.globalRoot = globalRoot;
        this.pointer = pointer == null ? null : pointer.withWriter(entryWriterKey);
        this.ownername = ownername;
        this.readers = readers;
        this.writers = writers;
        this.entryWriterKey = entryWriterKey;
        if (pointer == null)
            props = new FileProperties("/", "", 0, LocalDateTime.MIN, false, Optional.empty());
        else {
            SymmetricKey parentKey = this.getParentKey();
            props = pointer.fileAccess.getProperties(parentKey);
        }
        thumbnail = new NativeJSThumbnail();
    }

    public FileWrapper(RetrievedCapability pointer, String ownername,
                       Set<String> readers, Set<String> writers, Optional<SecretSigningKey> entryWriterKey) {
        this(Optional.empty(), pointer, ownername, readers, writers, entryWriterKey);
    }

    public FileWrapper withTrieNode(TrieNode trie) {
        return new FileWrapper(Optional.of(trie), pointer, ownername, readers, writers, entryWriterKey);
    }

    private FileWrapper withCryptreeNode(CryptreeNode access) {
        return new FileWrapper(globalRoot, new RetrievedCapability(getPointer().capability, access), ownername,
                readers, writers, entryWriterKey);
    }

    @JsMethod
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileWrapper))
            return false;
        return pointer.equals(((FileWrapper) other).getPointer());
    }

    public RetrievedCapability getPointer() {
        return pointer;
    }

    public boolean isRoot() {
        return props.name.equals("/");
    }

    public CompletableFuture<String> getPath(NetworkAccess network) {
        return retrieveParent(network).thenCompose(parent -> {
            if (!parent.isPresent() || parent.get().isRoot())
                return CompletableFuture.completedFuture("/" + props.name);
            return parent.get().getPath(network).thenApply(parentPath -> parentPath + "/" + props.name);
        });
    }

    public CompletableFuture<Optional<FileWrapper>> getDescendentByPath(String path, NetworkAccess network) {
        ensureUnmodified();
        if (path.length() == 0)
            return CompletableFuture.completedFuture(Optional.of(this));

        if (path.equals("/"))
            if (isDirectory())
                return CompletableFuture.completedFuture(Optional.of(this));
            else
                return CompletableFuture.completedFuture(Optional.empty());

        if (path.startsWith("/"))
            path = path.substring(1);
        int slash = path.indexOf("/");
        String prefix = slash > 0 ? path.substring(0, slash) : path;
        String suffix = slash > 0 ? path.substring(slash + 1) : "";
        return getChildren(network).thenCompose(children -> {
            for (FileWrapper child : children)
                if (child.getFileProperties().name.equals(prefix)) {
                    return child.getDescendentByPath(suffix, network);
                }
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    private void ensureUnmodified() {
        if (modified.get())
            throw new IllegalStateException("This file has already been modified, use the returned instance");
    }

    private void setModified() {
        if (modified.get())
            throw new IllegalStateException("This file has already been modified, use the returned instance");
        modified.set(true);
    }

    /**
     * Marks a file/directory and all its descendants as dirty. Directories are immediately cleaned,
     * but files have all their keys except the actual data key cleaned. That is cleaned lazily, the next time it is modified
     *
     * @param network
     * @param parent
     * @param readersToRemove
     * @return
     * @throws IOException
     */
    public CompletableFuture<FileWrapper> makeDirty(NetworkAccess network, SafeRandom random,
                                                    FileWrapper parent, Set<String> readersToRemove) {
        if (!isWritable())
            throw new IllegalStateException("You cannot mark a file as dirty without write access!");
        if (isDirectory()) {
            // create a new baseKey == subfoldersKey and make all descendants dirty
            SymmetricKey newSubfoldersKey = SymmetricKey.random();
            Capability ourNewPointer = pointer.capability.withBaseKey(newSubfoldersKey);
            SymmetricKey newParentKey = SymmetricKey.random();
            FileProperties props = getFileProperties();

            DirAccess existing = (DirAccess) pointer.fileAccess;

            // Create new DirAccess, but don't upload it
            DirAccess newDirAccess = DirAccess.create(existing.committedHash(), newSubfoldersKey, props, parent.pointer.capability.getLocation(),
                    parent.getParentKey(), newParentKey);
            // re add children

            List<Capability> children = existing.getChildren(pointer.capability.baseKey);
            return newDirAccess.addChildrenAndCommit(children, newSubfoldersKey, ourNewPointer, getSigner(), network, random)
                    .thenCompose(updatedDirAccess -> {
                        readers.removeAll(readersToRemove);
                        RetrievedCapability ourNewRetrievedPointer = new RetrievedCapability(ourNewPointer, updatedDirAccess);
                        FileWrapper theNewUs = new FileWrapper(ourNewRetrievedPointer,
                                ownername, readers, writers, entryWriterKey);

                        // clean all subtree keys except file dataKeys (lazily re-key and re-encrypt them)
                        return getChildren(network).thenCompose(childFiles -> {
                            List<CompletableFuture<FileWrapper>> cleanedChildren = childFiles.stream()
                                    .map(child -> child.makeDirty(network, random, theNewUs, readersToRemove))
                                    .collect(Collectors.toList());

                            return Futures.combineAll(cleanedChildren);
                        }).thenCompose(finished ->
                                // update pointer from parent to us
                                ((DirAccess) parent.pointer.fileAccess)
                                        .updateChildLink(parent.pointer.capability, this.pointer,
                                                ourNewRetrievedPointer, getSigner(), network, random)
                                        .thenApply(x -> theNewUs));
                    }).thenApply(x -> {
                        setModified();
                        return x;
                    });
        } else {
            // create a new baseKey == parentKey and mark the metaDataKey as dirty
            SymmetricKey parentKey = SymmetricKey.random();
            return ((FileAccess) pointer.fileAccess).markDirty(writableFilePointer(), parentKey, network).thenCompose(newFileAccess -> {

                // changing readers here will only affect the returned FileWrapper, as the readers are derived from the entry point
                TreeSet<String> newReaders = new TreeSet<>(readers);
                newReaders.removeAll(readersToRemove);
                RetrievedCapability newPointer = new RetrievedCapability(this.pointer.capability.withBaseKey(parentKey), newFileAccess);

                // update link from parent folder to file to have new baseKey
                return ((DirAccess) parent.pointer.fileAccess)
                        .updateChildLink(parent.writableFilePointer(), pointer, newPointer, getSigner(), network, random)
                        .thenApply(x -> new FileWrapper(newPointer, ownername, newReaders, writers, entryWriterKey));
            }).thenApply(x -> {
                setModified();
                return x;
            });
        }
    }

    public CompletableFuture<Boolean> hasChildWithName(String name, NetworkAccess network) {
        ensureUnmodified();
        return getChildren(network)
                .thenApply(children -> children.stream().filter(c -> c.props.name.equals(name)).findAny().isPresent());
    }

    public CompletableFuture<FileWrapper> removeChild(FileWrapper child, NetworkAccess network) {
        setModified();
        return ((DirAccess) pointer.fileAccess)
                .removeChild(child.getPointer(), pointer.capability, getSigner(), network)
                .thenApply(updated -> new FileWrapper(globalRoot,
                        new RetrievedCapability(getPointer().capability, updated), ownername, readers,
                        writers, entryWriterKey));
    }

    public CompletableFuture<FileWrapper> addLinkTo(FileWrapper file, NetworkAccess network, SafeRandom random) {
        ensureUnmodified();
        CompletableFuture<FileWrapper> error = new CompletableFuture<>();
        if (!this.isDirectory() || !this.isWritable()) {
            error.completeExceptionally(new IllegalArgumentException("Can only add link to a writable directory!"));
            return error;
        }
        String name = file.getFileProperties().name;
        return hasChildWithName(name, network).thenCompose(hasChild -> {
            if (hasChild) {
                error.completeExceptionally(new IllegalStateException("Child already exists with name: " + name));
                return error;
            }
            DirAccess toUpdate = (DirAccess) pointer.fileAccess;
            return toUpdate.addChildAndCommit(file.pointer.capability, this.getKey(),
                    pointer.capability, getSigner(), network, random)
                    .thenApply(dirAccess -> new FileWrapper(this.pointer, ownername, readers, writers, entryWriterKey));
        });
    }

    public CompletableFuture<FileWrapper> addReadOnlySharingLinkTo(FileWrapper file, NetworkAccess network, SafeRandom random,
                                                           Fragmenter fragmenter) {
        return addSharingLinkTo(file, network, random, fragmenter, CapabilityStore.READ_ONLY_SHARING_FILE_PREFIX,
                CapabilityStore.READ_CAPABILITY_SIZE, CapabilityStore.SHARING_READ_FILE_MAX_SIZE);
    }

    public CompletableFuture<FileWrapper> addEditSharingLinkTo(FileWrapper file, NetworkAccess network, SafeRandom random,
                                                                   Fragmenter fragmenter) {
        return addSharingLinkTo(file, network, random, fragmenter, CapabilityStore.EDIT_SHARING_FILE_PREFIX,
                CapabilityStore.EDIT_CAPABILITY_SIZE, CapabilityStore.SHARING_EDIT_FILE_MAX_SIZE);
    }

    public CompletableFuture<FileWrapper> addSharingLinkTo(FileWrapper file, NetworkAccess network, SafeRandom random,
                                                           Fragmenter fragmenter, String sharingPrefix,
                                                           int capabilitySize, int sharingFileMaxSize) {
        ensureUnmodified();
        if (!this.isDirectory() || !this.isWritable()) {
            CompletableFuture<FileWrapper> error = new CompletableFuture<>();
            error.completeExceptionally(new IllegalArgumentException("Can only add link to a writable directory!"));
            return error;
        }

        return this.getChildren(network)
            .thenCompose(children -> {
                List<FileWrapper> capabilityCacheFiles = children.stream()
                        .filter(f -> f.getName().startsWith(sharingPrefix))
                        .collect(Collectors.toList());
                List<FileWrapper> sharingFiles = capabilityCacheFiles.stream()
                        .sorted(Comparator.comparingInt(f -> Integer.parseInt(f.getFileProperties().name
                                .substring(sharingPrefix.length()))))
                        .collect(Collectors.toList());
                FileWrapper currentSharingFile = sharingFiles.isEmpty() ? null : sharingFiles.get(sharingFiles.size() - 1);
                byte[] serializedCapability = file.pointer.capability.readOnly().toCbor().toByteArray();
                if (serializedCapability.length != capabilitySize)
                    throw new IllegalArgumentException("Unexpected Capability length:" + serializedCapability.length);
                AsyncReader.ArrayBacked newCapability = new AsyncReader.ArrayBacked(serializedCapability);
                if (currentSharingFile != null
                        && currentSharingFile.getFileProperties().size + capabilitySize <= sharingFileMaxSize) {
                    long size = currentSharingFile.getSize();
                    return uploadFileSection(currentSharingFile.props.name, newCapability, size, size + serializedCapability.length,
                            Optional.of(currentSharingFile.pointer.capability.baseKey), true, network, random, x -> {}, fragmenter);
                } else {
                    int sharingFileIndex = currentSharingFile == null ? 0 : sharingFiles.size();
                    String capStoreFilename = sharingPrefix + sharingFileIndex;
                    return uploadFileSection(capStoreFilename, newCapability, 0, serializedCapability.length,
                            Optional.empty(), false, network, random, x -> {}, fragmenter);
                }
            });
    }

    @JsMethod
    public String toLink() {
        return pointer.capability.toLink();
    }

    @JsMethod
    public boolean isWritable() {
        return entryWriterKey.isPresent();
    }

    @JsMethod
    public boolean isReadable() {
        try {
            pointer.fileAccess.getMetaKey(pointer.capability.baseKey);
            return false;
        } catch (Exception e) {}
        return true;
    }

    public SymmetricKey getKey() {
        return pointer.capability.baseKey;
    }

    public Location getLocation() {
        return pointer.capability.getLocation();
    }

    private SigningPrivateKeyAndPublicHash getSigner() {
        if (! isWritable())
            throw new IllegalStateException("Can only get a signer for a writable directory!");

        return new SigningPrivateKeyAndPublicHash(getLocation().writer, entryWriterKey.get());
    }

    public Set<Location> getChildrenLocations() {
        ensureUnmodified();
        if (!this.isDirectory())
            return Collections.emptySet();
        return ((DirAccess) pointer.fileAccess).getChildrenLocations(pointer.capability.baseKey);
    }

    public CompletableFuture<Optional<FileWrapper>> retrieveParent(NetworkAccess network) {
        ensureUnmodified();
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        SymmetricKey parentKey = getParentKey();
        CompletableFuture<RetrievedCapability> parent = pointer.fileAccess.getParent(parentKey, network);
        return parent.thenApply(parentRFP -> {
            if (parentRFP == null)
                return Optional.empty();
            return Optional.of(new FileWrapper(parentRFP, ownername, Collections.emptySet(), Collections.emptySet(), entryWriterKey));
        });
    }

    public SymmetricKey getParentKey() {
        ensureUnmodified();
        SymmetricKey parentKey = pointer.capability.baseKey;
        if (this.isDirectory())
            try {
                parentKey = pointer.fileAccess.getParentKey(parentKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return parentKey;
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getChildren(NetworkAccess network) {
        ensureUnmodified();
        if (globalRoot.isPresent())
            return globalRoot.get().getChildren("/", network);
        if (isReadable()) {
            return retrieveChildren(network).thenApply(childrenRFPs -> {
                Set<FileWrapper> newChildren = childrenRFPs.stream()
                        .map(x -> new FileWrapper(x, ownername, readers, writers, entryWriterKey))
                        .collect(Collectors.toSet());
                return newChildren.stream().collect(Collectors.toSet());
            });
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    public CompletableFuture<Optional<FileWrapper>> getChild(String name, NetworkAccess network) {
        return getChildren(network)
                .thenApply(children -> children.stream().filter(f -> f.getName().equals(name)).findAny());
    }

    private CompletableFuture<Set<RetrievedCapability>> retrieveChildren(NetworkAccess network) {
        Capability capability = pointer.capability;
        CryptreeNode fileAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = capability.baseKey;

        if (isReadable())
            return ((DirAccess) fileAccess).getChildren(network, rootDirKey);
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    public CompletableFuture<FileWrapper> cleanUnreachableChildren(NetworkAccess network) {
        setModified();
        Capability capability = pointer.capability;
        CryptreeNode fileAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = capability.baseKey;

        if (isReadable())
            return ((DirAccess) fileAccess).cleanUnreachableChildren(network, rootDirKey, capability, getSigner())
                    .thenApply(da -> new FileWrapper(globalRoot,
                            new RetrievedCapability(capability, da), ownername, readers, writers, entryWriterKey));
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    @JsMethod
    public String getOwner() {
        return ownername;
    }

    @JsMethod
    public boolean isDirectory() {
        boolean isNull = pointer == null;
        return isNull || pointer.fileAccess.isDirectory();
    }

    public boolean isDirty() {
        ensureUnmodified();
        return pointer.fileAccess.isDirty(pointer.capability.baseKey);
    }

    /**
     *
     * @param network
     * @param random
     * @param parent
     * @param fragmenter
     * @return updated parent dir
     */
    public CompletableFuture<FileWrapper> clean(NetworkAccess network, SafeRandom random,
                                                FileWrapper parent, peergos.shared.user.fs.Fragmenter fragmenter) {
        if (!isDirty())
            return CompletableFuture.completedFuture(this);
        if (isDirectory()) {
            throw new IllegalStateException("Directories are never dirty (they are cleaned immediately)!");
        } else {
            FileProperties props = getFileProperties();
            SymmetricKey baseKey = pointer.capability.baseKey;
            // stream download and re-encrypt with new metaKey
            return getInputStream(network, random, l -> {}).thenCompose(in -> {
                byte[] tmp = new byte[16];
                new Random().nextBytes(tmp);
                String tmpFilename = ArrayOps.bytesToHex(tmp) + ".tmp";

                CompletableFuture<FileWrapper> reuploaded = parent.uploadFileSection(tmpFilename, in, 0, props.size,
                        Optional.of(baseKey), true, network, random, l -> {}, fragmenter);
                return reuploaded.thenCompose(upload -> upload.getDescendentByPath(tmpFilename, network)
                        .thenCompose(tmpChild -> tmpChild.get().rename(props.name, network, upload, true))
                        .thenApply(res -> {
                            setModified();
                            return res;
                        }));
            });
        }
    }

    @JsMethod
    public CompletableFuture<FileWrapper> uploadFileJS(String filename, AsyncReader fileData,
                                                       int lengthHi, int lengthLow,
                                                       boolean overwriteExisting,
                                                       NetworkAccess network, SafeRandom random,
                                                       ProgressConsumer<Long> monitor, Fragmenter fragmenter) {
        return uploadFileSection(filename, fileData, 0, lengthLow + ((lengthHi & 0xFFFFFFFFL) << 32),
                Optional.empty(), overwriteExisting, network, random, monitor, fragmenter);
    }

    public CompletableFuture<FileWrapper> uploadFile(String filename,
                                                     AsyncReader fileData,
                                                     long length,
                                                     NetworkAccess network,
                                                     SafeRandom random,
                                                     ProgressConsumer<Long> monitor,
                                                     Fragmenter fragmenter) {
        return uploadFileSection(filename, fileData, 0, length, Optional.empty(),
                true, network, random, monitor, fragmenter);
    }

    public CompletableFuture<FileWrapper> uploadFile(String filename,
                                                     AsyncReader fileData,
                                                     boolean isHidden,
                                                     long length,
                                                     boolean overwriteExisting,
                                                     NetworkAccess network,
                                                     SafeRandom random,
                                                     ProgressConsumer<Long> monitor,
                                                     Fragmenter fragmenter) {
        return uploadFileSection(filename, fileData, isHidden, 0, length, Optional.empty(),
                overwriteExisting, network, random, monitor, fragmenter);
    }

    public CompletableFuture<FileWrapper> uploadFileSection(String filename, AsyncReader fileData, long startIndex, long endIndex,
                                                            NetworkAccess network, SafeRandom random,
                                                            ProgressConsumer<Long> monitor, Fragmenter fragmenter) {
        return uploadFileSection(filename, fileData, startIndex, endIndex, Optional.empty(), true, network, random, monitor, fragmenter);
    }

    public CompletableFuture<FileWrapper> uploadFileSection(String filename,
                                                            AsyncReader fileData,
                                                            long startIndex,
                                                            long endIndex,
                                                            Optional<SymmetricKey> baseKey,
                                                            boolean overwriteExisting,
                                                            NetworkAccess network,
                                                            SafeRandom random,
                                                            ProgressConsumer<Long> monitor,
                                                            Fragmenter fragmenter) {
        return uploadFileSection(filename, fileData, false, startIndex, endIndex, baseKey,
                overwriteExisting, network, random, monitor, fragmenter);
    }

    public CompletableFuture<FileWrapper> uploadFileSection(String filename, AsyncReader fileData,
                                                            boolean isHidden,
                                                            long startIndex,
                                                            long endIndex,
                                                            Optional<SymmetricKey> baseKey,
                                                            boolean overwriteExisting,
                                                            NetworkAccess network,
                                                            SafeRandom random,
                                                            ProgressConsumer<Long> monitor,
                                                            Fragmenter fragmenter) {
        if (!isLegalName(filename)) {
            CompletableFuture<FileWrapper> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Illegal filename: " + filename));
            return res;
        }
        if (! isDirectory()) {
            CompletableFuture<FileWrapper> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Cannot upload a sub file to a file!"));
            return res;
        }
        return getDescendentByPath(filename, network).thenCompose(childOpt -> {
            if (childOpt.isPresent()) {
                if (! overwriteExisting)
                    throw new IllegalStateException("File already exists with name " + filename);
                return updateExistingChild(childOpt.get(), fileData, startIndex, endIndex, network, random, monitor, fragmenter);
            }
            if (startIndex > 0) {
                // TODO if startIndex > 0 prepend with a zero section
                throw new IllegalStateException("Unimplemented!");
            }
            SymmetricKey fileKey = baseKey.orElseGet(SymmetricKey::random);
            SymmetricKey fileMetaKey = SymmetricKey.random();
            SymmetricKey rootRKey = pointer.capability.baseKey;
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
            Location parentLocation = getLocation();
            int thumbnailSrcImageSize = startIndex == 0 && endIndex < Integer.MAX_VALUE ? (int) endIndex : 0;
            return generateThumbnail(network, fileData, thumbnailSrcImageSize, filename)
                    .thenCompose(thumbData -> fileData.reset()
                            .thenCompose(forMime -> calculateMimeType(forMime, endIndex)
                                    .thenCompose(mimeType -> fileData.reset().thenCompose(resetReader -> {
                                        FileProperties fileProps = new FileProperties(filename, mimeType, endIndex,
                                                LocalDateTime.now(), isHidden, Optional.of(thumbData));
                                        FileUploader chunks = new FileUploader(filename, mimeType, resetReader,
                                                startIndex, endIndex, fileKey, fileMetaKey, parentLocation, dirParentKey, monitor, fileProps,
                                                fragmenter);
                                        byte[] mapKey = random.randomBytes(32);
                                        Location nextChunkLocation = new Location(getLocation().owner, getLocation().writer, mapKey);
                                        return chunks.upload(network, random, parentLocation.owner, getSigner(), nextChunkLocation)
                                                .thenCompose(fileLocation -> {
                                                    Capability capability = new Capability(fileLocation, Optional.empty(), fileKey);
                                                    return addChildPointer(filename, capability, network, random, 2);
                                                });
                                    }))
                            )
                    );
        });
    }

    private CompletableFuture<FileWrapper> addChildPointer(String filename,
                                                           Capability childPointer,
                                                           NetworkAccess network,
                                                           SafeRandom random,
                                                           int retries) {
        CompletableFuture<FileWrapper> result = new CompletableFuture<>();
        ((DirAccess) pointer.fileAccess).addChildAndCommit(childPointer, pointer.capability.baseKey, pointer.capability, getSigner(), network, random)
                .thenAccept(uploadResult -> {
                    setModified();
                    result.complete(this.withCryptreeNode(uploadResult));
                }).exceptionally(e -> {

            if (e instanceof MutableTree.CasException || e.getCause() instanceof MutableTree.CasException) {
                // reload directory and try again
                network.getMetadata(getLocation()).thenCompose(opt -> {
                    DirAccess updatedUs = (DirAccess) opt.get();
                    // Check another file of same name hasn't been added in the concurrent change

                    RetrievedCapability updatedPointer = new RetrievedCapability(pointer.capability, updatedUs);
                    FileWrapper us = new FileWrapper(globalRoot, updatedPointer, ownername, readers, writers, entryWriterKey);
                    return us.getChildren(network).thenCompose(children -> {
                        Set<String> childNames = children.stream()
                                .map(f -> f.getName())
                                .collect(Collectors.toSet());
                        if (! childNames.contains(filename)) {
                            return us.addChildPointer(filename, childPointer, network, random, retries)
                                    .thenAccept(res -> {
                                        result.complete(res);
                                    });
                        }
                        String safeName = nextSafeReplacementFilename(filename, childNames);
                        // rename file in place as we've already uploaded it
                        return network.getMetadata(childPointer.location).thenCompose(renameOpt -> {
                            CryptreeNode fileToRename = renameOpt.get();
                            RetrievedCapability updatedChildPointer =
                                    new RetrievedCapability(childPointer, fileToRename);
                            FileWrapper toRename = new FileWrapper(Optional.empty(),
                                    updatedChildPointer, ownername, readers, writers, entryWriterKey);
                            return toRename.rename(safeName, network, us).thenCompose(usAgain ->
                                    ((DirAccess) usAgain.pointer.fileAccess)
                                            .addChildAndCommit(childPointer, pointer.capability.baseKey,
                                                    pointer.capability, getSigner(), network, random)
                                            .thenAccept(uploadResult -> {
                                                setModified();
                                                result.complete(this.withCryptreeNode(uploadResult));
                                            }));
                        });
                    });
                }).exceptionally(ex -> {
                    if ((e instanceof MutableTree.CasException ||
                            e.getCause() instanceof MutableTree.CasException) && retries > 0)
                        addChildPointer(filename, childPointer, network, random, retries - 1)
                                .thenApply(f -> result.complete(f))
                                .exceptionally(e2 -> {
                                    result.completeExceptionally(e2);
                                    return null;
                                });
                    else
                        result.completeExceptionally(e);
                    return null;
                });
            } else
                result.completeExceptionally(e);
            return null;
        });
        return result;
    }

    private static String nextSafeReplacementFilename(String desired, Set<String> existing) {
        if (! existing.contains(desired))
            return desired;
        for (int counter = 1; counter < 1000; counter++) {
            int dot = desired.lastIndexOf(".");
            String candidate = dot >= 0 ?
                    desired.substring(0, dot) + "[" + counter + "]" + desired.substring(dot) :
                    desired + "[" + counter + "]";
            if (! existing.contains(candidate))
                return candidate;
        }
        throw new IllegalStateException("Too many concurrent writes trying to add a file of the same name!");
    }

    private CompletableFuture<FileWrapper> updateExistingChild(FileWrapper existingChild, AsyncReader fileData,
                                                               long inputStartIndex, long endIndex,
                                                               NetworkAccess network, SafeRandom random,
                                                               ProgressConsumer<Long> monitor, Fragmenter fragmenter) {

        String filename = existingChild.getFileProperties().name;
        LOG.info("Overwriting section [" + Long.toHexString(inputStartIndex) + ", " + Long.toHexString(endIndex) + "] of child with name: " + filename);

        Supplier<Location> locationSupplier = () -> new Location(getLocation().owner, getLocation().writer, random.randomBytes(32));

        return (existingChild.isDirty() ?
                existingChild.clean(network, random, this, fragmenter)
                        .thenCompose(us -> us.getChild(filename, network)
                                .thenApply(cleanedChild -> new Pair<>(us, cleanedChild.get()))) :
                CompletableFuture.completedFuture(new Pair<>(this, existingChild))
        ).thenCompose(updatedPair -> {
            FileWrapper us = updatedPair.left;
            FileWrapper child = updatedPair.right;
            FileProperties childProps = child.getFileProperties();
            final AtomicLong filesSize = new AtomicLong(childProps.size);
            FileRetriever retriever = child.getRetriever();
            SymmetricKey baseKey = child.pointer.capability.baseKey;
            FileAccess fileAccess = (FileAccess) child.pointer.fileAccess;
            SymmetricKey dataKey = fileAccess.getDataKey(baseKey);

            List<Long> startIndexes = new ArrayList<>();

            for (long startIndex = inputStartIndex; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE))
                startIndexes.add(startIndex);


            boolean identity = true;

            BiFunction<Boolean, Long, CompletableFuture<Boolean>> composer = (id, startIndex) -> {
                return retriever.getChunkInputStream(network, random, dataKey, startIndex, filesSize.get(),
                        child.getLocation(), child.pointer.fileAccess.committedHash(), monitor)
                        .thenCompose(currentLocation -> {
                                    CompletableFuture<Optional<Location>> locationAt = retriever
                                            .getLocationAt(child.getLocation(), startIndex + Chunk.MAX_SIZE, dataKey, network);
                                    return locationAt.thenCompose(location ->
                                            CompletableFuture.completedFuture(new Pair<>(currentLocation, location)));
                                }
                        ).thenCompose(pair -> {

                            if (!pair.left.isPresent()) {
                                CompletableFuture<Boolean> result = new CompletableFuture<>();
                                result.completeExceptionally(new IllegalStateException("Current chunk not present"));
                                return result;
                            }

                            LocatedChunk currentOriginal = pair.left.get();
                            Optional<Location> nextChunkLocationOpt = pair.right;
                            Location nextChunkLocation = nextChunkLocationOpt.orElseGet(locationSupplier);
                            LOG.info("********** Writing to chunk at mapkey: " + ArrayOps.bytesToHex(currentOriginal.location.getMapKey()) + " next: " + nextChunkLocation);

                            // modify chunk, re-encrypt and upload
                            int internalStart = (int) (startIndex % Chunk.MAX_SIZE);
                            int internalEnd = endIndex - (startIndex - internalStart) > Chunk.MAX_SIZE ?
                                    Chunk.MAX_SIZE : (int) (endIndex - (startIndex - internalStart));
                            byte[] rawData = currentOriginal.chunk.data();
                            // extend data array if necessary
                            if (rawData.length < internalEnd)
                                rawData = Arrays.copyOfRange(rawData, 0, internalEnd);
                            byte[] raw = rawData;

                            return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart).thenCompose(read -> {

                                Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), dataKey.createNonce());
                                LocatedChunk located = new LocatedChunk(currentOriginal.location, currentOriginal.existingHash, updated);
                                long currentSize = filesSize.get();
                                FileProperties newProps = new FileProperties(childProps.name, childProps.mimeType,
                                        endIndex > currentSize ? endIndex : currentSize,
                                        LocalDateTime.now(), childProps.isHidden, childProps.thumbnail);

                                CompletableFuture<Multihash> chunkUploaded = FileUploader.uploadChunk(getSigner(),
                                        newProps, getLocation(), us.getParentKey(), baseKey, located,
                                        fragmenter,
                                        nextChunkLocation, network, monitor);

                                return chunkUploaded.thenCompose(isUploaded -> {
                                    //update indices to be relative to next chunk
                                    long updatedLength = startIndex + internalEnd - internalStart;
                                    if (updatedLength > filesSize.get()) {
                                        filesSize.set(updatedLength);

                                        if (updatedLength > Chunk.MAX_SIZE) {
                                            // update file size in FileProperties of first chunk
                                            return getChildren(network).thenCompose(children -> {
                                                Optional<FileWrapper> updatedChild = children.stream()
                                                        .filter(f -> f.getFileProperties().name.equals(filename))
                                                        .findAny();
                                                return updatedChild.get().setProperties(child.getFileProperties().withSize(endIndex), network, this);
                                            });
                                        }
                                    }
                                    return CompletableFuture.completedFuture(true);
                                });
                            });
                        });
            };

            BiFunction<Boolean, Boolean, Boolean> combiner = (left, right) -> left && right;
            return Futures.reduceAll(startIndexes, identity, composer, combiner)
                    .thenApply(b -> us);
        });
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
    }

    @JsMethod
    public CompletableFuture<Capability> mkdir(String newFolderName, NetworkAccess network, boolean isSystemFolder,
                                               SafeRandom random) {
        return mkdir(newFolderName, network, null, isSystemFolder, random);
    }

    public CompletableFuture<Capability> mkdir(String newFolderName,
                                               NetworkAccess network,
                                               SymmetricKey requestedBaseSymmetricKey,
                                               boolean isSystemFolder,
                                               SafeRandom random) {

        CompletableFuture<Capability> result = new CompletableFuture<>();
        if (!this.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("Cannot mkdir in a file!"));
            return result;
        }
        if (!isLegalName(newFolderName)) {
            result.completeExceptionally(new IllegalStateException("Illegal directory name: " + newFolderName));
            return result;
        }
        return hasChildWithName(newFolderName, network).thenCompose(hasChild -> {
            if (hasChild) {
                result.completeExceptionally(new IllegalStateException("Child already exists with name: " + newFolderName));
                return result;
            }
            Capability dirPointer = pointer.capability;
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            SymmetricKey rootDirKey = dirPointer.baseKey;
            return dirAccess.mkdir(newFolderName, network, dirPointer.location.owner, getSigner(), dirPointer.getLocation().getMapKey(), rootDirKey,
                    requestedBaseSymmetricKey, isSystemFolder, random).thenApply(x -> {
                setModified();
                return x;
            });
        });
    }

    @JsMethod
    public CompletableFuture<FileWrapper> rename(String newFilename, NetworkAccess network, FileWrapper parent) {
        return rename(newFilename, network, parent, false);
    }

    /**
     * @param newFilename
     * @param network
     * @param parent
     * @param overwrite
     * @return the updated parent
     */
    public CompletableFuture<FileWrapper> rename(String newFilename, NetworkAccess network,
                                                 FileWrapper parent, boolean overwrite) {
        setModified();
        if (! isLegalName(newFilename))
            return CompletableFuture.completedFuture(parent);
        CompletableFuture<Optional<FileWrapper>> childExists = parent == null ?
                CompletableFuture.completedFuture(Optional.empty()) :
                parent.getDescendentByPath(newFilename, network);
        return childExists
                .thenCompose(existing -> {
                    if (existing.isPresent() && !overwrite)
                        return CompletableFuture.completedFuture(parent);

                    return ((overwrite && existing.isPresent()) ?
                            existing.get().remove(network, parent) :
                            CompletableFuture.completedFuture(parent)
                    ).thenCompose(res -> {

                        //get current props
                        Capability capability = pointer.capability;
                        SymmetricKey baseKey = capability.baseKey;
                        CryptreeNode fileAccess = pointer.fileAccess;

                        SymmetricKey key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
                        FileProperties currentProps = fileAccess.getProperties(key);

                        FileProperties newProps = new FileProperties(newFilename, currentProps.mimeType, currentProps.size,
                                currentProps.modified, currentProps.isHidden, currentProps.thumbnail);

                        return fileAccess.updateProperties(writableFilePointer(), newProps, network)
                                .thenApply(fa -> res);
                    });
                });
    }

    public CompletableFuture<Boolean> setProperties(FileProperties updatedProperties, NetworkAccess network, FileWrapper parent) {
        setModified();
        String newName = updatedProperties.name;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!isLegalName(newName)) {
            result.completeExceptionally(new IllegalArgumentException("Illegal file name: " + newName));
            return result;
        }
        return (parent == null ?
                CompletableFuture.completedFuture(false) :
                parent.hasChildWithName(newName, network))
                .thenCompose(hasChild -> {
                    if (hasChild && parent != null && !parent.getChildrenLocations().stream()
                            .map(l -> new ByteArrayWrapper(l.getMapKey()))
                            .collect(Collectors.toSet())
                            .contains(new ByteArrayWrapper(pointer.capability.getLocation().getMapKey()))) {
                        result.completeExceptionally(new IllegalStateException("Cannot rename to same name as an existing file"));
                        return result;
                    }
                    CryptreeNode fileAccess = pointer.fileAccess;

                    return fileAccess.updateProperties(writableFilePointer(), updatedProperties, network)
                            .thenApply(fa -> true);
                });
    }

    private Capability writableFilePointer() {
        Capability capability = pointer.capability;
        SymmetricKey baseKey = capability.baseKey;
        return new Capability(capability.location, entryWriterKey, baseKey);
    }

    public Optional<SecretSigningKey> getEntryWriterKey() {
        return entryWriterKey;
    }

    @JsMethod
    public CompletableFuture<FileWrapper> copyTo(FileWrapper target,
                                                 NetworkAccess network,
                                                 SafeRandom random,
                                                 Fragmenter fragmenter) {
        ensureUnmodified();
        CompletableFuture<FileWrapper> result = new CompletableFuture<>();
        if (! target.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " must be a directory"));
            return result;
        }
        return target.hasChildWithName(getFileProperties().name, network).thenCompose(childExists -> {
            if (childExists) {
                result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " already has child with name " + getFileProperties().name));
                return result;
            }
            boolean sameWriter = getLocation().writer.equals(target.getLocation().writer);
            //make new FileWrapper pointing to the same file if we are the same writer, but with a different location
            if (sameWriter) {
                byte[] newMapKey = new byte[32];
                random.randombytes(newMapKey, 0, 32);
                SymmetricKey ourBaseKey = this.getKey();
                SymmetricKey newBaseKey = SymmetricKey.random();
                Capability newRFP = new Capability(target.getLocation().owner, target.getLocation().writer, newMapKey, newBaseKey);
                Location newParentLocation = target.getLocation();
                SymmetricKey newParentParentKey = target.getParentKey();

                return pointer.fileAccess.copyTo(ourBaseKey, newBaseKey, newParentLocation, newParentParentKey,
                        target.getLocation().writer, getSigner(), newMapKey, network, random)
                        .thenCompose(newAccess -> {
                            // upload new metadatablob
                            RetrievedCapability newRetrievedCapability = new RetrievedCapability(newRFP, newAccess);
                            FileWrapper newFileWrapper = new FileWrapper(newRetrievedCapability, target.getOwner(),
                                    Collections.emptySet(), Collections.emptySet(), target.getEntryWriterKey());
                            return target.addLinkTo(newFileWrapper, network, random);
                        });
            } else {
                return getInputStream(network, random, x -> {})
                        .thenCompose(stream -> target.uploadFileSection(getName(), stream, 0, getSize(),
                                Optional.empty(), false, network, random, x -> {}, fragmenter)
                        .thenApply(b -> target));
            }
        });
    }

    /**
     * @param network
     * @param parent
     * @return updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> remove(NetworkAccess network, FileWrapper parent) {
        ensureUnmodified();
        Supplier<CompletableFuture<Boolean>> supplier = () -> new RetrievedCapability(writableFilePointer(), pointer.fileAccess)
                .remove(network, null, getSigner());

        if (parent != null) {
            return parent.removeChild(this, network)
                    .thenCompose(updated -> supplier.get()
                            .thenApply(x -> updated));
        }
        return supplier.get().thenApply(x -> parent);
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network, SafeRandom random,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(network, random, getFileProperties().size, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   int fileSizeHi,
                                                                   int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(network, random, fileSizeLow + ((fileSizeHi & 0xFFFFFFFFL) << 32), monitor);
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        ensureUnmodified();
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        FileAccess fileAccess = (FileAccess) pointer.fileAccess;
        SymmetricKey baseKey = pointer.capability.baseKey;
        SymmetricKey dataKey = fileAccess.getDataKey(baseKey);
        return fileAccess.retriever().getFile(network, random, dataKey, fileSize, getLocation(), fileAccess.committedHash(), monitor);
    }

    private FileRetriever getRetriever() {
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        FileAccess fileAccess = (FileAccess) pointer.fileAccess;
        return fileAccess.retriever();
    }

    @JsMethod
    public String getBase64Thumbnail() {
        Optional<byte[]> thumbnail = props.thumbnail;
        if (thumbnail.isPresent()) {
            String base64Data = Base64.getEncoder().encodeToString(thumbnail.get());
            return "data:image/png;base64," + base64Data;
        } else {
            return "";
        }
    }

    @JsMethod
    public FileProperties getFileProperties() {
        ensureUnmodified();
        return props;
    }

    public String getName() {
        return getFileProperties().name;
    }

    public long getSize() {
        return getFileProperties().size;
    }

    public String toString() {
        return getFileProperties().name;
    }

    public static FileWrapper createRoot(TrieNode root) {
        return new FileWrapper(Optional.of(root), null, null, Collections.EMPTY_SET, Collections.EMPTY_SET, null);
    }

    public static byte[] generateThumbnail(byte[] imageBlob) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBlob));
            BufferedImage thumbnailImage = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, image.getType());
            Graphics2D g = thumbnailImage.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnailImage, "JPG", baos);
            baos.close();
            return baos.toByteArray();
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
        }
        return new byte[0];
    }

    public static byte[] generateVideoThumbnail(byte[] videoBlob) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile(UUID.randomUUID().toString(), ".mp4");
            Files.write(tempFile.toPath(), videoBlob, StandardOpenOption.WRITE);
            return VideoThumbnail.create(tempFile.getAbsolutePath(), THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
        } finally {
            if(tempFile != null) {
                try {
                    Files.delete(tempFile.toPath());
                }catch(IOException ioe){

                }
            }
        }
        return new byte[0];
    }

    private CompletableFuture<byte[]> generateThumbnail(NetworkAccess network, AsyncReader fileData, int fileSize, String filename) {
        CompletableFuture<byte[]> fut = new CompletableFuture<>();
        if (fileSize > MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE) {
            getFileType(fileData).thenAccept(mimeType -> {
                if (mimeType.startsWith("image")) {
                    if (network.isJavascript()) {
                        thumbnail.generateThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                            byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                            fut.complete(bytesOfData);
                        });
                    } else {
                        byte[] bytes = new byte[fileSize];
                        fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                            fut.complete(generateThumbnail(bytes));
                        });
                    }
                } else if (mimeType.startsWith("video")) {
                    if (network.isJavascript()) {
                        thumbnail.generateVideoThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                            byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                            fut.complete(bytesOfData);
                        });
                    } else {
                        byte[] bytes = new byte[fileSize];
                        fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                            fut.complete(generateVideoThumbnail(bytes));
                        });
                    }
                } else if (mimeType.startsWith("audio/mpeg")) {
                    byte[] mp3Data = new byte[fileSize];
                    fileData.readIntoArray(mp3Data, 0, fileSize).thenAccept(read -> {
                        Mp3CoverImage mp3CoverImage = Mp3CoverImage.extractCoverArt(mp3Data);
                        if (network.isJavascript()) {
                            AsyncReader.ArrayBacked imageBlob = new AsyncReader.ArrayBacked(mp3CoverImage.imageData);
                            thumbnail.generateThumbnail(imageBlob, mp3CoverImage.imageData.length, filename)
                                    .thenAccept(base64Str -> {
                                        byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                                        fut.complete(bytesOfData);
                                    });
                        } else {
                            fut.complete(generateThumbnail(mp3CoverImage.imageData));
                        }
                    });
                } else {
                    fut.complete(new byte[0]);
                }
            });
        } else {
            fut.complete(new byte[0]);
        }
        return fut;
    }

    private CompletableFuture<String> getFileType(AsyncReader imageBlob) {
        CompletableFuture<String> result = new CompletableFuture<>();
        byte[] data = new byte[MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE];
        imageBlob.readIntoArray(data, 0, data.length).thenAccept(numBytesRead -> {
            imageBlob.reset().thenAccept(resetResult -> {
                if (numBytesRead < data.length) {
                    result.complete("");
                } else {
                    String mimeType = MimeTypes.calculateMimeType(data);
                    result.complete(mimeType);
                }
            });
        });
        return result;
    }

    public static CompletableFuture<String> calculateMimeType(AsyncReader data, long fileSize) {
        byte[] header = new byte[(int) Math.min(fileSize, MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE)];
        return data.readIntoArray(header, 0, header.length)
                .thenApply(read -> MimeTypes.calculateMimeType(header));
    }
}
