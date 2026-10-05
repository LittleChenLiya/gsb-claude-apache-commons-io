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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.io.file.CopyPlan.LinkPolicy;
import org.apache.commons.io.file.CopyPlan.PermissionScope;
import org.apache.commons.io.file.CopyPlan.SourceChangedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link CopyPlan}.
 */
class CopyPlanTest {

    private static final byte[] OLD = "OLD".getBytes(StandardCharsets.UTF_8);

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @TempDir
    Path tempDir;

    private void assertOnlyLeftovers(final Path directory, final Path... expected) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            final List<Path> actual = new ArrayList<>();
            stream.forEach(actual::add);
            assertEquals(expected.length, actual.size(), () -> "Unexpected leftovers in " + directory + ": " + actual);
            for (final Path path : expected) {
                assertTrue(actual.contains(path), () -> "Missing " + path + " in " + actual);
            }
        }
    }

    private boolean createSymbolicLink(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (final UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    @Test
    void publishesThreeByteFile() throws IOException {
        final Path source = tempDir.resolve("source.bin");
        Files.write(source, new byte[] { 1, 2, 3 });
        final Path targetDir = Files.createDirectory(tempDir.resolve("out"));
        final Path target = targetDir.resolve("target.bin");

        assertEquals(target, CopyPlan.copy(source, target));

        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(target));
        assertEquals(3, Files.size(target));
        assertOnlyLeftovers(targetDir, target);
    }

    @Test
    void publishesToShortTargetName() throws IOException {
        final Path source = tempDir.resolve("source.bin");
        Files.write(source, new byte[] { 7 });
        final Path target = tempDir.resolve("ab");

        CopyPlan.copy(source, target);

        assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(target));
        assertOnlyLeftovers(tempDir, source, target);
    }

    @Test
    void copiesFourSmallFiles() throws IOException {
        final Path sourceDir = Files.createDirectory(tempDir.resolve("src4"));
        final Path targetDir = Files.createDirectory(tempDir.resolve("dst4"));
        final String[] names = { "a.txt", "b.txt", "c.txt", "d.txt" };
        final List<CopyPlan> plans = new ArrayList<>();
        for (final String name : names) {
            final Path source = sourceDir.resolve(name);
            Files.write(source, bytes("content-of-" + name));
            plans.add(CopyPlan.builder(source, targetDir.resolve(name))
                .setLinkPolicy(LinkPolicy.REJECT_LINKS)
                .setPermissionScope(PermissionScope.LAST_MODIFIED_TIME)
                .build());
        }

        final List<Path> published = CopyPlan.executeAll(plans);

        assertEquals(4, published.size());
        for (final String name : names) {
            assertArrayEquals(bytes("content-of-" + name), Files.readAllBytes(targetDir.resolve(name)));
        }
        assertOnlyLeftovers(targetDir, published.toArray(new Path[0]));
    }

    @Test
    void sourceChangedDuringCopyKeepsOldTarget() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("SOURCE-V1"));
        final Path targetDir = Files.createDirectory(tempDir.resolve("out"));
        final Path target = targetDir.resolve("target.txt");
        Files.write(target, OLD);
        final CopyPlan plan = CopyPlan.builder(source, target).build()
            .withTempFileReadyCallback(temp -> Files.write(source, bytes("SOURCE-V2-CHANGED-IN-FLIGHT")));

        assertThrows(SourceChangedException.class, plan::execute);

        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertOnlyLeftovers(targetDir, target);
    }

    @Test
    void failedSecondWriteKeepsOldTarget() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("NEW-CONTENT"));
        final Path targetDir = Files.createDirectory(tempDir.resolve("out"));
        final Path target = targetDir.resolve("target.txt");
        Files.write(target, OLD);
        final CopyPlan plan = CopyPlan.builder(source, target).build()
            .withMover((temp, dest) -> {
                throw new IOException("Simulated failure of the second write (the publish move)");
            });

        assertThrows(IOException.class, plan::execute);

        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertOnlyLeftovers(targetDir, target);
    }

    @Test
    void failedMoveToDirectoryTargetKeepsExistingEntry() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("NEW-CONTENT"));
        final Path targetDir = Files.createDirectory(tempDir.resolve("out"));
        final Path target = Files.createDirectory(targetDir.resolve("target.txt"));
        final Path kept = Files.write(target.resolve("kept.txt"), OLD);

        assertThrows(IOException.class, () -> CopyPlan.copy(source, target));

        assertTrue(Files.isDirectory(target));
        assertArrayEquals(OLD, Files.readAllBytes(kept));
        assertOnlyLeftovers(targetDir, target);
    }

    @Test
    void rejectsSymbolicLinkSourceWhenPolicyRejects() throws IOException {
        final Path real = tempDir.resolve("real.txt");
        Files.write(real, bytes("REAL"));
        final Path link = tempDir.resolve("link.txt");
        assumeTrue(createSymbolicLink(link, real), "This test host cannot create symbolic links");

        assertThrows(FileSystemException.class, () -> CopyPlan.copy(link, tempDir.resolve("out.txt")));
    }

    @Test
    void followsSymbolicLinkSourceWhenPolicyAllows() throws IOException {
        final Path real = tempDir.resolve("real.txt");
        Files.write(real, bytes("REAL"));
        final Path link = tempDir.resolve("link.txt");
        assumeTrue(createSymbolicLink(link, real), "This test host cannot create symbolic links");
        final Path target = tempDir.resolve("out.txt");

        CopyPlan.builder(link, target).setLinkPolicy(LinkPolicy.FOLLOW_LINKS).build().execute();

        assertArrayEquals(bytes("REAL"), Files.readAllBytes(target));
    }

    @Test
    void rejectsSymbolicLinkTargetWhenPolicyRejects() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("NEW"));
        final Path elsewhere = tempDir.resolve("elsewhere.txt");
        Files.write(elsewhere, OLD);
        final Path link = tempDir.resolve("link-target.txt");
        assumeTrue(createSymbolicLink(link, elsewhere), "This test host cannot create symbolic links");

        assertThrows(FileSystemException.class, () -> CopyPlan.copy(source, link));

        assertArrayEquals(OLD, Files.readAllBytes(elsewhere));
    }

    @Test
    void rejectsDirectorySourceUnderBothLinkPolicies() {
        assertThrows(FileSystemException.class,
            () -> CopyPlan.builder(tempDir, tempDir.resolve("out1")).setLinkPolicy(LinkPolicy.REJECT_LINKS).build().execute());
        assertThrows(FileSystemException.class,
            () -> CopyPlan.builder(tempDir, tempDir.resolve("out2")).setLinkPolicy(LinkPolicy.FOLLOW_LINKS).build().execute());
    }

    @Test
    void rejectsJunctionSourceOnWindows() throws IOException {
        assumeTrue(isWindows(), "Junctions are a Windows fixture");
        final Path realDir = Files.createDirectory(tempDir.resolve("real-dir"));
        Files.write(realDir.resolve("data.txt"), bytes("DATA"));
        final Path junction = tempDir.resolve("junction");
        final Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
            junction.toAbsolutePath().toString(), realDir.toAbsolutePath().toString())
            .redirectErrorStream(true).start();
        boolean created;
        try {
            created = process.waitFor() == 0 && Files.exists(junction);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            created = false;
        }
        assumeTrue(created, "This test host cannot create junctions");

        assertThrows(FileSystemException.class, () -> CopyPlan.copy(junction, tempDir.resolve("out.txt")));
    }

    @Test
    void rejectsOversizeSource() throws IOException {
        final Path source = tempDir.resolve("big.bin");
        Files.write(source, new byte[16]);
        final Path target = tempDir.resolve("out.bin");

        assertThrows(FileSystemException.class,
            () -> CopyPlan.builder(source, target).setMaxBytes(4).build().execute());

        assertTrue(Files.notExists(target));
        assertOnlyLeftovers(tempDir, source);
    }

    @Test
    void rejectsExistingTargetWhenReplaceDisabled() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("NEW"));
        final Path target = tempDir.resolve("target.txt");
        Files.write(target, OLD);

        assertThrows(FileAlreadyExistsException.class,
            () -> CopyPlan.builder(source, target).setReplaceExisting(false).build().execute());

        assertArrayEquals(OLD, Files.readAllBytes(target));
        assertOnlyLeftovers(tempDir, source, target);
    }

    @Test
    void preservesLastModifiedTimeWhenScopeRequests() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("TIMED"));
        final FileTime past = FileTime.from(Instant.parse("2001-02-03T04:05:06Z"));
        Files.setLastModifiedTime(source, past);
        final Path target = tempDir.resolve("target.txt");

        CopyPlan.builder(source, target).setPermissionScope(PermissionScope.LAST_MODIFIED_TIME).build().execute();

        assertEquals(past, Files.getLastModifiedTime(target));
    }

    @Test
    void doesNotPreserveLastModifiedTimeByDefault() throws IOException {
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("TIMED"));
        final FileTime past = FileTime.from(Instant.parse("2001-02-03T04:05:06Z"));
        Files.setLastModifiedTime(source, past);
        final Path target = tempDir.resolve("target.txt");

        CopyPlan.copy(source, target);

        assertNotEquals(past, Files.getLastModifiedTime(target));
    }

    @Test
    void preservesPosixPermissionsWhenScopeRequests() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
            "This test host has no POSIX attribute view");
        final Path source = tempDir.resolve("source.txt");
        Files.write(source, bytes("PERMS"));
        final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwx------");
        Files.setPosixFilePermissions(source, permissions);
        final Path target = tempDir.resolve("target.txt");

        CopyPlan.builder(source, target).setPermissionScope(PermissionScope.POSIX_PERMISSIONS).build().execute();

        assertEquals(permissions, Files.getPosixFilePermissions(target));
    }

    @Test
    void gettersReflectBuilderPolicy() {
        final Path source = tempDir.resolve("source.txt");
        final Path target = tempDir.resolve("target.txt");
        final CopyPlan plan = CopyPlan.builder(source, target)
            .setLinkPolicy(LinkPolicy.FOLLOW_LINKS)
            .setPermissionScope(PermissionScope.ALL)
            .setReplaceExisting(false)
            .setMaxBytes(1024)
            .build();

        assertEquals(source, plan.getSource());
        assertEquals(target, plan.getTarget());
        assertEquals(LinkPolicy.FOLLOW_LINKS, plan.getLinkPolicy());
        assertEquals(PermissionScope.ALL, plan.getPermissionScope());
        assertEquals(1024, plan.getMaxBytes());
        assertTrue(!plan.isReplaceExisting());
    }
}
