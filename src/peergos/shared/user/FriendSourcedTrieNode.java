package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class FriendSourcedTrieNode implements TrieNode {

    private final String owner;
    private final Supplier<CompletableFuture<FileWrapper>> homeDirSupplier;
    private final EntryPoint sharedDir;
    private final SafeRandom random;
    private final Fragmenter fragmenter;
    private TrieNode root;
    private long capCountReadOnly;
    private long capCountEdit;


    public FriendSourcedTrieNode(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                 String owner,
                                 EntryPoint sharedDir,
                                 TrieNode root,
                                 long capCountReadOnly,
                                 long capCountEdit,
                                 SafeRandom random,
                                 Fragmenter fragmenter) {
        this.homeDirSupplier = homeDirSupplier;
        this.owner = owner;
        this.sharedDir = sharedDir;
        this.root = root;
        this.capCountReadOnly = capCountReadOnly;
        this.capCountEdit = capCountEdit;
        this.random = random;
        this.fragmenter = fragmenter;
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           SafeRandom random,
                                                                           Fragmenter fragmenter) {
        return network.retrieveEntryPoint(e)
                .thenCompose(sharedDirOpt -> {
                    if (! sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return CapabilityStore.loadReadAccessSharingLinks(homeDirSupplier, sharedDirOpt.get(), e.owner,
                            network, random, fragmenter, true)
                            .thenCompose(readCaps -> {
                                return CapabilityStore.loadWriteAccessSharingLinks(homeDirSupplier, sharedDirOpt.get(), e.owner,
                                        network, random, fragmenter, true)
                                        .thenApply(writeCaps -> {
                                            //kev todo this not correct!!
                                            List<CapabilityWithPath> allCaps = new ArrayList<>();
                                            allCaps.addAll(readCaps.getRetrievedCapabilities());
                                            allCaps.addAll(writeCaps.getRetrievedCapabilities());
                                            return Optional.of(new FriendSourcedTrieNode(homeDirSupplier,
                                                    e.owner,
                                                    e,
                                                    allCaps.stream()
                                                            .reduce(TrieNodeImpl.empty(),
                                                                    (root, cap) -> root.put(trimOwner(cap.path), UserContext.convert(e.owner, cap)),
                                                                    (a, b) -> a),
                                                    readCaps.getRecordsRead(), writeCaps.getRecordsRead(), random, fragmenter));
                                        });
                            });

                });
    }

    private synchronized CompletableFuture<Boolean> ensureUptodate(NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (!sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(true);
                    return CapabilityStore.getReadOnlyCapabilityCount(sharedDirOpt.get(), network)
                            .thenCompose(count -> {
                                if (count == capCountReadOnly)
                                    return CompletableFuture.completedFuture(true);
                                return CapabilityStore.loadReadAccessSharingLinksFromIndex(homeDirSupplier, sharedDirOpt.get(),
                                        owner, network, random, fragmenter, capCountReadOnly, true)
                                        .thenCompose(newReadCaps -> {
                                            capCountReadOnly += newReadCaps.getRecordsRead();
                                            root = newReadCaps.getRetrievedCapabilities().stream()
                                                    .reduce(root,
                                                            (root, cap) -> root.put(trimOwner(cap.path), UserContext.convert(owner, cap)),
                                                            (a, b) -> a);
                                            return CapabilityStore.getEditableCapabilityCount(sharedDirOpt.get(), network)
                                                    .thenCompose(editCount -> {
                                                        if (editCount == capCountEdit)
                                                            return CompletableFuture.completedFuture(true);
                                                        //todo kev this is not correct!!!
                                                        return CapabilityStore.loadWriteAccessSharingLinksFromIndex(homeDirSupplier, sharedDirOpt.get(),
                                                                owner, network, random, fragmenter, capCountEdit, true)
                                                                .thenApply(newWriteCaps -> {
                                                                    capCountEdit += newWriteCaps.getRecordsRead();
                                                                    root = newWriteCaps.getRetrievedCapabilities().stream()
                                                                            .reduce(root,
                                                                                    (root, cap) -> root.put(trimOwner(cap.path), UserContext.convert(owner, cap)),
                                                                                    (a, b) -> a);
                                                                    return true;
                                                                });
                                        });
                                });
                            });
                });
    }

    private CompletableFuture<Optional<FileWrapper>> getFriendRoot(NetworkAccess network) {
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (! sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return sharedDirOpt.get().retrieveParent(network)
                            .thenCompose(sharedOpt -> {
                                if (! sharedOpt.isPresent()) {
                                    CompletableFuture<Optional<FileWrapper>> empty = CompletableFuture.completedFuture(Optional.empty());
                                    return empty;
                                }
                                return sharedOpt.get().retrieveParent(network);
                            });
                });
    }

    private static String trimOwner(String path) {
        path = TrieNode.canonicalise(path);
        return path.substring(path.indexOf("/") + 1);
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path, NetworkAccess network) {
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        return ensureUptodate(network).thenCompose(x -> root.getByPath(path, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, NetworkAccess network) {
        return ensureUptodate(network).thenCompose(x -> root.getChildren(path, network));
    }

    @Override
    public synchronized Set<String> getChildNames() {
        return root.getChildNames();
    }

    @Override
    public synchronized TrieNode put(String path, EntryPoint e) {
        root = root.put(path, e);
        return this;
    }

    @Override
    public synchronized TrieNode putNode(String path, TrieNode t) {
        root = root.putNode(path, t);
        return this;
    }

    @Override
    public synchronized TrieNode removeEntry(String path) {
        root = root.removeEntry(path);
        return this;
    }

    @Override
    public boolean isEmpty() {
        return root.isEmpty();
    }
}
