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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.io.file.CopyPlan.LinkPolicy;
import org.apache.commons.io.file.CopyPlan.PermissionPolicy;
import org.apache.commons.io.file.CopyPlan.SourceChangedException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CopyPlan}.
 * <p>
 * Run only these tests on this D: drive worktree with:
 * </p>
 *
 * <pre>
 * D:\Tools\apache-maven-3.9.11\bin\mvn.cmd -s D:/m2/settings.xml -Dmaven.repo.local=D:/m2/repository
 *     -Djava.io.tmpdir=D:/tmp-1007-084 -Dtest=CopyPlanTest -DfailIfNoTests=false test
 * </pre>
 * <p>
 * {@link #copiesFourSmallFilesExample()} is the four-file example: it publishes alpha.txt, beta.txt, gamma.txt and
 * delta.txt with one plan each.
 * </p>
 */
class CopyPlanTest extends AbstractTempDirTest {

    /**
     * Minimal {@link BasicFileAttributes} stub so link policy decisions can be tested on hosts where creating real
     * symbolic links requires privileges.
     */
    private static final class StubFileAttributes implements BasicFileAttributes {

        private final boolean symbolicLink;
        private final boolean regularFile;
        private final long size;

        StubFileAttributes(final boolean symbolicLink, final boolean regularFile, final long size) {
            this.symbolicLink = symbolicLink;
            this.regularFile = regularFile;
            this.size = size;
        }

        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public Object fileKey() {
            return null;
        }

        @Override
        public boolean isDirectory() {
            return !regularFile && !symbolicLink;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public boolean isRegularFile() {
            return regularFile;
        }

        @Override
        public boolean isSymbolicLink() {
            return symbolicLink;
        }

        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public long size() {
            return size;
        }
    }

    private static void assertNoTempFilesLeft(final Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            assertEquals(0, stream.filter(path -> path.getFileName().toString().endsWith(".tmp")).count(), "temporary files left behind");
        }
    }

    private static byte[] bytes(final String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private Path writeFile(final String fileName, final String content) throws IOException {
        final Path path = tempDirPath.resolve(fileName);
        Files.write(path, bytes(content));
        return path;
    }

    /**
     * Four-file example: publishes four small regular files, one plan per file.
     */
    @Test
    void copiesFourSmallFilesExample() throws IOException {
        final String[] names = { "alpha.txt", "beta.txt", "gamma.txt", "delta.txt" };
        final String[] contents = { "A", "BB", "CCC", "DDDD" };
        for (int i = 0; i < names.length; i++) {
            final Path source = writeFile("source-" + names[i], contents[i]);
            final Path target = tempDirPath.resolve(names[i]);
            assertEquals(target, CopyPlan.of(source, target).execute());
            assertArrayEquals(bytes(contents[i]), Files.readAllBytes(target));
        }
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void defaultsToForbidLinksAndNoPermissionPreservation() throws IOException {
        final Path source = writeFile("source.txt", "abc");
        final CopyPlan plan = CopyPlan.of(source, tempDirPath.resolve("target.txt"));
        assertEquals(LinkPolicy.FORBID, plan.getLinkPolicy());
        assertEquals(PermissionPolicy.NONE, plan.getPermissionPolicy());
        assertEquals(CopyPlan.DEFAULT_MAX_BYTES, plan.getMaxBytes());
        assertEquals(source, plan.getSource());
    }

    @Test
    void keepsOldTargetWhenPublishFails() throws IOException {
        final Path source = writeFile("source.txt", "new-content");
        final Path target = writeFile("target.txt", "old-content");
        final CopyPlan plan = CopyPlan.builder(source, target)
            .onBeforeMove(() -> {
                throw new IOException("simulated publish failure");
            })
            .build();
        final IOException exception = assertThrows(IOException.class, plan::execute);
        assertEquals("simulated publish failure", exception.getMessage());
        // The pre-existing target is never half-written.
        assertArrayEquals(bytes("old-content"), Files.readAllBytes(target));
        // Only this execution's temporary file is cleaned up.
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void keepsOldTargetWhenSourceChangesMidCopy() throws IOException {
        final Path source = writeFile("source.txt", "new-content");
        final Path target = writeFile("target.txt", "old-content");
        final CopyPlan plan = CopyPlan.builder(source, target)
            .onAfterCopy(() -> {
                // Mutate the source while the copy is in flight; a different size changes the identity
                // even on file systems with coarse modification-time granularity.
                Files.write(source, bytes("mutated-content-with-different-size"));
            })
            .build();
        assertThrows(SourceChangedException.class, plan::execute);
        // The pre-existing target is never half-written.
        assertArrayEquals(bytes("old-content"), Files.readAllBytes(target));
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void keepsOldTargetWhenSourceChangesAfterPlanWasBuilt() throws IOException {
        final Path source = writeFile("source.txt", "new-content");
        final Path target = writeFile("target.txt", "old-content");
        final CopyPlan plan = CopyPlan.of(source, target);
        Files.write(source, bytes("changed-after-planning"));
        assertThrows(SourceChangedException.class, plan::execute);
        assertArrayEquals(bytes("old-content"), Files.readAllBytes(target));
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void preservesPosixPermissionsOnlyWhenDeclared() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX attributes unsupported here");
        final Path source = writeFile("source.txt", "abc");
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
        Files.setPosixFilePermissions(source, permissions);
        final Path target = tempDirPath.resolve("target.txt");
        CopyPlan.builder(source, target).permissionPolicy(PermissionPolicy.PRESERVE_POSIX).build().execute();
        assertEquals(permissions, Files.getPosixFilePermissions(target));
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void publishesThreeByteFile() throws IOException {
        final Path source = writeFile("source.txt", "abc");
        final Path target = tempDirPath.resolve("published.txt");
        assertEquals(target, CopyPlan.of(source, target).execute());
        assertEquals(3, Files.size(target));
        assertArrayEquals(new byte[] { 'a', 'b', 'c' }, Files.readAllBytes(target));
        assertNoTempFilesLeft(tempDirPath);
    }

    @Test
    void rejectsDirectory() {
        assertThrows(IOException.class, () -> CopyPlan.of(tempDirPath, tempDirPath.resolve("target.txt")));
    }

    @Test
    void rejectsExternalLinkPerDeclaredPolicy() throws IOException {
        final Path source = tempDirPath.resolve("link.txt");
        final BasicFileAttributes symbolicLink = new StubFileAttributes(true, false, 3);
        final BasicFileAttributes regularFile = new StubFileAttributes(false, true, 3);
        // FORBID rejects a link, for example one pointing outside the source directory.
        assertThrows(IOException.class,
            () -> CopyPlan.checkSourceAttributes(source, symbolicLink, LinkPolicy.FORBID, CopyPlan.DEFAULT_MAX_BYTES));
        // Under FOLLOW the attributes describe the link target, so a bare link stub is not a regular file.
        assertThrows(IOException.class,
            () -> CopyPlan.checkSourceAttributes(source, symbolicLink, LinkPolicy.FOLLOW, CopyPlan.DEFAULT_MAX_BYTES));
        // Regular files pass both policies.
        CopyPlan.checkSourceAttributes(source, regularFile, LinkPolicy.FORBID, CopyPlan.DEFAULT_MAX_BYTES);
        CopyPlan.checkSourceAttributes(source, regularFile, LinkPolicy.FOLLOW, CopyPlan.DEFAULT_MAX_BYTES);
    }

    @Test
    void rejectsOversizedSource() throws IOException {
        final Path source = writeFile("source.txt", "0123456789abcdef");
        assertThrows(IOException.class, () -> CopyPlan.builder(source, tempDirPath.resolve("target.txt")).maxBytes(4).build());
        assertThrows(IllegalArgumentException.class, () -> CopyPlan.builder(source, tempDirPath.resolve("target.txt")).maxBytes(-1));
    }

    @Test
    void rejectsRealSymbolicLinkWhenForbiddenAndFollowsWhenDeclared() throws IOException {
        final Path outsideDirectory = Files.createDirectory(tempDirPath.resolve("outside"));
        final Path secret = outsideDirectory.resolve("secret.txt");
        Files.write(secret, bytes("external"));
        final Path link = tempDirPath.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (final UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "Creating symbolic links requires privileges on this host: " + e);
        }
        // FORBID: the external link is rejected and no target is created.
        final Path forbiddenTarget = tempDirPath.resolve("forbidden-target.txt");
        assertThrows(IOException.class, () -> CopyPlan.of(link, forbiddenTarget));
        assertTrue(Files.notExists(forbiddenTarget));
        // FOLLOW: the declared policy follows the link and publishes its content.
        final Path followedTarget = tempDirPath.resolve("followed-target.txt");
        CopyPlan.builder(link, followedTarget).linkPolicy(LinkPolicy.FOLLOW).build().execute();
        assertArrayEquals(bytes("external"), Files.readAllBytes(followedTarget));
        assertNoTempFilesLeft(tempDirPath);
    }
}
