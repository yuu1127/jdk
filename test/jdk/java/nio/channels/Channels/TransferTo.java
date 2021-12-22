/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import jdk.test.lib.RandomFactory;

import static java.lang.String.format;
import static java.nio.file.StandardOpenOption.*;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.RandomFactory
 * @run testng/othervm/timeout=180 TransferTo
 * @bug 8265891
 * @summary Tests whether sun.nio.ChannelInputStream.transferTo conforms to the
 *          InputStream.transferTo specification
 * @key randomness
 */
public class TransferTo extends AbstractTransferTo {

    /*
     * Provides test scenarios, i.e., combinations of input and output streams
     * to be tested.
     */
    @DataProvider
    public static Object[][] streamCombinations() {
        return new Object[][] {
            // tests FileChannel.transferTo(FileChannel) optimized case
            {fileChannelInput(), fileChannelOutput()},

            // tests FileChannel.transferTo(SelectableChannelOutput)
            // optimized case
            {fileChannelInput(), selectableChannelOutput()},

            // tests FileChannel.transferTo(WritableByteChannelOutput)
            // optimized case
            {fileChannelInput(), writableByteChannelOutput()},

            // tests InputStream.transferTo(OutputStream) default case
            {readableByteChannelInput(), defaultOutput()}
        };
    }

    /*
     * Input streams to be tested.
     */
    @DataProvider
    public static Object[][] inputStreamProviders() {
        return new Object[][] {
                {fileChannelInput()},
                {readableByteChannelInput()}
        };
    }

    /*
     * Special test for file-to-stream transfer of more than 2 GB. This test
     * covers multiple iterations of FileChannel.transferTo(WritableByteChannel),
     * which ChannelInputStream.transferTo() only applies in this particular
     * case, and cannot get tested using a single byte[] due to size limitation
     * of arrays.
     */
    @Test
    public void testMoreThanTwoGB() throws IOException {
        // prepare two temporary files to be compared at the end of the test
        // set the source file name
        String sourceName = String.format("test3GBSource%s.tmp",
            RND.nextInt(Integer.MAX_VALUE));
        Path sourceFile = CWD.resolve(sourceName);

        try {
            // set the target file name
            String targetName = String.format("test3GBTarget%s.tmp",
                RND.nextInt(Integer.MAX_VALUE));
            Path targetFile = CWD.resolve(targetName);

            try {
                // calculate initial position to be just short of 2GB
                final long initPos = 2047*BYTES_PER_WRITE;

                // create the source file with a hint to be sparse
                try (FileChannel fc = FileChannel.open(sourceFile, CREATE_NEW, SPARSE, WRITE, APPEND)) {
                    // set initial position to avoid writing nearly 2GB
                    fc.position(initPos);

                    // Add random bytes to the remainder of the file
                    int nw = (int)(NUM_WRITES - initPos/BYTES_PER_WRITE);
                    for (int i = 0; i < nw; i++) {
                        byte[] rndBytes = createRandomBytes(BYTES_PER_WRITE, 0);
                        ByteBuffer src = ByteBuffer.wrap(rndBytes);
                        fc.write(src);
                    }
                }

                // create the target file with a hint to be sparse
                try (FileChannel fc = FileChannel.open(targetFile, CREATE_NEW, WRITE, SPARSE)) {
                }

                // perform actual transfer, effectively by multiple invocations
                // of FileChannel.transferTo(WritableByteChannel)
                try (InputStream inputStream = Channels.newInputStream(FileChannel.open(sourceFile));
                     OutputStream outputStream = Channels.newOutputStream(FileChannel.open(targetFile, WRITE))) {
                    long count = inputStream.transferTo(outputStream);

                    // compare reported transferred bytes, must be 3 GB
                    // less the value of the initial position
                    assertEquals(count, BYTES_WRITTEN - initPos);
                }

                // compare content of both files, failing if different
                assertEquals(Files.mismatch(sourceFile, targetFile), -1);

            } finally {
                 Files.delete(targetFile);
            }
        } finally {
            Files.delete(sourceFile);
        }
    }

    /*
     * Special test whether selectable channel based transfer throws blocking mode exception.
     */
    @Test
    public void testIllegalBlockingMode() throws IOException {
        Pipe pipe = Pipe.open();
        try {
            // testing arbitrary input (here: empty file) to non-blocking
            // selectable output
            try (FileChannel fc = FileChannel.open(Files.createTempFile(CWD, "testIllegalBlockingMode", null));
                InputStream is = Channels.newInputStream(fc);
                SelectableChannel sc = pipe.sink().configureBlocking(false);
                OutputStream os = Channels.newOutputStream((WritableByteChannel) sc)) {

                // IllegalBlockingMode must be thrown when trying to perform
                // a transfer
                assertThrows(IllegalBlockingModeException.class, () -> is.transferTo(os));
            }

            // testing non-blocking selectable input to arbitrary output
            // (here: byte array)
            try (SelectableChannel sc = pipe.source().configureBlocking(false);
                InputStream is = Channels.newInputStream((ReadableByteChannel) sc);
                OutputStream os = new ByteArrayOutputStream()) {

                // IllegalBlockingMode must be thrown when trying to perform
                // a transfer
                assertThrows(IllegalBlockingModeException.class, () -> is.transferTo(os));
            }
        } finally {
            pipe.source().close();
            pipe.sink().close();
        }
    }

    /*
     * Creates a provider for an output stream which does not wrap a channel
     */
    private static OutputStreamProvider defaultOutput() {
        return spy -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            spy.accept(outputStream::toByteArray);
            return outputStream;
        };
    }

    /*
     * Creates a provider for an input stream which wraps a file channel
     */
    private static InputStreamProvider fileChannelInput() {
        return bytes -> {
            Path path = Files.createTempFile(CWD, "fileChannelInput", null);
            Files.write(path, bytes);
            FileChannel fileChannel = FileChannel.open(path);
            return Channels.newInputStream(fileChannel);
        };
    }

    /*
     * Creates a provider for an output stream which wraps a selectable channel
     */
    private static OutputStreamProvider selectableChannelOutput() {
        return spy -> {
            Pipe pipe = Pipe.open();
            Future<byte[]> bytes = CompletableFuture.supplyAsync(() -> {
                try {
                    InputStream is = Channels.newInputStream(pipe.source());
                    return is.readAllBytes();
                } catch (IOException e) {
                    throw new AssertionError("Exception while asserting content", e);
                }
            });
            final OutputStream os = Channels.newOutputStream(pipe.sink());
            spy.accept(() -> {
                try {
                    os.close();
                    return bytes.get();
                } catch (IOException | InterruptedException | ExecutionException e) {
                    throw new AssertionError("Exception while asserting content", e);
                }
            });
            return os;
        };
    }

    /*
     * Creates a provider for an output stream which wraps a writable byte channel but is not a file channel
     */
    private static OutputStreamProvider writableByteChannelOutput() {
        return spy -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            spy.accept(outputStream::toByteArray);
            return Channels.newOutputStream(Channels.newChannel(outputStream));
        };
    }

}
