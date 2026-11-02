package com.xzxg.shop.files;

/**
 * Content sniffing equivalent to Go's {@code http.DetectContentType} for the types
 * the API accepts.
 */
public final class FileTypes {

    private FileTypes() {
    }

    public static String detect(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
                && (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return "image/gif";
        }
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data.length >= 5
                && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F'
                && data[4] == '-') {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    public static boolean isAccepted(String mimeType) {
        return mimeType.startsWith("image/") || "application/pdf".equals(mimeType);
    }

    public static String extensionFor(String filename, String mimeType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && filename.length() - dot <= 12) {
                return filename.substring(dot).toLowerCase();
            }
        }
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> ".bin";
        };
    }
}
