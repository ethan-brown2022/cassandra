/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.io.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.DseLegacy;
import org.apache.cassandra.utils.FastByteOperations;
import org.apache.cassandra.utils.INativeLibrary;
import org.apache.cassandra.utils.NativeLibrary;
import org.assertj.core.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUtilsTest
{

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @Test
    public void testParseFileSize() throws Exception
    {
        // test straightforward conversions for each unit
        assertEquals("FileUtils.parseFileSize() failed to parse a whole number of bytes",
            256L, FileUtils.parseFileSize("256 bytes"));
        assertEquals("FileUtils.parseFileSize() failed to parse a whole number of kilobytes",
            2048L, FileUtils.parseFileSize("2 KiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a whole number of megabytes",
            4194304L, FileUtils.parseFileSize("4 MiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a whole number of gigabytes",
            3221225472L, FileUtils.parseFileSize("3 GiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a whole number of terabytes",
            5497558138880L, FileUtils.parseFileSize("5 TiB"));
        // test conversions of fractional units
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of kilobytes",
            1536L, FileUtils.parseFileSize("1.5 KiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of kilobytes",
            4434L, FileUtils.parseFileSize("4.33 KiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of megabytes",
            2359296L, FileUtils.parseFileSize("2.25 MiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of megabytes",
            3292529L, FileUtils.parseFileSize("3.14 MiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of gigabytes",
            1299227607L, FileUtils.parseFileSize("1.21 GiB"));
        assertEquals("FileUtils.parseFileSize() failed to parse a rational number of terabytes",
            6621259022467L, FileUtils.parseFileSize("6.022 TiB"));
    }

    @Test
    public void testDelete()
    {
        File file = FileUtils.createDeletableTempFile("testTruncate", "1");
        FileUtils.delete(file);
    }

    @Test
    public void testTruncate() throws IOException
    {
        File file = FileUtils.createDeletableTempFile("testTruncate", "1");
        final String expected = "The quick brown fox jumps over the lazy dog";

        Files.write(file.toPath(), expected.getBytes());
        assertTrue(file.exists());

        byte[] b = Files.readAllBytes(file.toPath());
        assertEquals(expected, new String(b, StandardCharsets.UTF_8));

        FileUtils.truncate(file.absolutePath(), 10);
        b = Files.readAllBytes(file.toPath());
        assertEquals("The quick ", new String(b, StandardCharsets.UTF_8));

        FileUtils.truncate(file.absolutePath(), 0);
        b = Files.readAllBytes(file.toPath());
        assertEquals(0, b.length);
    }

    @Test
    public void testFolderSize() throws Exception
    {
        File folder = createFolder(DatabaseDescriptor.getAllDataFileLocations()[0], "testFolderSize");
        folder.deleteOnExit();

        File childFolder = createFolder(folder, "child");

        File[] files = {
                       createFile(new File(folder, "001"), 10000),
                       createFile(new File(folder, "002"), 1000),
                       createFile(new File(folder, "003"), 100),
                       createFile(new File(childFolder, "001"), 1000),
                       createFile(new File(childFolder, "002"), 2000),
        };

        assertEquals(0, FileUtils.folderSize(new File(folder, "i_dont_exist")));
        assertEquals(files[0].length(), FileUtils.folderSize(files[0]));

        long size = FileUtils.folderSize(folder);
        assertEquals(Arrays.stream(files).mapToLong(f -> f.length()).sum(), size);
    }

    @Test
    public void testIsContained()
    {
        assertTrue(FileUtils.isContained(new File("/tmp/abc"), new File("/tmp/abc")));
        assertFalse(FileUtils.isContained(new File("/tmp/abc"), new File("/tmp/abcd")));
        assertTrue(FileUtils.isContained(new File("/tmp/abc"), new File("/tmp/abc/d")));
        assertTrue(FileUtils.isContained(new File("/tmp/abc/../abc"), new File("/tmp/abc/d")));
        assertFalse(FileUtils.isContained(new File("/tmp/abc/../abc"), new File("/tmp/abcc")));
    }

    @Test
    public void testMoveFiles() throws IOException
    {
        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());
        Path sourceDir = Files.createDirectory(tmpDir.resolve("source"));
        Path subDir_1 = Files.createDirectory(sourceDir.resolve("a"));
        subDir_1.resolve("file_1.txt").toFile().createNewFile();
        subDir_1.resolve("file_2.txt").toFile().createNewFile();
        Path subDir_11 = Files.createDirectory(subDir_1.resolve("ab"));
        subDir_11.resolve("file_1.txt").toFile().createNewFile();
        subDir_11.resolve("file_2.txt").toFile().createNewFile();
        subDir_11.resolve("file_3.txt").toFile().createNewFile();
        Path subDir_12 = Files.createDirectory(subDir_1.resolve("ac"));
        Path subDir_2 = Files.createDirectory(sourceDir.resolve("b"));
        subDir_2.resolve("file_1.txt").toFile().createNewFile();
        subDir_2.resolve("file_2.txt").toFile().createNewFile();

        Path targetDir = Files.createDirectory(tmpDir.resolve("target"));

        FileUtils.moveRecursively(sourceDir, targetDir);

        assertThat(sourceDir).doesNotExist();
        assertThat(targetDir.resolve("a/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_3.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/ac/")).exists();
        assertThat(targetDir.resolve("b/file_1.txt")).exists();
        assertThat(targetDir.resolve("b/file_2.txt")).exists();

        // Tests that files can be moved into existing directories

        sourceDir = Files.createDirectory(tmpDir.resolve("source2"));
        subDir_1 = Files.createDirectory(sourceDir.resolve("a"));
        subDir_1.resolve("file_3.txt").toFile().createNewFile();
        subDir_11 = Files.createDirectory(subDir_1.resolve("ab"));
        subDir_11.resolve("file_4.txt").toFile().createNewFile();

        FileUtils.moveRecursively(sourceDir, targetDir);

        assertThat(sourceDir).doesNotExist();
        assertThat(targetDir.resolve("a/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/file_3.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_3.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_4.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_1.txt")).exists();
        assertThat(targetDir.resolve("a/ab/file_2.txt")).exists();
        assertThat(targetDir.resolve("a/ac/")).exists();
        assertThat(targetDir.resolve("b/file_1.txt")).exists();
        assertThat(targetDir.resolve("b/file_2.txt")).exists();

        // Tests that existing files are not replaced but trigger an error.

        sourceDir = Files.createDirectory(tmpDir.resolve("source3"));
        subDir_1 = Files.createDirectory(sourceDir.resolve("a"));
        subDir_1.resolve("file_3.txt").toFile().createNewFile();
        FileUtils.moveRecursively(sourceDir, targetDir);

        assertThat(sourceDir).exists();
        assertThat(sourceDir.resolve("a/file_3.txt")).exists();
        assertThat(targetDir.resolve("a/file_3.txt")).exists();
    }

    @Test
    public void testDeleteDirectoryIfEmpty() throws IOException
    {
        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());
        Path subDir_1 = Files.createDirectory(tmpDir.resolve("a"));
        Path subDir_2 = Files.createDirectory(tmpDir.resolve("b"));
        Path file_1 = subDir_2.resolve("file_1.txt");
        file_1.toFile().createNewFile();

        FileUtils.deleteDirectoryIfEmpty(subDir_1);
        assertThat(subDir_1).doesNotExist();

        FileUtils.deleteDirectoryIfEmpty(subDir_2);
        assertThat(subDir_2).exists();

        Assertions.assertThatThrownBy(() -> FileUtils.deleteDirectoryIfEmpty(file_1))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("is not a directory");
    }

    @Test
    public void testSize() throws IOException
    {
        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());
        Path path = tmpDir.resolve("a.txt");
        Assert.assertEquals(0, FileUtils.size(path));

        createFile(new File(path), 10000);
        Assert.assertEquals(10000, FileUtils.size(path));
    }

    @Test
    public void testCreateHardLinkWithoutConfirm() throws Throwable
    {
        Assume.assumeTrue(NativeLibrary.instance.isOS(INativeLibrary.OSType.LINUX));

        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());

        Path from = tmpDir.resolve("b.txt");
        writeData(new File(from), 100);
        Assert.assertTrue(Files.exists(from));
        Assert.assertEquals(1, Files.getAttribute(from, "unix:nlink"));

        Path to = tmpDir.resolve("c.txt");
        Assert.assertFalse(Files.exists(to));
    }

    @Test
    public void testCopyWithOutConfirm() throws Throwable
    {
        Assume.assumeTrue(NativeLibrary.instance.isOS(INativeLibrary.OSType.LINUX));

        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());

        Path from = tmpDir.resolve("b.txt");
        writeData(new File(from), 100);
        Assert.assertTrue(Files.exists(from));
        Assert.assertEquals(1, Files.getAttribute(from, "unix:nlink"));

        Path to = tmpDir.resolve("c.txt");
        Assert.assertFalse(Files.exists(to));

        FileUtils.copyWithOutConfirm(new File(from), new File(to));
        compareFile(from, to);
        Assert.assertEquals(1, Files.getAttribute(from, "unix:nlink"));
        Assert.assertEquals(1, Files.getAttribute(to, "unix:nlink"));

        File nonExisting = new File(from.resolveSibling("non_existing.txt"));
        to = tmpDir.resolve("d.txt");
        FileUtils.copyWithOutConfirm(nonExisting, new File(to));
        Assert.assertFalse(new File(to).exists());
    }

    @Test
    public void testLegacyDSEAPI() throws IOException
    {
        Path tmpDir = Files.createTempDirectory(this.getClass().getSimpleName());

        FileUtils.createDirectory(tmpDir);
        Path f = tmpDir.resolve("somefile");
        FileUtils.appendAndSync(f, "lorem", "ipsum");
        assertEquals(Arrays.asList(f), FileUtils.listPaths(tmpDir, path -> true));
        assertEquals(Arrays.asList(f), FileUtils.listPaths(tmpDir));
        FileUtils.deleteContent(tmpDir);
        assertEquals(Arrays.asList(), FileUtils.listPaths(tmpDir));
        FileUtils.delete(tmpDir);
        FileUtils.deleteRecursive(tmpDir);
    }

    private File createFolder(File folder, String additionalName)
    {
        folder = folder.resolve(additionalName);
        FileUtils.createDirectory(folder);
        return folder;
    }

    private File createFile(File file, long size)
    {
        try (RandomAccessFile f = new RandomAccessFile(file.toJavaIOFile(), "rw"))
        {
            f.setLength(size);
        }
        catch (Exception e)
        {
            System.err.println(e);
        }
        return file;
    }

    private File writeData(File file, int size) throws Throwable
    {
        Random random = new Random();
        try (RandomAccessFile f = new RandomAccessFile(file.toJavaIOFile(), "rw"))
        {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);

            f.write(bytes);
        }
        return file;
    }

    private boolean compareFile(Path left, Path right) throws IOException
    {
        return FastByteOperations.compareUnsigned(ByteBuffer.wrap(Files.readAllBytes(left)), ByteBuffer.wrap(Files.readAllBytes(right))) == 0;
    }
}
