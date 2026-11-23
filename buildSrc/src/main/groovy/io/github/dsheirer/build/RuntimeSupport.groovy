package io.github.dsheirer.build

import java.security.MessageDigest

final class RuntimeSupport {
    static String digest(File source) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] buffer = new byte[64 * 1024]
        source.withInputStream { input ->
            int count
            while((count = input.read(buffer)) >= 0) {
                if(count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().encodeHex().toString()
    }

    static boolean matches(File archive, long size, String sha256) {
        archive.isFile() && archive.length() == size && digest(archive) == sha256
    }
}
