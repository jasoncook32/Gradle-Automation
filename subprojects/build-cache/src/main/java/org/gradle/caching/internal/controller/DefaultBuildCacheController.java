/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.controller;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Closer;
import org.gradle.api.GradleException;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.controller.operations.PackOperationDetails;
import org.gradle.caching.internal.controller.operations.PackOperationResult;
import org.gradle.caching.internal.controller.operations.UnpackOperationDetails;
import org.gradle.caching.internal.controller.operations.UnpackOperationResult;
import org.gradle.caching.internal.controller.service.BuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.BuildCacheServiceRole;
import org.gradle.caching.internal.controller.service.BuildCacheServicesConfiguration;
import org.gradle.caching.internal.controller.service.DefaultLocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.LocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.NullBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.NullLocalBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.OpFiringBuildCacheServiceHandle;
import org.gradle.caching.internal.controller.service.StoreTarget;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.caching.internal.origin.OriginMetadataFactory;
import org.gradle.caching.internal.packaging.BuildCacheEntryPacker;
import org.gradle.caching.local.internal.BuildCacheTempFileStore;
import org.gradle.caching.local.internal.DefaultBuildCacheTempFileStore;
import org.gradle.caching.local.internal.LocalBuildCacheService;
import org.gradle.internal.file.FileMetadata;
import org.gradle.internal.file.FileType;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.MissingFileSnapshot;
import org.gradle.internal.vfs.FileSystemAccess;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultBuildCacheController implements BuildCacheController {

    @VisibleForTesting
    final BuildCacheServiceHandle remote;

    @VisibleForTesting
    final LocalBuildCacheServiceHandle local;

    private final BuildCacheTempFileStore tmp;
    private final boolean emitDebugLogging;
    private final PackOperationExecutor packExecutor;

    private boolean closed;

    public DefaultBuildCacheController(
        BuildCacheServicesConfiguration config,
        BuildOperationExecutor buildOperationExecutor,
        TemporaryFileProvider temporaryFileProvider,
        boolean logStackTraces,
        boolean emitDebugLogging,
        boolean disableRemoteOnError,
        FileSystemAccess fileSystemAccess,
        BuildCacheEntryPacker packer,
        OriginMetadataFactory originMetadataFactory,
        StringInterner stringInterner
    ) {
        this.emitDebugLogging = emitDebugLogging;
        this.local = toLocalHandle(config.getLocal(), config.isLocalPush());
        this.remote = toRemoteHandle(config.getRemote(), config.isRemotePush(), buildOperationExecutor, logStackTraces, disableRemoteOnError);
        this.tmp = toTempFileStore(config.getLocal(), temporaryFileProvider);
        this.packExecutor = new PackOperationExecutor(
            buildOperationExecutor,
            fileSystemAccess,
            packer,
            originMetadataFactory,
            stringInterner
        );
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isEmitDebugLogging() {
        return emitDebugLogging;
    }

    @Override
    public Optional<LoadResult> load(BuildCacheKey key, CacheableEntity entity) {
        Optional<LoadResult> result = Optional.empty();
        if (local.canLoad()) {
            result = loadLocal(key, entity);
        }
        if (!result.isPresent() && remote.canLoad()) {
            result = loadRemoteAndStoreResultLocally(key, entity);
        }
        return result;
    }

    private Optional<LoadResult> loadLocal(BuildCacheKey key, CacheableEntity entity) {
        try {
            return local.load(key, file -> packExecutor.unpack(key, entity, file));
        } catch (Exception e) {
            throw new GradleException("Build cache entry " + key.getHashCode() + " from local build cache is invalid", e);
        }
    }

    private Optional<LoadResult> loadRemoteAndStoreResultLocally(BuildCacheKey key, CacheableEntity entity) {
        AtomicReference<Optional<LoadResult>> result = new AtomicReference<>(Optional.empty());
        tmp.withTempFile(key, file -> {
            if (remote.load(key, file)) {
                try {
                    result.set(Optional.of(packExecutor.unpack(key, entity, file)));
                } catch (Exception e) {
                    throw new GradleException("Build cache entry " + key.getHashCode() + " from remote build cache is invalid", e);
                }
                if (local.canStore()) {
                    local.store(key, file);
                }
            }
        });
        return result.get();
    }

    @Override
    public void store(BuildCacheKey key, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime) {
        if (!local.canStore() && !remote.canStore()) {
            return;
        }

        tmp.withTempFile(key, file -> {
            packExecutor.pack(file, key, entity, snapshots, executionTime);
            if (remote.canStore()) {
                remote.store(key, new StoreTarget(file));
            }

            if (local.canStore()) {
                local.store(key, file);
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            Closer closer = Closer.create();
            closer.register(local);
            closer.register(remote);
            closer.close();
        }
    }

    @VisibleForTesting
    static class PackOperationExecutor {
        private final BuildOperationExecutor buildOperationExecutor;
        private final FileSystemAccess fileSystemAccess;
        private final BuildCacheEntryPacker packer;
        private final OriginMetadataFactory originMetadataFactory;
        private final StringInterner stringInterner;

        PackOperationExecutor(BuildOperationExecutor buildOperationExecutor, FileSystemAccess fileSystemAccess, BuildCacheEntryPacker packer, OriginMetadataFactory originMetadataFactory, StringInterner stringInterner) {
            this.buildOperationExecutor = buildOperationExecutor;
            this.fileSystemAccess = fileSystemAccess;
            this.packer = packer;
            this.originMetadataFactory = originMetadataFactory;
            this.stringInterner = stringInterner;
        }

        @VisibleForTesting
        LoadResult unpack(BuildCacheKey key, CacheableEntity entity, File file) {
            return buildOperationExecutor.call(new CallableBuildOperation<LoadResult>() {
                @Override
                public LoadResult call(BuildOperationContext context) throws IOException {
                    try (InputStream input = new FileInputStream(file)) {
                        LoadResult metadata = doUnpack(entity, input);
                        context.setResult(new UnpackOperationResult(metadata.getArtifactEntryCount()));
                        return metadata;
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Unpack build cache entry " + key.getHashCode())
                        .details(new UnpackOperationDetails(key, file.length()))
                        .progressDisplayName("Unpacking build cache entry");
                }
            });
        }

        private LoadResult doUnpack(CacheableEntity entity, InputStream input) throws IOException {
            ImmutableList.Builder<String> roots = ImmutableList.builder();
            entity.visitOutputTrees((name, type, root) -> roots.add(root.getAbsolutePath()));
            // TODO: Actually unpack the roots inside of the action
            fileSystemAccess.write(roots.build(), () -> {});
            BuildCacheEntryPacker.UnpackResult unpackResult = packer.unpack(entity, input, originMetadataFactory.createReader(entity));
            // TODO: Update the snapshots from the action
            ImmutableSortedMap<String, FileSystemSnapshot> resultingSnapshots = snapshotUnpackedData(entity, unpackResult.getSnapshots());
            return new LoadResult() {
                @Override
                public long getArtifactEntryCount() {
                    return unpackResult.getEntries();
                }
                @Override
                public OriginMetadata getOriginMetadata() {
                    return unpackResult.getOriginMetadata();
                }
                @Override
                public ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots() {
                    return resultingSnapshots;
                }
            };
        }

        private ImmutableSortedMap<String, FileSystemSnapshot> snapshotUnpackedData(CacheableEntity entity, Map<String, ? extends FileSystemLocationSnapshot> treeSnapshots) {
            ImmutableSortedMap.Builder<String, FileSystemSnapshot> builder = ImmutableSortedMap.naturalOrder();
            entity.visitOutputTrees((treeName, type, root) -> {
                FileSystemLocationSnapshot treeSnapshot = treeSnapshots.get(treeName);
                FileSystemLocationSnapshot resultingSnapshot;
                if (treeSnapshot == null) {
                    String internedAbsolutePath = stringInterner.intern(root.getAbsolutePath());
                    resultingSnapshot = new MissingFileSnapshot(internedAbsolutePath, FileMetadata.AccessType.DIRECT);
                } else {
                    if (type == TreeType.FILE && treeSnapshot.getType() != FileType.RegularFile) {
                        throw new IllegalStateException(String.format("Only a regular file should be produced by unpacking tree '%s', but saw a %s", treeName, treeSnapshot.getType()));
                    }
                    resultingSnapshot = treeSnapshot;
                }
                fileSystemAccess.record(resultingSnapshot);
                builder.put(treeName, resultingSnapshot);
            });
            return builder.build();
        }

        @VisibleForTesting
        void pack(File file, BuildCacheKey key, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws IOException {
                    try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                        BuildCacheEntryPacker.PackResult packResult = packer.pack(entity, snapshots, fileOutputStream, originMetadataFactory.createWriter(entity, executionTime));
                        long entryCount = packResult.getEntries();
                        context.setResult(new PackOperationResult(entryCount, file.length()));
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Pack build cache entry " + key)
                        .details(new PackOperationDetails(key))
                        .progressDisplayName("Packing build cache entry");
                }
            });
        }
    }

    private static BuildCacheServiceHandle toRemoteHandle(@Nullable BuildCacheService service, boolean push, BuildOperationExecutor buildOperationExecutor, boolean logStackTraces, boolean disableOnError) {
        return service == null
            ? NullBuildCacheServiceHandle.INSTANCE
            : new OpFiringBuildCacheServiceHandle(service, push, BuildCacheServiceRole.REMOTE, buildOperationExecutor, logStackTraces, disableOnError);
    }

    private static LocalBuildCacheServiceHandle toLocalHandle(@Nullable LocalBuildCacheService local, boolean localPush) {
        return local == null
            ? NullLocalBuildCacheServiceHandle.INSTANCE
            : new DefaultLocalBuildCacheServiceHandle(local, localPush);
    }

    private static BuildCacheTempFileStore toTempFileStore(@Nullable LocalBuildCacheService local, TemporaryFileProvider temporaryFileProvider) {
        return local != null
            ? local
            : new DefaultBuildCacheTempFileStore(temporaryFileProvider);
    }
}
