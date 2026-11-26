package io.github.c1589190.agentmosire.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Uses directory-relative NIO operations to preserve confinement across path changes. */
final class WorkspaceFiles {
    static final String STAGING_PREFIX = ".mosire-";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final int MAX_LIST_ENTRIES = 100;

    private WorkspaceFiles() { }

    record Listing(List<String> entries, boolean truncated) { }

    static Listing list(Path path, String virtual) throws IOException {
        var names = new TreeSet<String>();
        boolean truncated = false;
        try (var directory = open(path)) {
            for (var child : directory) {
                var name = child.getFileName();
                if (!WorkspaceTools.validSegment(name.toString())) {
                    continue;
                }
                try {
                    var attrs = directory.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW)
                            .readAttributes();
                    if (attrs.isRegularFile() || attrs.isDirectory()) {
                        names.add(virtual + "/" + name);
                        if (names.size() > MAX_LIST_ENTRIES) {
                            truncated = true;
                            names.pollLast();
                        }
                    }
                } catch (NoSuchFileException removed) {
                    // An entry can disappear during enumeration.
                }
            }
        }
        return new Listing(List.copyOf(names), truncated);
    }

    static byte[] read(Path path, int maxBytes) throws IOException {
        try (var directory = open(path.getParent())) {
            requireFile(directory, path.getFileName(), false);
            try (var channel = directory.newByteChannel(path.getFileName(), Set.of(StandardOpenOption.READ, NOFOLLOW));
                    var input = Channels.newInputStream(channel)) {
                byte[] bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IllegalArgumentException("The file exceeds the workspace read limit");
                }
                return bytes;
            }
        }
    }

    static void write(Path path, ByteBuffer bytes) throws IOException {
        try (var directory = open(path.getParent())) {
            var name = path.getFileName();
            requireFile(directory, name, true);
            Path temporary = path.getFileSystem().getPath(STAGING_PREFIX + UUID.randomUUID());
            boolean staged = false;
            try {
                try (var channel = directory.newByteChannel(temporary,
                        Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW))) {
                    staged = true;
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                }
                requireFile(directory, name, true);
                directory.move(temporary, directory, name);
                staged = false;
            } finally {
                if (staged) {
                    directory.deleteFile(temporary);
                }
            }
        }
    }

    private static void requireFile(SecureDirectoryStream<Path> directory, Path name, boolean allowMissing)
            throws IOException {
        try {
            if (!directory.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW)
                    .readAttributes().isRegularFile()) {
                throw new AccessDeniedException(name.toString());
            }
        } catch (NoSuchFileException missing) {
            if (!allowMissing) {
                throw missing;
            }
        }
    }

    private static SecureDirectoryStream<Path> open(Path path) throws IOException {
        var stream = Files.newDirectoryStream(path.getRoot());
        if (!(stream instanceof SecureDirectoryStream<Path> directory)) {
            stream.close();
            throw new AccessDeniedException("The file system lacks secure directory-relative I/O");
        }
        var current = directory;
        try {
            for (var part : path) {
                var next = current.newDirectoryStream(part, NOFOLLOW);
                try {
                    current.close();
                } catch (IOException failure) {
                    next.close();
                    throw failure;
                }
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }
}
