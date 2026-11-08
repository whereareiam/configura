package me.whereareiam.configura.common.util;

import me.whereareiam.configura.type.Format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class FileUtil {
	private FileUtil() {
	}

	public static Path resolvePathWithFormat(String file, Format format) {
		return resolvePathWithExtension(Path.of(file), format.getExtension());
	}

	public static Path resolvePathWithFormat(Path path, Format format) {
		return resolvePathWithExtension(path, format.getExtension());
	}

	public static Path resolvePathWithExtension(String file, String extension) {
		return resolvePathWithExtension(Path.of(file), extension);
	}

	public static Path resolvePathWithExtension(Path path, String extension) {
		String file = path.toString();
		String lower = file.toLowerCase(Locale.ROOT);
		String normalizedExtension = normalizeExtension(extension);
		if (lower.endsWith(normalizedExtension) || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json"))
			return path;

		return Path.of(file + normalizedExtension);
	}

	/**
	 * Replaces a file with new content in one step: the content is written and synced to a temporary
	 * file beside it, which is then moved over the target, keeping the target's permissions. A
	 * failure leaves the previous content.
	 *
	 * @param target file to replace or create; its directory must exist
	 * @param bytes  new content
	 * @throws IOException when the content cannot be written or moved into place
	 */
	public static void writeAtomically(Path target, byte[] bytes) throws IOException {
		Path absolute = target.toAbsolutePath();
		Path temporary = Files.createTempFile(absolute.getParent(), ".configura-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}

			if (Files.exists(absolute) && Files.getFileStore(absolute).supportsFileAttributeView("posix"))
				Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(absolute));
			Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String normalizeExtension(String extension) {
		if (extension == null || extension.isBlank()) return "";
		return extension.startsWith(".") ? extension : "." + extension;
	}
}
