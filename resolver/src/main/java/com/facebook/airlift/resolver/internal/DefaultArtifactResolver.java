package com.facebook.airlift.resolver.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.RepositoryEvent.EventType;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.impl.OfflineController;
import org.eclipse.aether.impl.RemoteRepositoryFilterManager;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.impl.RepositoryConnectorProvider;
import org.eclipse.aether.impl.RepositoryEventDispatcher;
import org.eclipse.aether.impl.UpdateCheck;
import org.eclipse.aether.impl.UpdateCheckManager;
import org.eclipse.aether.impl.VersionResolver;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.LocalArtifactRegistration;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;
import org.eclipse.aether.spi.connector.ArtifactDownload;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilter;
import org.eclipse.aether.spi.io.FileProcessor;
import org.eclipse.aether.spi.locator.Service;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.spi.resolution.ArtifactResolverPostProcessor;
import org.eclipse.aether.spi.synccontext.SyncContextFactory;
import org.eclipse.aether.transfer.ArtifactFilteredOutException;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;
import org.eclipse.aether.transfer.RepositoryOfflineException;
import org.eclipse.aether.util.ConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Named
public class DefaultArtifactResolver implements ArtifactResolver, Service {
    private static final String CONFIG_PROP_SNAPSHOT_NORMALIZATION = "aether.artifactResolver.snapshotNormalization";
    private static final String CONFIG_PROP_SIMPLE_LRM_INTEROP = "aether.artifactResolver.simpleLrmInterop";
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultArtifactResolver.class);
    private FileProcessor fileProcessor;
    private RepositoryEventDispatcher repositoryEventDispatcher;
    private VersionResolver versionResolver;
    private UpdateCheckManager updateCheckManager;
    private RepositoryConnectorProvider repositoryConnectorProvider;
    private RemoteRepositoryManager remoteRepositoryManager;
    private SyncContextFactory syncContextFactory;
    private OfflineController offlineController;
    private Map<String, ArtifactResolverPostProcessor> artifactResolverPostProcessors;
    private RemoteRepositoryFilterManager remoteRepositoryFilterManager;

    public DefaultArtifactResolver() {
    }

    @Inject
    DefaultArtifactResolver(FileProcessor fileProcessor, RepositoryEventDispatcher repositoryEventDispatcher, VersionResolver versionResolver, UpdateCheckManager updateCheckManager, RepositoryConnectorProvider repositoryConnectorProvider, RemoteRepositoryManager remoteRepositoryManager, SyncContextFactory syncContextFactory, OfflineController offlineController, Map<String, ArtifactResolverPostProcessor> artifactResolverPostProcessors, RemoteRepositoryFilterManager remoteRepositoryFilterManager) {
        this.setFileProcessor(fileProcessor);
        this.setRepositoryEventDispatcher(repositoryEventDispatcher);
        this.setVersionResolver(versionResolver);
        this.setUpdateCheckManager(updateCheckManager);
        this.setRepositoryConnectorProvider(repositoryConnectorProvider);
        this.setRemoteRepositoryManager(remoteRepositoryManager);
        this.setSyncContextFactory(syncContextFactory);
        this.setOfflineController(offlineController);
        this.setArtifactResolverPostProcessors(artifactResolverPostProcessors);
        this.setRemoteRepositoryFilterManager(remoteRepositoryFilterManager);
    }

    public void initService(ServiceLocator locator) {
        this.setFileProcessor((FileProcessor)locator.getService(FileProcessor.class));
        this.setRepositoryEventDispatcher((RepositoryEventDispatcher)locator.getService(RepositoryEventDispatcher.class));
        this.setVersionResolver((VersionResolver)locator.getService(VersionResolver.class));
        this.setUpdateCheckManager((UpdateCheckManager)locator.getService(UpdateCheckManager.class));
        this.setRepositoryConnectorProvider((RepositoryConnectorProvider)locator.getService(RepositoryConnectorProvider.class));
        this.setRemoteRepositoryManager((RemoteRepositoryManager)locator.getService(RemoteRepositoryManager.class));
        this.setSyncContextFactory((SyncContextFactory)locator.getService(SyncContextFactory.class));
        this.setOfflineController((OfflineController)locator.getService(OfflineController.class));
        this.setArtifactResolverPostProcessors(Collections.emptyMap());
        this.setRemoteRepositoryFilterManager((RemoteRepositoryFilterManager)locator.getService(RemoteRepositoryFilterManager.class));
    }

    /** @deprecated */
    @Deprecated
    public DefaultArtifactResolver setLoggerFactory(org.eclipse.aether.spi.log.LoggerFactory loggerFactory) {
        return this;
    }

    public DefaultArtifactResolver setFileProcessor(FileProcessor fileProcessor) {
        this.fileProcessor = (FileProcessor)Objects.requireNonNull(fileProcessor, "file processor cannot be null");
        return this;
    }

    public DefaultArtifactResolver setRepositoryEventDispatcher(RepositoryEventDispatcher repositoryEventDispatcher) {
        this.repositoryEventDispatcher = (RepositoryEventDispatcher)Objects.requireNonNull(repositoryEventDispatcher, "repository event dispatcher cannot be null");
        return this;
    }

    public DefaultArtifactResolver setVersionResolver(VersionResolver versionResolver) {
        this.versionResolver = (VersionResolver)Objects.requireNonNull(versionResolver, "version resolver cannot be null");
        return this;
    }

    public DefaultArtifactResolver setUpdateCheckManager(UpdateCheckManager updateCheckManager) {
        this.updateCheckManager = (UpdateCheckManager)Objects.requireNonNull(updateCheckManager, "update check manager cannot be null");
        return this;
    }

    public DefaultArtifactResolver setRepositoryConnectorProvider(RepositoryConnectorProvider repositoryConnectorProvider) {
        this.repositoryConnectorProvider = (RepositoryConnectorProvider)Objects.requireNonNull(repositoryConnectorProvider, "repository connector provider cannot be null");
        return this;
    }

    public DefaultArtifactResolver setRemoteRepositoryManager(RemoteRepositoryManager remoteRepositoryManager) {
        this.remoteRepositoryManager = (RemoteRepositoryManager)Objects.requireNonNull(remoteRepositoryManager, "remote repository provider cannot be null");
        return this;
    }

    public DefaultArtifactResolver setSyncContextFactory(SyncContextFactory syncContextFactory) {
        this.syncContextFactory = (SyncContextFactory)Objects.requireNonNull(syncContextFactory, "sync context factory cannot be null");
        return this;
    }

    public DefaultArtifactResolver setOfflineController(OfflineController offlineController) {
        this.offlineController = (OfflineController)Objects.requireNonNull(offlineController, "offline controller cannot be null");
        return this;
    }

    public DefaultArtifactResolver setArtifactResolverPostProcessors(Map<String, ArtifactResolverPostProcessor> artifactResolverPostProcessors) {
        this.artifactResolverPostProcessors = (Map)Objects.requireNonNull(artifactResolverPostProcessors, "artifact resolver post-processors cannot be null");
        return this;
    }

    public DefaultArtifactResolver setRemoteRepositoryFilterManager(RemoteRepositoryFilterManager remoteRepositoryFilterManager) {
        this.remoteRepositoryFilterManager = (RemoteRepositoryFilterManager)Objects.requireNonNull(remoteRepositoryFilterManager, "remote repository filter manager cannot be null");
        return this;
    }

    public ArtifactResult resolveArtifact(RepositorySystemSession session, ArtifactRequest request) throws ArtifactResolutionException {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(session, "session cannot be null");
        return (ArtifactResult)this.resolveArtifacts(session, Collections.singleton(request)).get(0);
    }

    public List<ArtifactResult> resolveArtifacts(RepositorySystemSession session, Collection<? extends ArtifactRequest> requests) throws ArtifactResolutionException {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(session, "session cannot be null");
        SyncContext syncContext = this.syncContextFactory.newInstance(session, false);

        List var9;
        try {
            Collection<Artifact> artifacts = new ArrayList(requests.size());
            Iterator var5 = requests.iterator();

            while(var5.hasNext()) {
                ArtifactRequest request = (ArtifactRequest)var5.next();
                if (request.getArtifact().getProperty("localPath", (String)null) == null) {
                    artifacts.add(request.getArtifact());
                }
            }

            syncContext.acquire(artifacts, (Collection)null);
            var9 = this.resolve(session, requests);
        } catch (Throwable var8) {
            if (syncContext != null) {
                try {
                    syncContext.close();
                } catch (Throwable var7) {
                    var8.addSuppressed(var7);
                }
            }

            throw var8;
        }

        if (syncContext != null) {
            syncContext.close();
        }

        return var9;
    }

    private List<ArtifactResult> resolve(RepositorySystemSession session, Collection<? extends ArtifactRequest> requests) throws ArtifactResolutionException {
        List<ArtifactResult> results = new ArrayList(requests.size());
        boolean failures = false;
        boolean simpleLrmInterop = ConfigUtils.getBoolean(session, false, new String[]{"aether.artifactResolver.simpleLrmInterop"});
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        WorkspaceReader workspace = session.getWorkspaceReader();
        List<DefaultArtifactResolver.ResolutionGroup> groups = new ArrayList();
        RemoteRepositoryFilter filter = this.remoteRepositoryFilterManager.getRemoteRepositoryFilter(session);
        Iterator var10 = requests.iterator();

        while(true) {
            label169:
            while(var10.hasNext()) {
                ArtifactRequest request = (ArtifactRequest)var10.next();
                RequestTrace trace = RequestTrace.newChild(request.getTrace(), request);
                ArtifactResult result = new ArtifactResult(request);
                results.add(result);
                Artifact artifact = request.getArtifact();
                this.artifactResolving(session, trace, artifact);
                String localPath = artifact.getProperty("localPath", (String)null);
                if (localPath != null) {
                    File file = new File(localPath);
                    if (!file.isFile()) {
                        failures = true;
                        result.addException(new ArtifactNotFoundException(artifact, (RemoteRepository)null));
                    } else {
                        artifact = artifact.setFile(file);
                        result.setArtifact(artifact);
                        this.artifactResolved(session, trace, artifact, (ArtifactRepository)null, result.getExceptions());
                    }
                } else {
                    List<RemoteRepository> remoteRepositories = request.getRepositories();
                    List<RemoteRepository> filteredRemoteRepositories = new ArrayList(remoteRepositories);
                    if (filter != null) {
                        Iterator var18 = remoteRepositories.iterator();

                        while(var18.hasNext()) {
                            RemoteRepository repository = (RemoteRepository)var18.next();
                            RemoteRepositoryFilter.Result filterResult = filter.acceptArtifact(repository, artifact);
                            if (!filterResult.isAccepted()) {
                                result.addException(new ArtifactFilteredOutException(artifact, repository, filterResult.reasoning()));
                                ((List)filteredRemoteRepositories).remove(repository);
                            }
                        }
                    }

                    VersionResult versionResult;
                    try {
                        VersionRequest versionRequest = new VersionRequest(artifact, (List)filteredRemoteRepositories, request.getRequestContext());
                        versionRequest.setTrace(trace);
                        versionResult = this.versionResolver.resolveVersion(session, versionRequest);
                    } catch (VersionResolutionException var28) {
                        VersionResolutionException e = var28;
                        result.addException(e);
                        continue;
                    }

                    artifact = artifact.setVersion(versionResult.getVersion());
                    if (versionResult.getRepository() != null) {
                        if (versionResult.getRepository() instanceof RemoteRepository) {
                            filteredRemoteRepositories = Collections.singletonList((RemoteRepository)versionResult.getRepository());
                        } else {
                            filteredRemoteRepositories = Collections.emptyList();
                        }
                    }

                    if (workspace != null) {
                        File file = workspace.findArtifact(artifact);
                        if (file != null) {
                            artifact = artifact.setFile(file);
                            result.setArtifact(artifact);
                            result.setRepository(workspace.getRepository());
                            this.artifactResolved(session, trace, artifact, result.getRepository(), (List)null);
                            continue;
                        }
                    }

                    LocalArtifactResult local = lrm.find(session, new LocalArtifactRequest(artifact, (List)filteredRemoteRepositories, request.getRequestContext()));
                    result.setLocalArtifactResult(local);
                    boolean found = filter != null && local.isAvailable() || this.isLocallyInstalled(local, versionResult);
                    if (found || local.getFile() != null) {
                        if (local.getRepository() != null) {
                            result.setRepository(local.getRepository());
                        } else {
                            result.setRepository(lrm.getRepository());
                        }

                        try {
                            artifact = artifact.setFile(this.getFile(session, artifact, local.getFile()));
                            result.setArtifact(artifact);
                            this.artifactResolved(session, trace, artifact, result.getRepository(), (List)null);
                        } catch (ArtifactTransferException var27) {
                            ArtifactTransferException e = var27;
                            result.addException(e);
                        }

                        if (filter == null && simpleLrmInterop && !local.isAvailable()) {
                            lrm.add(session, new LocalArtifactRegistration(artifact));
                        }
                    } else {

                        LOGGER.debug("Resolving artifact {} from {}", artifact, remoteRepositories);
                        AtomicBoolean resolved = new AtomicBoolean(false);
                        Iterator<DefaultArtifactResolver.ResolutionGroup> groupIt = groups.iterator();
                        Iterator var23 = ((List)filteredRemoteRepositories).iterator();

                        while(true) {
                            RemoteRepository repo;
                            while(true) {
                                do {
                                    if (!var23.hasNext()) {
                                        continue label169;
                                    }

                                    repo = (RemoteRepository)var23.next();
                                } while(!repo.getPolicy(artifact.isSnapshot()).isEnabled());

                                try {
                                    Utils.checkOffline(session, this.offlineController, repo);
                                    break;
                                } catch (RepositoryOfflineException var29) {
                                    RepositoryOfflineException e = var29;
                                    Exception exception = new ArtifactNotFoundException(artifact, repo, "Cannot access " + repo.getId() + " (" + repo.getUrl() + ") in offline mode and the artifact " + artifact + " has not been downloaded from it before.", e);
                                    result.addException(exception);
                                }
                            }

                            DefaultArtifactResolver.ResolutionGroup group = null;

                            while(groupIt.hasNext()) {
                                DefaultArtifactResolver.ResolutionGroup t = (DefaultArtifactResolver.ResolutionGroup)groupIt.next();
                                if (t.matches(repo)) {
                                    group = t;
                                    break;
                                }
                            }

                            if (group == null) {
                                group = new DefaultArtifactResolver.ResolutionGroup(repo);
                                groups.add(group);
                                groupIt = Collections.emptyIterator();
                            }

                            group.items.add(new DefaultArtifactResolver.ResolutionItem(trace, artifact, resolved, result, local, repo));
                        }
                    }
                }
            }

            var10 = groups.iterator();

            while(var10.hasNext()) {
                DefaultArtifactResolver.ResolutionGroup group = (DefaultArtifactResolver.ResolutionGroup)var10.next();
                this.performDownloads(session, group);
            }

            var10 = this.artifactResolverPostProcessors.values().iterator();

            while(var10.hasNext()) {
                ArtifactResolverPostProcessor artifactResolverPostProcessor = (ArtifactResolverPostProcessor)var10.next();
                artifactResolverPostProcessor.postProcess(session, results);
            }

            var10 = results.iterator();

            while(true) {
                ArtifactResult result;
                ArtifactRequest request;
                Artifact artifact;
                do {
                    if (!var10.hasNext()) {
                        if (failures) {
                            throw new ArtifactResolutionException(results);
                        }

                        return results;
                    }

                    result = (ArtifactResult)var10.next();
                    request = result.getRequest();
                    artifact = result.getArtifact();
                } while(artifact != null && artifact.getFile() != null);

                failures = true;
                if (result.getExceptions().isEmpty()) {
                    Exception exception = new ArtifactNotFoundException(request.getArtifact(), (RemoteRepository)null);
                    result.addException(exception);
                }

                RequestTrace trace = RequestTrace.newChild(request.getTrace(), request);
                this.artifactResolved(session, trace, request.getArtifact(), (ArtifactRepository)null, result.getExceptions());
            }
        }
    }

    private boolean isLocallyInstalled(LocalArtifactResult lar, VersionResult vr) {
        if (lar.isAvailable()) {
            return true;
        } else {
            if (lar.getFile() != null) {
                if (vr.getRepository() instanceof LocalRepository) {
                    return true;
                }

                if (vr.getRepository() == null && lar.getRequest().getRepositories().isEmpty()) {
                    return true;
                }
            }

            return false;
        }
    }

    private File getFile(RepositorySystemSession session, Artifact artifact, File file) throws ArtifactTransferException {
        if (artifact.isSnapshot() && !artifact.getVersion().equals(artifact.getBaseVersion()) && ConfigUtils.getBoolean(session, true, new String[]{"aether.artifactResolver.snapshotNormalization"})) {
            String name = file.getName().replace(artifact.getVersion(), artifact.getBaseVersion());
            File dst = new File(file.getParent(), name);
            boolean copy = dst.length() != file.length() || dst.lastModified() != file.lastModified();
            if (copy) {
                try {
                    this.fileProcessor.copy(file, dst);
                    dst.setLastModified(file.lastModified());
                } catch (IOException var8) {
                    IOException e = var8;
                    throw new ArtifactTransferException(artifact, (RemoteRepository)null, e);
                }
            }

            file = dst;
        }

        return file;
    }

    private void performDownloads(RepositorySystemSession session, DefaultArtifactResolver.ResolutionGroup group) {
        List<ArtifactDownload> downloads = this.gatherDownloads(session, group);
        if (!downloads.isEmpty()) {
            Iterator var4 = downloads.iterator();

            while(var4.hasNext()) {
                ArtifactDownload download = (ArtifactDownload)var4.next();
                this.artifactDownloading(session, download.getTrace(), download.getArtifact(), group.repository);
            }

            try {
                RepositoryConnector connector = this.repositoryConnectorProvider.newRepositoryConnector(session, group.repository);

                try {
                    connector.get(downloads, (Collection)null);
                } catch (Throwable var8) {
                    if (connector != null) {
                        try {
                            connector.close();
                        } catch (Throwable var7) {
                            var8.addSuppressed(var7);
                        }
                    }

                    throw var8;
                }

                if (connector != null) {
                    connector.close();
                }
            } catch (NoRepositoryConnectorException var9) {
                NoRepositoryConnectorException e = var9;
                Iterator var12 = downloads.iterator();

                while(var12.hasNext()) {
                    ArtifactDownload download = (ArtifactDownload)var12.next();
                    download.setException(new ArtifactTransferException(download.getArtifact(), group.repository, e));
                }
            }

            this.evaluateDownloads(session, group);
        }
    }

    private List<ArtifactDownload> gatherDownloads(RepositorySystemSession session, DefaultArtifactResolver.ResolutionGroup group) {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        List<ArtifactDownload> downloads = new ArrayList();
        Iterator var5 = group.items.iterator();

        while(true) {
            while(true) {
                DefaultArtifactResolver.ResolutionItem item;
                Artifact artifact;
                do {
                    if (!var5.hasNext()) {
                        return downloads;
                    }

                    item = (DefaultArtifactResolver.ResolutionItem)var5.next();
                    artifact = item.artifact;
                } while(item.resolved.get());

                ArtifactDownload download = new ArtifactDownload();
                download.setArtifact(artifact);
                download.setRequestContext(item.request.getRequestContext());
                download.setListener(SafeTransferListener.wrap(session));
                download.setTrace(item.trace);
                if (item.local.getFile() != null) {
                    download.setFile(item.local.getFile());
                    download.setExistenceCheck(true);
                } else {
                    String path = lrm.getPathForRemoteArtifact(artifact, group.repository, item.request.getRequestContext());
                    download.setFile(new File(lrm.getRepository().getBasedir(), path));
                }

                boolean snapshot = artifact.isSnapshot();
                RepositoryPolicy policy = this.remoteRepositoryManager.getPolicy(session, group.repository, !snapshot, snapshot);
                int errorPolicy = Utils.getPolicy(session, artifact, group.repository);
                if ((errorPolicy & 3) != 0) {
                    UpdateCheck<Artifact, ArtifactTransferException> check = new UpdateCheck();
                    check.setItem(artifact);
                    check.setFile(download.getFile());
                    check.setFileValid(false);
                    check.setRepository(group.repository);
                    check.setPolicy(policy.getUpdatePolicy());
                    item.updateCheck = check;
                    this.updateCheckManager.checkArtifact(session, check);
                    if (!check.isRequired()) {
                        item.result.addException(check.getException());
                        continue;
                    }
                }

                download.setChecksumPolicy(policy.getChecksumPolicy());
                download.setRepositories(item.repository.getMirroredRepositories());
                downloads.add(download);
                item.download = download;
            }
        }
    }

    private void evaluateDownloads(RepositorySystemSession session, DefaultArtifactResolver.ResolutionGroup group) {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        Iterator var4 = group.items.iterator();

        while(var4.hasNext()) {
            DefaultArtifactResolver.ResolutionItem item = (DefaultArtifactResolver.ResolutionItem)var4.next();
            ArtifactDownload download = item.download;
            if (download != null) {
                Artifact artifact = download.getArtifact();
                if (download.getException() == null) {
                    item.resolved.set(true);
                    item.result.setRepository(group.repository);

                    try {
                        artifact = artifact.setFile(this.getFile(session, artifact, download.getFile()));
                        item.result.setArtifact(artifact);
                        lrm.add(session, new LocalArtifactRegistration(artifact, group.repository, download.getSupportedContexts()));
                    } catch (ArtifactTransferException var9) {
                        ArtifactTransferException e = var9;
                        download.setException(e);
                        item.result.addException(e);
                    }
                } else {
                    item.result.addException(download.getException());
                }

                if (item.updateCheck != null) {
                    item.updateCheck.setException(download.getException());
                    this.updateCheckManager.touchArtifact(session, item.updateCheck);
                }

                this.artifactDownloaded(session, download.getTrace(), artifact, group.repository, download.getException());
                if (download.getException() == null) {
                    this.artifactResolved(session, download.getTrace(), artifact, group.repository, (List)null);
                }
            }
        }

    }

    private void artifactResolving(RepositorySystemSession session, RequestTrace trace, Artifact artifact) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, EventType.ARTIFACT_RESOLVING);
        event.setTrace(trace);
        event.setArtifact(artifact);
        this.repositoryEventDispatcher.dispatch(event.build());
    }

    private void artifactResolved(RepositorySystemSession session, RequestTrace trace, Artifact artifact, ArtifactRepository repository, List<Exception> exceptions) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, EventType.ARTIFACT_RESOLVED);
        event.setTrace(trace);
        event.setArtifact(artifact);
        event.setRepository(repository);
        event.setExceptions(exceptions);
        if (artifact != null) {
            event.setFile(artifact.getFile());
        }

        this.repositoryEventDispatcher.dispatch(event.build());
    }

    private void artifactDownloading(RepositorySystemSession session, RequestTrace trace, Artifact artifact, RemoteRepository repository) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, EventType.ARTIFACT_DOWNLOADING);
        event.setTrace(trace);
        event.setArtifact(artifact);
        event.setRepository(repository);
        this.repositoryEventDispatcher.dispatch(event.build());
    }

    private void artifactDownloaded(RepositorySystemSession session, RequestTrace trace, Artifact artifact, RemoteRepository repository, Exception exception) {
        RepositoryEvent.Builder event = new RepositoryEvent.Builder(session, EventType.ARTIFACT_DOWNLOADED);
        event.setTrace(trace);
        event.setArtifact(artifact);
        event.setRepository(repository);
        event.setException(exception);
        if (artifact != null) {
            event.setFile(artifact.getFile());
        }

        this.repositoryEventDispatcher.dispatch(event.build());
    }

    static class ResolutionGroup {
        final RemoteRepository repository;
        final List<DefaultArtifactResolver.ResolutionItem> items = new ArrayList();

        ResolutionGroup(RemoteRepository repository) {
            this.repository = repository;
        }

        boolean matches(RemoteRepository repo) {
            return this.repository.getUrl().equals(repo.getUrl()) && this.repository.getContentType().equals(repo.getContentType()) && this.repository.isRepositoryManager() == repo.isRepositoryManager();
        }
    }

    static class ResolutionItem {
        final RequestTrace trace;
        final ArtifactRequest request;
        final ArtifactResult result;
        final LocalArtifactResult local;
        final RemoteRepository repository;
        final Artifact artifact;
        final AtomicBoolean resolved;
        ArtifactDownload download;
        UpdateCheck<Artifact, ArtifactTransferException> updateCheck;

        ResolutionItem(RequestTrace trace, Artifact artifact, AtomicBoolean resolved, ArtifactResult result, LocalArtifactResult local, RemoteRepository repository) {
            this.trace = trace;
            this.artifact = artifact;
            this.resolved = resolved;
            this.result = result;
            this.request = result.getRequest();
            this.local = local;
            this.repository = repository;
        }
    }
}
