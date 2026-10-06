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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.io.file;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.io.function.IORunnable;

/**
 * An explicit plan to publish one small regular file to a target path.
 * <p>
 * A plan binds a source identity (file key, size and last-modified time, snapshotted when the plan is built) to a
 * target policy (link policy, permission policy and size limit). {@link #execute()} then:
 * </p>
 * <ol>
 * <li>Rejects the source if its identity changed since the plan was built.</li>
 * <li>Copies the source to a uniquely named temporary file in the target directory.</li>
 * <li>Verifies the temporary file content against the source (byte for byte, not just modification times).</li>
 * <li>Rejects the publication if the source changed while copying.</li>
 * <li>Replaces the target with a single atomic move (falling back to a plain rename), so an existing target is never
 * half-written.</li>
 * </ol>
 * <p>
 * On any failure only the temporary file created by this execution is deleted; a pre-existing target is left untouched.
 * </p>
 * <p>
 * Scope: plans are limited to small regular files (at most {@link #DEFAULT_MAX_BYTES} bytes unless configured
 * otherwise). Directories and other non-regular files are rejected, so a plan never moves a user directory. Symbolic
 * links are followed or forbidden exactly as declared by the {@link LinkPolicy}; with {@link LinkPolicy#FORBID} a
 * source that is a link (for example a link pointing outside the source directory) is rejected.
 * </p>
 * <p>
 * Permission preservation scope is explicit and narrow: {@link PermissionPolicy#NONE} (the default) never copies
 * attributes, so the published file gets the default permissions of the target directory.
 * {@link PermissionPolicy#PRESERVE_POSIX} copies only the POSIX permission bits from source to target; ACLs, ownership,
 * DOS attributes and timestamps are never preserved.
 * </p>
 *
 * @since 2.23.0
 */
public final class CopyPlan {

    /**
     * How a plan treats a source that is a symbolic link.
     */
    public enum LinkPolicy {

        /** Follow links: the attributes and content of the link target are used. */
        FOLLOW,

        /** Forbid links: a source that is a symbolic link is rejected. */
        FORBID
    }

    /**
     * Which permissions, if any, are preserved from source to target.
     */
    public enum PermissionPolicy {

        /** Preserve nothing; the published file gets the default permissions of the target directory. */
        NONE,

        /** Preserve only the POSIX permission bits; ACLs, ownership, DOS attributes and timestamps are never preserved. */
        PRESERVE_POSIX
    }

    /**
     * Thrown when the source file identity differs from the identity bound to the plan, either because it changed
     * after the plan was built or because it changed while the copy was in flight.
     */
    public static class SourceChangedException extends IOException {

        private static final long serialVersionUID = 1L;

        SourceChangedException(final Path source) {
            super("Source file changed while the copy plan was built or executed: " + source);
        }
    }

    /**
     * Builds {@link CopyPlan} instances.
     */
    public static final class Builder {

        private final Path source;
        private final Path target;
        private LinkPolicy linkPolicy = LinkPolicy.FORBID;
        private PermissionPolicy permissionPolicy = PermissionPolicy.NONE;
        private long maxBytes = DEFAULT_MAX_BYTES;
        private IORunnable afterCopyProbe = IORunnable.noop();
        private IORunnable beforeMoveProbe = IORunnable.noop();

        private Builder(final Path source, final Path target) {
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
        }

        /**
         * Builds the plan, snapshotting the source identity and validating it against the declared policies.
         *
         * @return a new plan.
         * @throws IOException if the source cannot be read or violates a declared policy.
         */
        public CopyPlan build() throws IOException {
            return new CopyPlan(this);
        }

        /**
         * Sets how to treat a source that is a symbolic link; defaults to {@link LinkPolicy#FORBID}.
         *
         * @param linkPolicy the link policy.
         * @return this builder.
         */
        public Builder linkPolicy(final LinkPolicy linkPolicy) {
            this.linkPolicy = Objects.requireNonNull(linkPolicy, "linkPolicy");
            return this;
        }

        /**
         * Sets the maximum source size in bytes; defaults to {@link CopyPlan#DEFAULT_MAX_BYTES}.
         *
         * @param maxBytes the maximum source size in bytes, must be non-negative.
         * @return this builder.
         */
        public Builder maxBytes(final long maxBytes) {
            if (maxBytes < 0) {
                throw new IllegalArgumentException("maxBytes must be >= 0: " + maxBytes);
            }
            this.maxBytes = maxBytes;
            return this;
        }

        /**
         * Test hook executed after the temporary copy, before verification.
         *
         * @param afterCopyProbe the probe.
         * @return this builder.
         */
        Builder onAfterCopy(final IORunnable afterCopyProbe) {
            this.afterCopyProbe = Objects.requireNonNull(afterCopyProbe, "afterCopyProbe");
            return this;
        }

        /**
         * Test hook executed after verification, before the publishing move.
         *
         * @param beforeMoveProbe the probe.
         * @return this builder.
         */
        Builder onBeforeMove(final IORunnable beforeMoveProbe) {
            this.beforeMoveProbe = Objects.requireNonNull(beforeMoveProbe, "beforeMoveProbe");
            return this;
        }

        /**
         * Sets which permissions to preserve; defaults to {@link PermissionPolicy#NONE}.
         *
         * @param permissionPolicy the permission policy.
         * @return this builder.
         */
        public Builder permissionPolicy(final PermissionPolicy permissionPolicy) {
            this.permissionPolicy = Objects.requireNonNull(permissionPolicy, "permissionPolicy");
            return this;
        }
    }

    /**
     * The default maximum source size: 1 MiB. Plans are meant for a few small regular files, not bulk data.
     */
    public static final long DEFAULT_MAX_BYTES = 1024 * 1024;

    private static final LinkOption[] NOFOLLOW_LINKS = { LinkOption.NOFOLLOW_LINKS };

    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[0];

    /**
     * Creates a builder binding the given source to the given target.
     *
     * @param source the source file, must be a small regular file when the plan is built.
     * @param target the target path replaced on {@link CopyPlan#execute()}.
     * @return a new builder.
     */
    public static Builder builder(final Path source, final Path target) {
        return new Builder(source, target);
    }

    /**
     * Creates a plan with default policies binding the given source to the given target.
     *
     * @param source the source file, must be a small regular file.
     * @param target the target path replaced on {@link CopyPlan#execute()}.
     * @return a new plan.
     * @throws IOException if the source cannot be read or violates the default policies.
     */
    public static CopyPlan of(final Path source, final Path target) throws IOException {
        return builder(source, target).build();
    }

    /**
     * Validates source attributes against the declared policies. Package-private for testing.
     *
     * @param source the source path, used in exception messages.
     * @param attributes the attributes read according to the link policy.
     * @param linkPolicy the declared link policy.
     * @param maxBytes the maximum accepted size in bytes.
     * @throws IOException if the source is a forbidden link, not a regular file, or too large.
     */
    static void checkSourceAttributes(final Path source, final BasicFileAttributes attributes, final LinkPolicy linkPolicy, final long maxBytes)
        throws IOException {
        if (attributes.isSymbolicLink() && linkPolicy == LinkPolicy.FORBID) {
            throw new IOException("Link policy FORBID rejects symbolic link source: " + source);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Source is not a regular file (directories and special files are never copied): " + source);
        }
        if (attributes.size() > maxBytes) {
            throw new IOException("Source size " + attributes.size() + " exceeds the plan limit of " + maxBytes + " bytes: " + source);
        }
    }

    /**
     * Reads source attributes according to the link policy. Package-private for testing.
     *
     * @param source the source path.
     * @param linkPolicy the declared link policy.
     * @return the source attributes.
     * @throws IOException if the attributes cannot be read.
     */
    static BasicFileAttributes readSourceAttributes(final Path source, final LinkPolicy linkPolicy) throws IOException {
        final LinkOption[] options = linkPolicy == LinkPolicy.FORBID ? NOFOLLOW_LINKS : FOLLOW_LINKS;
        return Files.readAttributes(source, BasicFileAttributes.class, options);
    }

    private static boolean sameIdentity(final BasicFileAttributes a, final BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size() && Objects.equals(a.lastModifiedTime(), b.lastModifiedTime());
    }

    private final Path source;
    private final Path target;
    private final LinkPolicy linkPolicy;
    private final PermissionPolicy permissionPolicy;
    private final long maxBytes;
    private final BasicFileAttributes sourceIdentity;
    private final IORunnable afterCopyProbe;
    private final IORunnable beforeMoveProbe;

    private CopyPlan(final Builder builder) throws IOException {
        this.source = builder.source;
        this.target = builder.target;
        this.linkPolicy = builder.linkPolicy;
        this.permissionPolicy = builder.permissionPolicy;
        this.maxBytes = builder.maxBytes;
        this.afterCopyProbe = builder.afterCopyProbe;
        this.beforeMoveProbe = builder.beforeMoveProbe;
        this.sourceIdentity = readSourceAttributes(source, linkPolicy);
        checkSourceAttributes(source, sourceIdentity, linkPolicy, maxBytes);
    }

    private void applyPermissionPolicy(final Path tempFile) throws IOException {
        if (permissionPolicy == PermissionPolicy.PRESERVE_POSIX) {
            if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                throw new IOException("Permission policy PRESERVE_POSIX requested but POSIX attributes are not supported here");
            }
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(tempFile, permissions);
        }
    }

    /**
     * Executes the plan: copies the source to a temporary file, verifies its content, and replaces the target.
     *
     * @return the target path.
     * @throws SourceChangedException if the source changed after this plan was built or while copying.
     * @throws IOException if the copy, verification, or publication fails; only this execution temporary file is
     *         cleaned up and a pre-existing target is never half-written.
     */
    public Path execute() throws IOException {
        requireSourceUnchanged();
        final Path targetDirectory = target.toAbsolutePath().getParent();
        Files.createDirectories(targetDirectory);
        final String fileName = target.getFileName().toString();
        final String prefix = fileName.length() >= 3 ? fileName : fileName + "__";
        final Path tempFile = Files.createTempFile(targetDirectory, prefix, ".tmp");
        try {
            Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);
            applyPermissionPolicy(tempFile);
            afterCopyProbe.run();
            // Refuse to publish if the source changed while copying.
            requireSourceUnchanged();
            // Verify actual content, not just modification times.
            if (!PathUtils.fileContentEquals(source, tempFile)) {
                throw new IOException("Copied content does not match the source: " + source);
            }
            beforeMoveProbe.run();
            moveAtomically(tempFile, target);
        } catch (final IOException | RuntimeException e) {
            // Clean up only this execution temporary file; a pre-existing target stays untouched.
            Files.deleteIfExists(tempFile);
            throw e;
        }
        return target;
    }

    /**
     * Gets the declared link policy.
     *
     * @return the link policy.
     */
    public LinkPolicy getLinkPolicy() {
        return linkPolicy;
    }

    /**
     * Gets the maximum accepted source size in bytes.
     *
     * @return the maximum source size in bytes.
     */
    public long getMaxBytes() {
        return maxBytes;
    }

    /**
     * Gets the declared permission policy.
     *
     * @return the permission policy.
     */
    public PermissionPolicy getPermissionPolicy() {
        return permissionPolicy;
    }

    /**
     * Gets the source path bound to this plan.
     *
     * @return the source path.
     */
    public Path getSource() {
        return source;
    }

    /**
     * Gets the target path bound to this plan.
     *
     * @return the target path.
     */
    public Path getTarget() {
        return target;
    }

    private void moveAtomically(final Path tempFile, final Path targetPath) throws IOException {
        try {
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            // A rename still never leaves a half-written target.
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireSourceUnchanged() throws IOException {
        final BasicFileAttributes current = readSourceAttributes(source, linkPolicy);
        checkSourceAttributes(source, current, linkPolicy, maxBytes);
        if (!sameIdentity(sourceIdentity, current)) {
            throw new SourceChangedException(source);
        }
    }
}
