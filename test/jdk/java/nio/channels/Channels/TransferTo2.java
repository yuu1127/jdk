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

import java.io.BufferedInputStream;
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
 * @run testng/othervm/timeout=180 TransferTo2
 * @bug 8278268
 * @summary Tests FileChannel.transferFrom() optimized case
 * @key randomness
 */
public class TransferTo2 extends AbstractTransferTo {

    /*
     * Provides test scenarios, i.e., combinations of input and output streams
     * to be tested.
     */
    @DataProvider
    public static Object[][] streamCombinations() {
        return new Object[][] {
            // tests FileChannel.transferFrom(SelectableChannelOutput) optimized case
            {selectableChannelInput(), fileChannelOutput()},

            // tests FileChannel.transferFrom(ReadableByteChannelInput) optimized case
            {readableByteChannelInput(), fileChannelOutput()},
        };
    }

    /*
     * Input streams to be tested.
     */
    @DataProvider
    public static Object[][] inputStreamProviders() {
        return new Object[][] {
            {selectableChannelInput()},
            {readableByteChannelInput()}
        };
    }

    /*
     * Special test for stream-to-file transfer of more than 2 GB. This test
     * covers multiple iterations of FileChannel.transferFrom(ReadableByteChannel),
     * which ChannelInputStream.transferFrom() only applies in this particular
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

                // performing actual transfer, effectively by multiple invocations of
                // FileChannel.transferFrom(ReadableByteChannel)
                try (InputStream inputStream = Channels.newInputStream(Channels.newChannel(
                        new BufferedInputStream(Files.newInputStream(sourceFile))));
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
     * Creates a provider for an input stream which wraps a selectable channel
     */
    private static InputStreamProvider selectableChannelInput() {
        return bytes -> {
            Pipe pipe = Pipe.open();
            new Thread(() -> {
                try (OutputStream os = Channels.newOutputStream(pipe.sink())) {
                  os.write(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();
            return Channels.newInputStream(pipe.source());
        };
    }

}
