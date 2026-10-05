/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.io.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IOBiFunction;
import org.apache.commons.io.function.IOConsumer;

/**
 * An explicit plan for publishing one small regular file to a target path: the plan binds the <em>source
 * identity</em> (file key, size, and last-modified time, never the modification time alone) to a <em>target
 * policy</em> (link handling, overwrite, and permission preservation scope), copies the source to a temporary file
 * in the target directory, verifies the temporary file content, and only then replaces the target.
 * <p>
 * Behavior contract:
 * </p>
 * <ul>
 * <li><strong>Source identity.</strong> The source file key, size, and last-modified time are captured before the
 * copy and re-read after the temporary file is complete. If any of them changed, the copy is rejected with a
 * {@link SourceChangedException} and the target is left untouched.</li>
 * <li><strong>Content verification.</strong> A CRC-32 is computed while streaming the source into the temporary
 * file; the temporary file is then re-read and its CRC-32 and size must match before the target is replaced.</li>
 * <li><strong>No half-written targets.</strong> The target is only ever replaced by moving a fully written and
 * verified temporary file over it (atomically when the file store supports
 * {@link StandardCopyOption#ATOMIC_MOVE ATOMIC_MOVE}). An existing target is never truncated or rewritten in
 * place.</li>
 * <li><strong>Failure cleanup.</strong> On any failure only the temporary file created by this execution is
 * deleted; nothing else is touched.</li>
 * <li><strong>Links.</strong> The {@link LinkPolicy} declares whether a symbolic link source is followed or
 * rejected; junctions, directories, and other non-regular files are always rejected, regardless of policy.</li>
 * <li><strong>Scope.</strong> Only small regular files are supported: sources larger than the configured maximum
 * (default {@value #DEFAULT_MAX_BYTES} bytes) are rejected, and directories are never copied or moved.</li>
 * </ul>
 * <p>
 * Permission preservation scope is deliberately narrow and explicit: only the source last-modified time and/or its
 * POSIX permissions are carried over to the published file, and only when {@link PermissionScope} requests them.
 * POSIX permissions are applied on a best-effort basis, only when both the source and target file stores support
 * the POSIX attribute view. File owner, ACLs, DOS attributes, and extended attributes are never copied. With
 * {@link PermissionScope#NONE} (the default) the published file keeps the default attributes of a newly created
 * file.
 * </p>
 * <p>
 * Example publishing four small files:
 * </p>
 * <pre>{@code
 * List<CopyPlan> plans = new ArrayList<>();
 * for (String name : Arrays.asList("a.txt", "b.txt", "c.txt", "d.txt")) {
 *     plans.add(CopyPlan.builder(sourceDir.resolve(name), targetDir.resolve(name))
 *         .setLinkPolicy(CopyPlan.LinkPolicy.REJECT_LINKS)
 *         .setPermissionScope(CopyPlan.PermissionScope.LAST_MODIFIED_TIME)
 *         .build());
 * }
 * CopyPlan.executeAll(plans);
 * }</pre>
 * <p>
 * A plan instance is intended to be executed once; build a new plan to copy again.
 * </p>
 *
 * @since 2.23.0
 */
public final class CopyPlan {

    /**
     * Declares how a symbolic link source is treated.
     */
    public enum LinkPolicy {

        /** Follow symbolic links and copy the linked-to regular file. */
        FOLLOW_LINKS,

        /** Reject symbolic link sources (the default). */
        REJECT_LINKS
    }

    /**
     * Declares exactly which source attributes are preserved on the published file.
     */
    public enum PermissionScope {

        /** Preserve nothing; the published file keeps the default attributes of a newly created file. */
        NONE,

        /** Preserve only the source last-modified time. */
        LAST_MODIFIED_TIME,

        /**
         * Preserve only the source POSIX permissions, best-effort, when both file stores support the POSIX
         * attribute view.
         */
        POSIX_PERMISSIONS,

        /** Preserve both the last-modified time and the POSIX permissions. */
        ALL
    }

    /**
     * Builds {@link CopyPlan} instances.
     */
    public static final class Builder {

        private final Path source;
        private final Path target;
        private LinkPolicy linkPolicy = LinkPolicy.REJECT_LINKS;
        private PermissionScope permissionScope = PermissionScope.NONE;
        private boolean replaceExisting = true;
        private long maxBytes = DEFAULT_MAX_BYTES;

        private Builder(final Path source, final Path target) {
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
        }

        /**
         * Builds a new plan.
         *
         * @return a new plan.
         */
        public CopyPlan build() {
            return new CopyPlan(this);
        }

        /**
         * Sets the symbolic link policy, default {@link LinkPolicy#REJECT_LINKS}.
         *
         * @param linkPolicy the link policy.
         * @return this builder.
         */
        public Builder setLinkPolicy(final LinkPolicy linkPolicy) {
            this.linkPolicy = Objects.requireNonNull(linkPolicy, "linkPolicy");
            return this;
        }

        /**
         * Sets the maximum source size in bytes, default {@value #DEFAULT_MAX_BYTES}. Larger sources are rejected.
         *
         * @param maxBytes the maximum source size in bytes.
         * @return this builder.
         */
        public Builder setMaxBytes(final long maxBytes) {
            if (maxBytes < 0) {
                throw new IllegalArgumentException("maxBytes must be >= 0: " + maxBytes);
            }
            this.maxBytes = maxBytes;
            return this;
        }

        /**
         * Sets the permission preservation scope, default {@link PermissionScope#NONE}.
         *
         * @param permissionScope the permission preservation scope.
         * @return this builder.
         */
        public Builder setPermissionScope(final PermissionScope permissionScope) {
            this.permissionScope = Objects.requireNonNull(permissionScope, "permissionScope");
            return this;
        }

        /**
         * Sets whether an existing target is replaced, default {@code true}. When {@code false}, an existing
         * target fails the plan with {@link FileAlreadyExistsException}.
         *
         * @param replaceExisting whether to replace an existing target.
         * @return this builder.
         */
        public Builder setReplaceExisting(final boolean replaceExisting) {
            this.replaceExisting = replaceExisting;
            return this;
        }
    }

    /**
     * Thrown when the source file identity (file key, size, or last-modified time) changed while the plan was
     * executing; the target is guaranteed to be untouched.
     */
    public static class SourceChangedException extends IOException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructs a new exception for the given source path.
         *
         * @param source the source path that changed.
         */
        public SourceChangedException(final Path source) {
            super("Source file changed during copy, refusing to publish: " + source);
        }
    }

    /**
     * The default maximum source size: 64 MiB, keeping plans scoped to small regular files.
     */
    public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private static final int BUFFER_SIZE = 8192;

    /**
     * Returns a new builder for a plan copying {@code source} to {@code target}.
     *
     * @param source the source regular file.
     * @param target the target path; its parent directory must already exist.
     * @return a new builder.
     */
    public static Builder builder(final Path source, final Path target) {
        return new Builder(source, target);
    }

    /**
     * Copies {@code source} to {@code target} using a default plan: links rejected, existing targets replaced, no
     * attribute preservation.
     *
     * @param source the source regular file.
     * @param target the target path; its parent directory must already exist.
     * @return the target path.
     * @throws IOException if the copy fails.
     */
    public static Path copy(final Path source, final Path target) throws IOException {
        return builder(source, target).build().execute();
    }

    /**
     * Executes each plan in encounter order and returns the published target paths. Each plan is individually
     * atomic; if one plan fails, previously published targets stay published and the temporary file of the failing
     * plan is removed.
     *
     * @param plans the plans to execute.
     * @return the published target paths, in encounter order.
     * @throws IOException if a plan fails.
     */
    public static List<Path> executeAll(final Collection<CopyPlan> plans) throws IOException {
        final List<Path> targets = new ArrayList<>();
        for (final CopyPlan plan : plans) {
            targets.add(plan.execute());
        }
        return targets;
    }

    private static boolean isPosixSupported(final Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private final Path source;
    private final Path target;
    private final LinkPolicy linkPolicy;
    private final PermissionScope permissionScope;
    private final boolean replaceExisting;
    private final long maxBytes;

    /** Test hook invoked after the temporary file is written and verified, before the source identity re-check. */
    private IOConsumer<Path> tempFileReadyCallback = IOConsumer.noop();

    /** Test hook replacing the move step. */
    private IOBiFunction<Path, Path, Path> mover = this::move;

    private CopyPlan(final Builder builder) {
        this.source = builder.source;
        this.target = builder.target;
        this.linkPolicy = builder.linkPolicy;
        this.permissionScope = builder.permissionScope;
        this.replaceExisting = builder.replaceExisting;
        this.maxBytes = builder.maxBytes;
    }

    private void applyPermissions(final Path tempFile, final BasicFileAttributes sourceAttributes) throws IOException {
        if (permissionScope == PermissionScope.LAST_MODIFIED_TIME || permissionScope == PermissionScope.ALL) {
            Files.setLastModifiedTime(tempFile, sourceAttributes.lastModifiedTime());
        }
        if (permissionScope == PermissionScope.POSIX_PERMISSIONS || permissionScope == PermissionScope.ALL) {
            if (isPosixSupported(source) && isPosixSupported(tempFile)) {
                final Set<PosixFilePermission> permissions = Files
                    .readAttributes(source, PosixFileAttributes.class, linkOptions()).permissions();
                Files.setPosixFilePermissions(tempFile, permissions);
            }
        }
    }

    private void checkSourceIdentity(final BasicFileAttributes before) throws IOException {
        final BasicFileAttributes after = readSourceAttributes();
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new SourceChangedException(source);
        }
    }

    private void checkTarget() throws IOException {
        if (Files.isSymbolicLink(target) && linkPolicy == LinkPolicy.REJECT_LINKS) {
            throw new FileSystemException("Target is a symbolic link and the link policy rejects links: " + target);
        }
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
    }

    /**
     * Executes this plan and returns the published target path.
     *
     * @return the target path.
     * @throws SourceChangedException if the source changed while copying; the target is untouched.
     * @throws IOException if the plan fails for any other reason; the target is untouched and the temporary file
     *         created by this execution is removed.
     */
    public Path execute() throws IOException {
        final BasicFileAttributes before = readSourceAttributes();
        if (!before.isRegularFile()) {
            throw new FileSystemException("Source is not a regular file (directories and special files are out of scope): " + source);
        }
        if (before.size() > maxBytes) {
            throw new FileSystemException(
                "Source size " + before.size() + " exceeds the plan maximum of " + maxBytes + " bytes: " + source);
        }
        checkTarget();
        final Path parent = target.toAbsolutePath().getParent();
        final Path fileName = target.getFileName();
        // createTempFile requires a prefix of at least three characters.
        final String prefix = fileName == null ? "copyplan" : "cp" + fileName;
        final Path tempFile = Files.createTempFile(parent, prefix, ".tmp");
        boolean published = false;
        try {
            final long crc = copyToTempFile(tempFile);
            verifyTempFile(tempFile, crc);
            tempFileReadyCallback.accept(tempFile);
            checkSourceIdentity(before);
            applyPermissions(tempFile, before);
            mover.apply(tempFile, target);
            published = true;
            return target;
        } finally {
            if (!published) {
                // Only ever remove the temporary file owned by this execution; the target is never touched.
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private long copyToTempFile(final Path tempFile) throws IOException {
        final CRC32 crc = new CRC32();
        final byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = input.read(buffer)) != IOUtils.EOF) {
                total += read;
                if (total > maxBytes) {
                    throw new FileSystemException("Source grew past the plan maximum of " + maxBytes + " bytes: " + source);
                }
                crc.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    /**
     * Gets the link policy.
     *
     * @return the link policy.
     */
    public LinkPolicy getLinkPolicy() {
        return linkPolicy;
    }

    /**
     * Gets the maximum source size in bytes.
     *
     * @return the maximum source size in bytes.
     */
    public long getMaxBytes() {
        return maxBytes;
    }

    /**
     * Gets the permission preservation scope.
     *
     * @return the permission preservation scope.
     */
    public PermissionScope getPermissionScope() {
        return permissionScope;
    }

    /**
     * Gets the source path.
     *
     * @return the source path.
     */
    public Path getSource() {
        return source;
    }

    /**
     * Gets the target path.
     *
     * @return the target path.
     */
    public Path getTarget() {
        return target;
    }

    /**
     * Tests whether an existing target is replaced.
     *
     * @return whether an existing target is replaced.
     */
    public boolean isReplaceExisting() {
        return replaceExisting;
    }

    private LinkOption[] linkOptions() {
        return linkPolicy == LinkPolicy.FOLLOW_LINKS ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
    }

    private Path move(final Path tempFile, final Path targetPath) throws IOException {
        try {
            if (replaceExisting) {
                return Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            if (replaceExisting) {
                return Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.move(tempFile, targetPath);
        }
    }

    private BasicFileAttributes readSourceAttributes() throws IOException {
        if (linkPolicy == LinkPolicy.REJECT_LINKS) {
            final BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new FileSystemException("Source is a symbolic link and the link policy rejects links: " + source);
            }
            return attributes;
        }
        return Files.readAttributes(source, BasicFileAttributes.class);
    }

    private void verifyTempFile(final Path tempFile, final long expectedCrc) throws IOException {
        final CRC32 crc = new CRC32();
        final byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(tempFile)) {
            int read;
            while ((read = input.read(buffer)) != IOUtils.EOF) {
                crc.update(buffer, 0, read);
            }
        }
        if (crc.getValue() != expectedCrc) {
            throw new FileSystemException("Temporary file content verification failed: " + tempFile);
        }
    }

    CopyPlan withMover(final IOBiFunction<Path, Path, Path> mover) {
        this.mover = mover;
        return this;
    }

    CopyPlan withTempFileReadyCallback(final IOConsumer<Path> tempFileReadyCallback) {
        this.tempFileReadyCallback = tempFileReadyCallback;
        return this;
    }
}
