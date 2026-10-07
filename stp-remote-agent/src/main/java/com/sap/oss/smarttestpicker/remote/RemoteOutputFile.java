// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Owns the output path lease, in-progress marker, stale artifact recovery, and final file replacement. */
final class RemoteOutputFile {
	private final Path output;
	private final Path marker;
	private final Path parent;
	private final String temporaryPrefix;
	private FileChannel leaseChannel;
	private FileLock lease;
	private boolean closed;

	private RemoteOutputFile(Path output, FileChannel leaseChannel, FileLock lease) {
		this.output = output;
		this.parent = output.getParent();
		this.marker = output.resolveSibling(output.getFileName() + ".inprogress");
		this.temporaryPrefix = temporaryPrefixFor(output);
		this.leaseChannel = leaseChannel;
		this.lease = lease;
	}

	static RemoteOutputFile reserve(Path configured) {
		if (configured == null) throw new IllegalArgumentException("Remote STP output path is required");
		Path output = configured.toAbsolutePath().normalize();
		if (output.getFileName() == null) throw failure("output path must name a file", output, null);
		Path parent = output.getParent();
		FileChannel channel = null;
		FileLock lock = null;
		try {
			Files.createDirectories(parent);
			Path leasePath = output.resolveSibling(output.getFileName() + ".lock");
			if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)
					&& (!Files.isRegularFile(leasePath, LinkOption.NOFOLLOW_LINKS) || Files.size(leasePath) != 0))
				throw failure("malformed output lock artifact", output, null);
			channel = FileChannel.open(leasePath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
			lock = acquire(channel, output);
			RemoteOutputFile files = new RemoteOutputFile(output, channel, lock);
			files.prepareRun();
			return files;
		} catch (IOException | SecurityException failure) {
			release(lock, channel);
			throw failure("cannot prepare output files", output, failure);
		} catch (RuntimeException failure) {
			release(lock, channel);
			throw failure;
		}
	}

	void finish(String serializedJson) {
		try {
			checkpoint(serializedJson);
			Files.delete(marker); // Marker removal is the final state transition.
		} catch (IOException | RuntimeException problem) {
			throw failure("cannot finalize observations; in-progress state was retained for recovery", output, problem);
		} finally {
			closeLease();
		}
	}

	/** Persists one complete checkpoint without closing the run reservation or output lease. */
	void checkpoint(String serializedJson) {
		Path temporary = null;
		Path backup = null;
		try {
			temporary = Files.createTempFile(parent, temporaryPrefix, ".tmp");
			writeAndSync(temporary, serializedJson);
			try {
				Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				// Preserve the last good checkpoint so a failed fallback replacement can roll back.
				if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
					backup = Files.createTempFile(parent, temporaryPrefix, ".tmp");
					Files.copy(output, backup, StandardCopyOption.REPLACE_EXISTING);
					force(backup);
				}
				try {
					Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException replacementFailure) {
					if (backup != null) {
						try {
							Files.move(backup, output, StandardCopyOption.REPLACE_EXISTING);
							backup = null;
						} catch (IOException restoreFailure) { replacementFailure.addSuppressed(restoreFailure); }
					}
					throw replacementFailure;
				}
			}
			temporary = null;
			if (backup != null) {
				try { Files.deleteIfExists(backup); backup = null; }
				catch (IOException cleanup) { System.err.println("[stp-remote-agent] unable to clean checkpoint backup for '" + output + "': " + cleanup); }
			}
			try { cleanupTemporaryFiles(); }
			catch (IOException cleanup) { System.err.println("[stp-remote-agent] unable to clean stale checkpoint temp files for '" + output + "': " + cleanup); }
		} catch (IOException | RuntimeException problem) {
			throw failure("cannot persist observations checkpoint; previous output was preserved when replacement failed and in-progress state remains", output, problem);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); }
				catch (IOException cleanup) { System.err.println("[stp-remote-agent] unable to clean temporary artifact for '" + output + "': " + cleanup); }
			}
			if (backup != null) {
				try { Files.deleteIfExists(backup); }
				catch (IOException cleanup) { System.err.println("[stp-remote-agent] unable to clean checkpoint backup for '" + output + "': " + cleanup); }
			}
		}
	}

	void abandon() { closeLease(); }

	Path path() { return output; }

	IllegalStateException serializationFailure(Throwable cause) {
		return failure("cannot serialize observations; in-progress state was retained for recovery", output, cause);
	}

	private void prepareRun() throws IOException {
		boolean hadMarker = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
		if (hadMarker) validateMarker();
		if (!hadMarker) {
			Artifact state = artifact(output);
			if (state == Artifact.VALID) throw failure("completed output already exists; archive or remove it before starting another run", output, null);
			if (state == Artifact.DIRECTORY_OR_SPECIAL || state == Artifact.INVALID)
				throw failure("output exists without an in-progress marker and is not a valid completed STP file", output, null);
			boolean legacyTemporary = hasLegacyTemporaryFile();
			boolean currentTemporaries = hasTemporaryFiles();
			if (state == Artifact.EMPTY || legacyTemporary) {
				// Previous contract versions reserved an empty final file and used exactly <output>.tmp without a marker.
				if (state == Artifact.EMPTY) Files.delete(output);
				cleanupLegacyTemporaryFile();
				cleanupTemporaryFiles();
			} else if (currentTemporaries) {
				throw failure("orphan temporary artifacts exist without an in-progress marker", output, null);
			}
			Files.createFile(marker);
			Files.createFile(output);
			return;
		}

		Artifact state = artifact(output);
		if (state == Artifact.VALID) {
			cleanupTemporaryFiles();
			Files.delete(marker);
			throw failure("completed output exists with a stale in-progress marker; output was preserved", output, null);
		}
		if (state == Artifact.DIRECTORY_OR_SPECIAL) {
			throw failure("malformed in-progress state: output path is not a regular file", output, null);
		}
		// Marker + absent/empty/invalid regular final file proves an interrupted run. Its data was not complete.
		if (state == Artifact.EMPTY || state == Artifact.INVALID) Files.delete(output);
		cleanupTemporaryFiles();
		Files.delete(marker);
		Files.createFile(marker);
		Files.createFile(output);
	}

	private void validateMarker() throws IOException {
		if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 0)
			throw failure("malformed in-progress marker; refusing automatic cleanup", output, null);
	}

	private Artifact artifact(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Artifact.ABSENT;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return Artifact.DIRECTORY_OR_SPECIAL;
		if (Files.size(path) == 0) return Artifact.EMPTY;
		String json;
		try { json = Files.readString(path, StandardCharsets.UTF_8); }
		catch (java.nio.charset.CharacterCodingException malformedUtf8) { return Artifact.INVALID; }
		return RemoteObservationJson.isValidSchemaV2(json) ? Artifact.VALID : Artifact.INVALID;
	}

	private boolean hasTemporaryFiles() throws IOException {
		try (DirectoryStream<Path> paths = Files.newDirectoryStream(parent, temporaryPrefix + "*.tmp")) {
			return paths.iterator().hasNext();
		}
	}

	private boolean hasLegacyTemporaryFile() {
		return Files.exists(output.resolveSibling(output.getFileName() + ".tmp"), LinkOption.NOFOLLOW_LINKS);
	}

	private void cleanupLegacyTemporaryFile() throws IOException {
		Path legacyTemporary = output.resolveSibling(output.getFileName() + ".tmp");
		if (!Files.exists(legacyTemporary, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(legacyTemporary, LinkOption.NOFOLLOW_LINKS))
			throw failure("malformed legacy temporary artifact; refusing to remove it", output, null);
		Files.delete(legacyTemporary);
	}

	private void cleanupTemporaryFiles() throws IOException {
		try (DirectoryStream<Path> paths = Files.newDirectoryStream(parent, temporaryPrefix + "*.tmp")) {
			for (Path path : paths) {
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					throw failure("malformed temporary artifact; refusing to remove it", output, null);
				Files.delete(path);
			}
		}
	}

	private void closeLease() {
		if (closed) return;
		closed = true;
		release(lease, leaseChannel);
	}

	private static void release(FileLock lock, FileChannel channel) {
		try { if (lock != null && lock.isValid()) lock.release(); }
		catch (IOException failure) { System.err.println("[stp-remote-agent] unable to release output lock: " + failure); }
		try { if (channel != null) channel.close(); }
		catch (IOException failure) { System.err.println("[stp-remote-agent] unable to close output lock: " + failure); }
	}

	private static void writeAndSync(Path path, String value) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
			while (bytes.hasRemaining()) channel.write(bytes);
			channel.force(true);
		}
	}

	private static void force(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
	}

	private static FileLock acquire(FileChannel channel, Path output) throws IOException {
		try {
			FileLock lock = channel.tryLock();
			if (lock == null) throw failure("another JVM currently owns this output path", output, null);
			return lock;
		} catch (OverlappingFileLockException activeInThisJvm) {
			throw failure("another agent instance in this JVM currently owns this output path", output, activeInThisJvm);
		}
	}

	private static String pathHash(Path path) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toString().getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}

	static String temporaryPrefixFor(Path configured) {
		return ".stp-remote-" + pathHash(configured.toAbsolutePath().normalize()) + "-";
	}

	private static IllegalStateException failure(String action, Path output, Throwable cause) {
		String message = "[stp-remote-agent] " + action + " for configured output path '" + output + "'";
		return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
	}

	private enum Artifact { ABSENT, EMPTY, VALID, INVALID, DIRECTORY_OR_SPECIAL }
}
